package local.phantom.companion.core.solana

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Що сервер видав для входу: позначку й те, що має стояти в повідомленні. */
data class SiwsChallenge(val nonce: String, val domain: String, val statement: String, val uri: String, val chainId: String)

/**
 * Sign-In-With-Solana v1 — побайтно як `SignInWithSolana.Payload.toV1Message()` у MWA 2.0:
 * так вбудований гаманець підписує те саме, що підписав би Seed Vault, а сервер розбирає одне.
 */
object Siws {
    fun message(challenge: SiwsChallenge, address: SolanaKey, issuedAt: String): String {
        val prefix = "${challenge.domain} wants you to sign in with your Solana account:\n${address.base58}"
        val fields = listOf(
            "URI: ${challenge.uri}",
            "Version: 1",
            "Chain ID: ${challenge.chainId}",
            "Nonce: ${challenge.nonce}",
            "Issued At: $issuedAt",
        )
        return "$prefix\n\n${challenge.statement}\n\n" + fields.joinToString("\n")
    }

    /** ISO-8601 з мілісекундами й «Z» — як у JS `toISOString()`, яким пишуть гаманці. */
    fun now(at: Instant = Instant.now()): String = at.truncatedTo(ChronoUnit.MILLIS).toString().let {
        if (it.contains('.')) it else it.removeSuffix("Z") + ".000Z"
    }
}

sealed interface SeekerClaimResult {
    /** Ліцензію видано; [proUntil] — ISO-дата, коли місяць скінчиться. */
    data class Granted(val licenceKey: String, val proUntil: String, val mint: String, val badge: String? = null) : SeekerClaimResult
    /** Ця SGT уже дала свій місяць — цьому гаманцю ([proUntil] відомий) або іншому. */
    data class AlreadyUsed(val proUntil: String?, val otherWallet: Boolean, val badge: String? = null) : SeekerClaimResult
    data object NotSeeker : SeekerClaimResult
    /** Сервер чи mainnet не відповіли — нічого не вирішено. */
    data object Unavailable : SeekerClaimResult
    data class Refused(val reason: String) : SeekerClaimResult
}

/** Відповідь на «Оновити знак Seeker»: місяць PRO тут не видається й не витрачається. */
sealed interface BadgeRefresh {
    data class Fresh(val badge: String) : BadgeRefresh
    /** Ні SGT, ні стейку SKR — знака немає. */
    data object Nothing : BadgeRefresh
    data object Unavailable : BadgeRefresh
    data class Refused(val reason: String) : BadgeRefresh
}

class SeekerClaimClient(private val baseUrl: String, private val http: HttpCall = PlainHttp) {

    private val root get() = baseUrl.trimEnd('/') + "/api/solana/seeker"

    suspend fun challenge(): SiwsChallenge? {
        if (baseUrl.isBlank()) return null
        val (code, text) = runCatching { http.request("$root/nonce", null) }.getOrNull() ?: return null
        return if (code == 200) parseChallenge(text) else null
    }

    /** [identity] — особа в месенджері; з нею сервер додає знак «Seeker перевірено» для купюр. */
    suspend fun claim(wallet: SolanaKey, message: String, signature: ByteArray, identity: String? = null): SeekerClaimResult {
        val body = buildJsonObject {
            put("wallet", wallet.base58)
            put("siws_message", message)
            put("siws_signature", Base58.encode(signature))
            identity?.takeIf { it.isNotBlank() }?.let { put("identity", it) }
        }.toString()
        val (code, text) = runCatching { http.request("$root/claim", body) }.getOrElse { return SeekerClaimResult.Unavailable }
        return parseClaim(code, text)
    }

    /** Свіжий знак (SGT і стейк SKR зараз) без заявки на місяць. */
    suspend fun refreshBadge(wallet: SolanaKey, message: String, signature: ByteArray, identity: String): BadgeRefresh {
        val body = buildJsonObject {
            put("wallet", wallet.base58)
            put("siws_message", message)
            put("siws_signature", Base58.encode(signature))
            put("identity", identity)
        }.toString()
        val (code, text) = runCatching { http.request("$root/badge", body) }.getOrElse { return BadgeRefresh.Unavailable }
        return parseBadge(code, text)
    }

    companion object {
        fun parseBadge(code: Int, text: String): BadgeRefresh {
            val o = runCatching { SolanaRpc.JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            val reason = (o?.get("reason") ?: o?.get("detail"))?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            return when {
                code == 200 -> (o?.get("badge") as? kotlinx.serialization.json.JsonObject)?.toString()
                    ?.takeIf { SeekerBadge.parse(it) != null }?.let { BadgeRefresh.Fresh(it) } ?: BadgeRefresh.Refused("bad_reply")
                code == 404 && reason == "not_seeker" -> BadgeRefresh.Nothing
                code >= 500 || code == 429 -> BadgeRefresh.Unavailable
                else -> BadgeRefresh.Refused(reason ?: "http_$code")
            }
        }

        fun parseChallenge(text: String): SiwsChallenge? = runCatching {
            val o = SolanaRpc.JSON.parseToJsonElement(text).jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content
            val nonce = s("nonce")?.takeIf { it.length >= 8 && it.all(Char::isLetterOrDigit) } ?: return null
            val domain = s("domain")?.takeIf { it.isNotBlank() } ?: return null
            SiwsChallenge(nonce, domain, s("statement") ?: "", s("uri") ?: "https://$domain", s("chain_id") ?: "mainnet")
        }.getOrNull()

        fun parseClaim(code: Int, text: String): SeekerClaimResult {
            val o = runCatching { SolanaRpc.JSON.parseToJsonElement(text).jsonObject }.getOrNull()
            fun s(k: String) = o?.get(k)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            val reason = s("reason") ?: s("detail")
            val badge = (o?.get("badge") as? kotlinx.serialization.json.JsonObject)?.toString()
            return when {
                code == 200 -> {
                    val key = s("license_key")
                    val until = s("pro_until")
                    if (key == null || until == null) SeekerClaimResult.Refused("bad_reply")
                    else SeekerClaimResult.Granted(key, until, s("mint").orEmpty(), badge)
                }
                code == 404 && reason == "not_seeker" -> SeekerClaimResult.NotSeeker
                code == 409 && reason == "sgt_already_used" -> SeekerClaimResult.AlreadyUsed(null, otherWallet = true, badge = badge)
                code == 409 && reason == "month_used" -> SeekerClaimResult.AlreadyUsed(s("pro_until"), otherWallet = false, badge = badge)
                code >= 500 || code == 429 -> SeekerClaimResult.Unavailable
                else -> SeekerClaimResult.Refused(reason ?: "http_$code")
            }
        }
    }
}
