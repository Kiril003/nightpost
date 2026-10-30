package local.phantom.companion.core.net.auth

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64

/**
 * Passwordless web sign-in QR protocol (`NP-AUTH-v1`).
 *
 * QR text: `NP-AUTH-v1|{challenge_id}|{nonce}|{expires_at_unix_s}`
 * Signed UTF-8 bytes: `NP-AUTH-v1|{challenge_id}|{nonce}|{pub_key}|{issued_ms}`
 * where `pub_key` is the exact base64 string sent in the approve body.
 */
object WebAuthQr {

    const val PREFIX = "NP-AUTH-v1"
    private const val PREFIX_WITH_PIPE = "$PREFIX|"

    data class Challenge(
        val challengeId: String,
        val nonce: String,
        val expiresAtSeconds: Long,
    )

    sealed interface ParseResult {
        data class Valid(val challenge: Challenge) : ParseResult
        data class Expired(val challenge: Challenge) : ParseResult
        data object Invalid : ParseResult
    }

    object Purpose {
        const val SIGN_IN = ""
        const val ERASE = "erase"
        /** The signed-in cabinet asks this phone to read its page requests; no sign-in happens. */
        const val LINK = "link"
        fun known(p: String) = p == SIGN_IN || p == ERASE || p == LINK
    }

    data class PeekInfo(
        val browser: String,
        val country: String?,
        val createdAt: String?,
        val ipMismatch: Boolean = false,
        val sameIp: Boolean = !ipMismatch,
        val purpose: String = "",
    )

    data class SignedApproval(
        val challengeId: String,
        val pubKeyB64: String,
        val nonce: String,
        val issuedMs: Long,
        val signatureB64: String,
    )

    fun parse(raw: String?, nowMs: Long = System.currentTimeMillis()): ParseResult {
        val trimmed = raw?.trim() ?: return ParseResult.Invalid
        if (!trimmed.startsWith(PREFIX_WITH_PIPE)) return ParseResult.Invalid
        val parts = trimmed.split('|')
        if (parts.size != 4 || parts[0] != PREFIX) return ParseResult.Invalid
        val challengeId = parts[1]
        val nonce = parts[2]
        val expRaw = parts[3]
        if (challengeId.isBlank() || nonce.isBlank()) return ParseResult.Invalid
        if (challengeId.any { it.isWhitespace() || it.isISOControl() }) return ParseResult.Invalid
        if (nonce.any { it.isWhitespace() || it.isISOControl() }) return ParseResult.Invalid
        val expiresAtSeconds = expRaw.toLongOrNull()?.takeIf { it > 0L } ?: return ParseResult.Invalid
        val challenge = Challenge(
            challengeId = challengeId,
            nonce = nonce,
            expiresAtSeconds = expiresAtSeconds,
        )
        return if (expiresAtSeconds * 1_000L <= nowMs) {
            ParseResult.Expired(challenge)
        } else {
            ParseResult.Valid(challenge)
        }
    }

    fun messageBytes(
        challengeId: String,
        nonce: String,
        pubKeyB64: String,
        issuedMs: Long,
    ): ByteArray =
        "$PREFIX|$challengeId|$nonce|$pubKeyB64|$issuedMs".toByteArray(Charsets.UTF_8)

    fun signApproval(
        challenge: Challenge,
        privateKey: ByteArray,
        issuedMs: Long,
    ): SignedApproval {
        require(privateKey.size == Ed25519.PRIVATE_KEY_SIZE) {
            "Ed25519 private key must be ${Ed25519.PRIVATE_KEY_SIZE} bytes"
        }
        val pubKeyBytes = Ed25519.publicKeyFromPrivate(privateKey)
        val pubKeyB64 = bytesToB64(pubKeyBytes)
        val msg = messageBytes(
            challengeId = challenge.challengeId,
            nonce = challenge.nonce,
            pubKeyB64 = pubKeyB64,
            issuedMs = issuedMs,
        )
        val sigBytes = Ed25519.sign(privateKey, msg)
        val sigB64 = bytesToB64(sigBytes)
        return SignedApproval(
            challengeId = challenge.challengeId,
            pubKeyB64 = pubKeyB64,
            nonce = challenge.nonce,
            issuedMs = issuedMs,
            signatureB64 = sigB64,
        )
    }

    fun verifyApproval(approval: SignedApproval): Boolean {
        val pubKey = runCatching { b64ToBytes(approval.pubKeyB64) }.getOrNull() ?: return false
        val sig = runCatching { b64ToBytes(approval.signatureB64) }.getOrNull() ?: return false
        if (pubKey.size != Ed25519.PUBLIC_KEY_SIZE || sig.size != Ed25519.SIGNATURE_SIZE) return false
        val msg = messageBytes(
            challengeId = approval.challengeId,
            nonce = approval.nonce,
            pubKeyB64 = approval.pubKeyB64,
            issuedMs = approval.issuedMs,
        )
        return Ed25519.verify(pubKey, msg, sig)
    }

