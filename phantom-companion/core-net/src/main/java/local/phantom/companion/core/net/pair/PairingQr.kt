package local.phantom.companion.core.net.pair

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * QR payload the desktop renders for /pair/init. Shape is locked to
 * `PHANTOM OS/src/backend/security/pair_crypto.build_qr_payload`:
 *
 *     {
 *       "v": 1,
 *       "host": "phantom.local",
 *       "ip":   "192.168.1.10",
 *       "port": 8000,
 *       "pair_id": "...",
 *       "server_pub": "<base64 X25519>",
 *       "server_cert_sha256": "dev-no-pin" | "<hex>",
 *       "exp": 1700000000,
 *       "nonce": "<base64>"
 *     }
 *
 * The phone parses this off the QR, runs ECDH against `server_pub`,
 * and posts the proof back to /pair/claim derived from [baseUrl].
 *
 * Why a domain class (not just `JsonObject`): the phone speaks UTC
 * `exp` for expiry checks and toggles the request URL's scheme based
 * on `server_cert_sha256`. Both happen at multiple sites; centralising
 * the parsing keeps drift-prevention guarantees in one place.
 */
@Serializable
data class PairingQr(
    val v: Int,
    @SerialName("pair_id") val pairId: String,
    @SerialName("server_pub") val serverPubB64: String,
    @SerialName("server_cert_sha256") val serverCertSha256: String = DEV_NO_PIN_SENTINEL,
    val exp: Long,
    val nonce: String,
    /** 
     * v2: List of connection candidates. If empty (v1), 
     * we use legacy host/ip/port fields.
     */
    val endpoints: List<Endpoint> = emptyList(),
    // Legacy fields kept for v1 compatibility
    val host: String = "",
    val ip: String = "",
    val port: Int = 0,
    /** «self» — QR передачі особи (docs/ONE_SELF.md); порожньо — спарювання. */
    val purpose: String = "",
    /** Імʼя вузла в QR «особа»: особа їде лише тому ПК, з яким я спарений. */
    @SerialName("node_id") val nodeId: String = "",
) {
    @Serializable
    data class Endpoint(
        val type: String, // "lan", "relay", "tailscale"
        val url: String,
        @SerialName("device_id") val deviceId: String? = null,
        /** Адреса вузла на точці зустрічі. Є лише в `relay`. */
        @SerialName("node_id") val nodeId: String? = null,
    ) {
        val isRelay: Boolean get() = type.equals("relay", ignoreCase = true)

        val isDirect: Boolean get() = !isRelay
    }

    init {
        require(v >= 1) { "Unsupported QR version: $v" }
        require(pairId.isNotBlank()) { "PairingQr.pairId must not be blank" }
        require(serverPubB64.isNotBlank()) {
            "PairingQr.serverPubB64 must not be blank"
        }
        require(nonce.isNotBlank()) { "PairingQr.nonce must not be blank" }

        if (v == 1) {
            require(host.isNotBlank() || ip.isNotBlank()) {
                "v1 PairingQr requires host or ip"
            }
            require(port in 1..65535) { "v1 PairingQr requires valid port" }
        } else {
            require(endpoints.isNotEmpty()) { "v2+ PairingQr requires endpoints" }
        }
    }

    /**
     * `dev-no-pin` is the well-known sentinel the backend stamps in dev
     * builds (uvicorn HTTP on port 8000). When this flag is present the
     * phone uses cleartext to reach the host; production deployments
     * carry a real SHA-256 cert fingerprint and the request goes over
     * HTTPS.
     */
    val isDevNoPin: Boolean get() = serverCertSha256 == DEV_NO_PIN_SENTINEL

    /**
     * Returns true when the QR's `exp` is in the past relative to
     * [nowEpochSec]. The default `nowEpochSec` lambda evaluates lazily
     * so tests can pin time deterministically.
     */
    fun isExpired(nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 }): Boolean =
        nowEpochSec() > exp

    /**
     * Returns the primary base URL (LAN first for v2, legacy URL for v1).
     * Convenience wrapper over [candidateUrls] for single-endpoint callers.
     */
    fun baseUrl(): String =
        candidateUrls().firstOrNull() ?: candidateEndpoints().first().url

    /**
     * Returns all candidate base URLs, ordered by priority.
     *
     * v1 QR carries both `host` (typically the mDNS name like
     * `phantom.local`) and `ip` (the raw LAN address). Android phones
     * can fail to resolve `.local` names when not on the same
     * multicast/Bonjour domain or when the OS-level resolver disables
     * mDNS for the target SDK. We therefore return both URLs so
     * [PairFlowClient.claim] can try the mDNS hostname first and fall
     * through to the raw IP on `UnknownHostException` /
     * `ConnectException`. Bug surfaced 2026-05-09 during live E2E on
     * SM-A566B / Android 16: the phone could see the QR but never
     * reach the backend at `phantom.local`.
     *
     * v2 QR's `endpoints[]` already carries explicit URLs and is
     * returned as-is.
     */
    fun candidateUrls(): List<String> = candidateEndpoints()
        .filter { it.isDirect }
        .map { it.url }

    /**
     * Усі шляхи до вузла, включно з тими, що потребують тунелю.
     *
     * `relay` сюди входить, а в [candidateUrls] — ні: за адресою точки зустрічі
     * вузла немає, туди можна лише прокласти трубу (див. RelayTunnel).
     */
    fun candidateEndpoints(): List<Endpoint> {
        if (v == 1 || endpoints.isEmpty()) {
            return legacyCandidates().map { Endpoint(type = "lan", url = it) }
        }
        return endpoints.map { it.copy(url = it.url.trimEnd('/')) }
    }

    private fun legacyCandidates(): List<String> {
        val trimmedHost = host.trim()
        // If `host` is already a fully-qualified URL, treat it as a
        // single hard-coded endpoint and skip the host/ip composition.
        if (trimmedHost.startsWith("http://", ignoreCase = true) ||
            trimmedHost.startsWith("https://", ignoreCase = true)
        ) {
            return listOf(trimmedHost.trimEnd('/'))
        }
        val scheme = if (isDevNoPin) "http" else "https"
        val out = LinkedHashSet<String>() // de-duplicate when host == ip
        if (trimmedHost.isNotBlank()) {
            out += "$scheme://$trimmedHost:$port"
        }
        val trimmedIp = ip.trim()
        if (trimmedIp.isNotBlank()) {
            out += "$scheme://$trimmedIp:$port"
        }
        check(out.isNotEmpty()) {
            "PairingQr v1 must carry host or ip (init validates this — defensive guard)"
        }
        return out.toList()
    }

    companion object {
        const val CURRENT_VERSION: Int = 2
        const val DEV_NO_PIN_SENTINEL: String = "dev-no-pin"

        private val Format = Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

        /**
         * Parse a raw JSON string from the QR. Throws
         * [IllegalArgumentException] on malformed payloads (the
         * underlying [kotlinx.serialization.SerializationException]
         * is wrapped to keep the call sites narrow).
         */
        fun fromJson(raw: String): PairingQr {
            return try {
                Format.decodeFromString(serializer(), raw)
            } catch (t: Throwable) {
                throw IllegalArgumentException(
                    "Failed to decode PairingQr: ${t.message}", t,
                )
            }
        }
    }
}
