package local.phantom.companion.ui.messenger

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.data.DeviceIdentityStore
import local.phantom.companion.data.VenueClient
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * `/support/…` з бандла → сервер (`/api/support/…`), підписано ключем пристрою.
 *
 * Вузла на телефоні нема, тож скарги, звернення й звіти про збій ідуть на сервер,
 * де їх бачить персонал (_briefs/v3-build/support-phone.md). Ключ не виходить із
 * процесу: у дріт іде лише підпис над `NP-SUPPORT-v1|шлях|ключ|число|час|sha256(тіло)`.
 * Сервер без цих ручок (ще не викладено) або без мережі → `503 support_pending`,
 * і веб тримає звернення в черзі з чесним підписом.
 */
class WebMessengerSupport(
    private val baseUrl: String,
    private val signer: Signer,
    private val http: VenueClient.Http,
) {

    data class Proof(val pubKey: String, val nonce: String, val issuedMs: Long, val signature: String)

    fun interface Signer {
        fun sign(path: String, body: String): Proof?
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[0] == SEGMENT

    suspend fun handle(method: String, seg: List<String>, body: String?): String {
        val target = when {
            seg.size == 2 && seg[0] == "support" && seg[1] == "reports" && method == "POST" -> "/api/support/reports"
            seg.size == 2 && seg[0] == "support" && seg[1] == "reports" && method == "GET" -> "/api/support/reports/list"
            seg.size == 4 && seg[0] == "support" && seg[1] == "reports" && seg[3] == "appeal" && method == "POST" ->
                idOrNull(seg[2])?.let { "/api/support/reports/$it/appeal" }
            seg.size == 2 && seg[0] == "support" && seg[1] == "tickets" && method == "POST" -> "/api/support/tickets"
            seg.size == 2 && seg[0] == "support" && seg[1] == "tickets" && method == "GET" -> "/api/support/tickets/list"
            seg.size == 4 && seg[0] == "support" && seg[1] == "tickets" && seg[3] == "messages" && method == "POST" ->
                idOrNull(seg[2])?.let { "/api/support/tickets/$it/messages" }
            seg.size == 2 && seg[0] == "support" && seg[1] == "crash" && method == "POST" -> "/api/support/crash"
            seg.size == 2 && seg[0] == "support" && seg[1] == "notices" && method == "POST" -> "/api/support/notices"
            seg.size == 4 && seg[0] == "support" && seg[1] == "notices" && seg[3] == "appeal" && method == "POST" ->
                seg[2].takeIf { NOTICE_ID.matches(it) }?.let { "/api/support/notices/$it/appeal" }
            else -> return fail(404, "not_found")
        } ?: return fail(400, "bad_id")
        val payload = if (method == "GET") "" else body.orEmpty()
        val proof = signer.sign(target, payload) ?: return fail(503, PENDING)
        val envelope = JSONObject()
            .put("pub_key", proof.pubKey)
            .put("nonce", proof.nonce)
            .put("issued_ms", proof.issuedMs)
            .put("signature", proof.signature)
            .put("body", payload)
            .toString()
        val (status, text) = withTimeoutOrNull(DEADLINE_MS) {
            http.call("POST", baseUrl.trimEnd('/') + target, envelope)
        } ?: return fail(503, PENDING)
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull().orEmpty().take(200)
        return when {
            status in 200..299 -> """{"ok":true,"status":200,"data":${text.ifBlank { "null" }}}"""
            status == 404 && text.contains("\"Not Found\"") -> fail(503, PENDING)
            status >= 500 || status == 408 -> fail(503, PENDING)
            status == 429 -> fail(429, "support_busy", detail)
            status == 401 && detail.contains("годинник") -> fail(401, "clock_skew", detail)
            status == 401 && detail.contains("вже використано") -> fail(401, "replay", detail)
            status == 401 -> fail(401, "support_unauthorized", detail)
            else -> fail(status, "support_refused", detail)
        }
    }

    private fun idOrNull(raw: String): String? = raw.takeIf { ID.matches(it) }

    private fun fail(status: Int, code: String, detail: String = ""): String =
        JSONObject().put("ok", false).put("status", status).put("code", code)
            .put("detail", detail.ifBlank { code }).toString()

    companion object {
        const val SEGMENT = "support"
        const val PENDING = "support_pending"
        const val DEADLINE_MS = 45_000L
        private val ID = Regex("^[A-Za-z0-9_.:-]{1,128}$")
        private val NOTICE_ID = Regex("^[0-9]{1,12}$")

        internal fun sentence(path: String, pub: String, nonce: String, issuedMs: Long, body: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(body.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            return "NP-SUPPORT-v1|$path|$pub|$nonce|$issuedMs|$digest"
        }

        fun deviceSigner(
            identity: () -> DeviceIdentityStore.Identity?,
            nowMs: () -> Long = { System.currentTimeMillis() },
        ): Signer = Signer { path, body ->
            val id = identity() ?: return@Signer null
            val raw = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val nonce = raw.joinToString("") { "%02x".format(it) }
            val at = nowMs()
            val bytes = sentence(path, id.publicKeyB64, nonce, at, body).toByteArray(Charsets.UTF_8)
            val sig = runCatching { Ed25519.sign(id.privateKey, bytes) }.getOrNull() ?: return@Signer null
            Proof(id.publicKeyB64, nonce, at, Base64.getEncoder().encodeToString(sig))
        }
    }
}