    fun peekRequestJson(challenge: Challenge): String = buildJsonObject {
        put("challenge_id", challenge.challengeId)
        put("nonce", challenge.nonce)
    }.toString()

    fun approveRequestJson(approval: SignedApproval): String = buildJsonObject {
        put("challenge_id", approval.challengeId)
        put("pub_key", approval.pubKeyB64)
        put("nonce", approval.nonce)
        put("issued_ms", approval.issuedMs)
        put("signature", approval.signatureB64)
    }.toString()

    fun parsePeekResponse(body: String): PeekInfo? {
        val obj = runCatching { Json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val browser = (obj["browser"] as? JsonPrimitive)
            ?.takeIf { obj["browser"] !is JsonNull }
            ?.contentOrNull?.trim().orEmpty()
        if (browser.isEmpty()) return null
        val country = (obj["country"] as? JsonPrimitive)
            ?.takeIf { obj["country"] !is JsonNull }
            ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val createdAt = (obj["created_at"] as? JsonPrimitive)
            ?.takeIf { obj["created_at"] !is JsonNull }
            ?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val sameIpField = (obj["same_ip"] as? JsonPrimitive)
            ?.takeIf { obj["same_ip"] !is JsonNull }
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
        val ipMismatchField = (obj["ip_mismatch"] as? JsonPrimitive)
            ?.takeIf { obj["ip_mismatch"] !is JsonNull }
            ?.contentOrNull
            ?.toBooleanStrictOrNull()
        val ipMismatch = ipMismatchField ?: (sameIpField?.not() ?: false)
        val sameIp = sameIpField ?: !ipMismatch
        val purpose = (obj["purpose"] as? JsonPrimitive)
            ?.takeIf { obj["purpose"] !is JsonNull }
            ?.contentOrNull?.trim().orEmpty()
        return PeekInfo(
            browser = browser,
            country = country,
            createdAt = createdAt,
            ipMismatch = ipMismatch,
            sameIp = sameIp,
            purpose = purpose,
        )
    }
}

class WebAuthQrClient private constructor(
    private val baseUrl: String,
    http: HttpClient?,
    timeoutMs: Long,
) {

    private val client: HttpClient = http ?: HttpClient(OkHttp) {
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
        expectSuccess = false
    }

    sealed interface PeekOutcome {
        data class Found(val info: WebAuthQr.PeekInfo) : PeekOutcome
        data object Expired : PeekOutcome
        data object Unreachable : PeekOutcome
    }

    sealed interface ApproveOutcome {
        data object Approved : ApproveOutcome
        data object Expired : ApproveOutcome
        data object Refused : ApproveOutcome
        data object Unreachable : ApproveOutcome
    }

    suspend fun peek(challenge: WebAuthQr.Challenge): PeekOutcome = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/auth/qr/peek") {
                contentType(ContentType.Application.Json)
                setBody(WebAuthQr.peekRequestJson(challenge))
            }
            when (response.status) {
                HttpStatusCode.OK -> {
                    val info = WebAuthQr.parsePeekResponse(response.bodyAsText())
                    if (info != null) PeekOutcome.Found(info) else PeekOutcome.Unreachable
                }
                HttpStatusCode.NotFound, HttpStatusCode.Gone -> PeekOutcome.Expired
                else -> PeekOutcome.Unreachable
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            PeekOutcome.Unreachable
        }
    }

    suspend fun approve(approval: WebAuthQr.SignedApproval): ApproveOutcome = withContext(Dispatchers.IO) {
        try {
            val response = client.post("$baseUrl/api/auth/qr/approve") {
                contentType(ContentType.Application.Json)
                setBody(WebAuthQr.approveRequestJson(approval))
            }
            when {
                response.status == HttpStatusCode.OK -> ApproveOutcome.Approved
                response.status == HttpStatusCode.NotFound || response.status == HttpStatusCode.Gone ->
                    ApproveOutcome.Expired
                response.status.value in 400..499 -> ApproveOutcome.Refused
                else -> ApproveOutcome.Unreachable
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            ApproveOutcome.Unreachable
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 8_000L
        private const val HTTPS = "https://"

        fun open(
            baseUrl: String,
            http: HttpClient? = null,
            timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        ): WebAuthQrClient? {
            val url = baseUrl.trim().trimEnd('/')
            if (!url.startsWith(HTTPS) || url.length <= HTTPS.length) return null
            return WebAuthQrClient(url, http, timeoutMs)
        }
    }
}
