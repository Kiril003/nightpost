package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.MessageDigest
import java.util.Base64

/** Хто платить комісію мережі замість людини. Видає сервер сайту (`platform-site/server/sponsor.py`). */
data class SponsorInfo(val cluster: Cluster, val feePayer: SolanaKey)

sealed interface SponsorResult {
    data class Sent(val signature: String) : SponsorResult
    /** Спонсор розібрав транзакцію і відмовив (правило, квота) — це не «немає звʼязку». */
    data class Refused(val reason: String) : SponsorResult
    /** Спонсора немає або він недосяжний — людина платить сама звичайним шляхом. */
    data object Unavailable : SponsorResult
}

class SponsorClient(private val baseUrl: String, private val http: HttpCall = PlainHttp) {

    val configured: Boolean get() = baseUrl.isNotBlank()

    suspend fun info(): SponsorInfo? {
        if (!configured) return null
        val (code, text) = runCatching { http.request(url("/api/solana/sponsor"), null) }.getOrNull() ?: return null
        if (code != 200) return null
        return parseInfo(text)
    }

    /** [tx] — частково підписана транзакція: слот спонсора порожній, слот [signer] заповнено. */
    suspend fun submit(tx: ByteArray, signer: Ed25519Keypair): SponsorResult {
        if (!configured) return SponsorResult.Unavailable
        val message = SolanaTx.messageOf(tx) ?: return SponsorResult.Refused("bad_tx")
        val body = buildJsonObject {
            put("tx_base64", Base64.getEncoder().encodeToString(tx))
            put("identity", signer.publicKey.base58)
            put("identity_sig", Base58.encode(signer.sign(identityPayload(message))))
        }.toString()
        val (code, text) = runCatching { http.request(url("/api/solana/sponsor/sign"), body) }
            .getOrElse { return SponsorResult.Unavailable }
        val o = runCatching { SolanaRpc.JSON.parseToJsonElement(text).jsonObject }.getOrNull()
        return when {
            code == 200 -> o?.get("signature")?.jsonPrimitive?.content?.takeIf { Base58.decode(it)?.size == 64 }
                ?.let { SponsorResult.Sent(it) } ?: SponsorResult.Refused("bad_reply")
            code == 503 || code == 404 -> SponsorResult.Unavailable
            else -> SponsorResult.Refused(o?.reason() ?: "http_$code")
        }
    }

    private fun url(path: String) = baseUrl.trimEnd('/') + path

    private fun JsonObject.reason(): String? =
        (get("detail") ?: get("reason"))?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    companion object {
        private val PREFIX = "phantom-sponsor:v1:".toByteArray()

        /** Те, що підписує людина поверх самої транзакції: префікс домену + SHA-256 повідомлення. */
        fun identityPayload(message: ByteArray): ByteArray = PREFIX + MessageDigest.getInstance("SHA-256").digest(message)

        fun parseInfo(text: String): SponsorInfo? = runCatching {
            val o = SolanaRpc.JSON.parseToJsonElement(text).jsonObject
            val cluster = Cluster.of(o["cluster"]?.jsonPrimitive?.content) ?: return null
            val payer = SolanaKey.parse(o["fee_payer"]?.jsonPrimitive?.content.orEmpty()) ?: return null
            SponsorInfo(cluster, payer)
        }.getOrNull()
    }
}

/** Хто платить за цей переказ. Спонсор — лише для токенів, вбудованого гаманця і свого кластера. */
object FeeRoute {
    sealed interface Route {
        data class Sponsored(val feePayer: SolanaKey) : Route
        data object Self : Route
    }

    fun choose(sponsor: SponsorInfo?, kind: WalletKind, cluster: Cluster, coin: Coin): Route = when {
        sponsor == null || sponsor.cluster != cluster -> Route.Self
        // SOL переказує той, у кого SOL є, — тоді й комісію він має чим заплатити.
        coin.native -> Route.Self
        // Зовнішній гаманець через MWA сам відправляє підписане; частковий підпис він не віддає.
        kind != WalletKind.BUILT_IN -> Route.Self
        else -> Route.Sponsored(sponsor.feePayer)
    }

    /** Коли за людину платить спонсор і SOL у неї немає — SOL на екрані не показується зовсім. */
    fun hidesSol(state: WalletState): Boolean =
        state.sponsor?.cluster == state.cluster && state.kind == WalletKind.BUILT_IN && (state.balances?.get(Coin.SOL) ?: 0L) == 0L

    /** Чим можна переказувати: SOL — лише коли він видимий людині. */
    fun payableCoins(state: WalletState): List<Coin> = Coin.on(state.cluster).filter { !(it.native && hidesSol(state)) }

    fun visibleBalances(state: WalletState): Map<Coin, Long>? =
        state.balances?.let { b -> if (hidesSol(state)) b - Coin.SOL else b }
}
