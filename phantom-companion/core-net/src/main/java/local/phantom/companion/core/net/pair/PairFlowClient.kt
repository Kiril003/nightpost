package local.phantom.companion.core.net.pair

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.bytesToB64

/**
 * Phone-side metadata the desktop UI shows in the "<phone> claimed"
 * toast and stores onto [PairedDevice]. Optional fields default to
 * empty so a paranoid integrator can opt out of leaking model strings.
 */
@Serializable
data class DeviceInfo(
    val name: String,
    val model: String,
    val platform: String = "android",
    @SerialName("os_version") val osVersion: String? = null,
)

/**
 * Body of POST /pair/claim. Field names mirror the Pydantic schema in
 * `PHANTOM OS/src/backend/api/routes_pair.py.PairClaimRequest`.
 * Internal because callers go through [PairFlowClient.claim] — there
 * is no use case where a caller wants to construct a request manually
 * and skip the proof-building helper.
 */
@Serializable
internal data class PairClaimRequest(
    @SerialName("pair_id") val pairId: String,
    @SerialName("client_pub") val clientPubB64: String,
    @SerialName("device_pub_ed25519") val devicePubB64: String,
    @SerialName("nonce_echo") val nonceEcho: String,
    @SerialName("client_proof") val clientProofB64: String,
    val device: DeviceInfo,
    @SerialName("profile_id") val profileId: String? = null,
    @SerialName("profile_display_name") val profileDisplayName: String? = null,
    // Довічна сітьова особа телефона. Не ключі парингу: саме з цих трьох полів
    // ПК складе ключ каналу й адресу скриньки у сховку.
    @SerialName("peer_id") val peerId: String? = null,
    @SerialName("peer_pub_ed25519") val peerPubKeyB64: String? = null,
    @SerialName("peer_dh_x25519") val peerDhPubKeyB64: String? = null,
)

@Serializable
data class PairUserDto(
    val id: String,
    val username: String,
    val role: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class PairProfileDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    val role: String,
    @SerialName("avatar_uri") val avatarUri: String? = null,
    @SerialName("is_primary") val isPrimary: Boolean,
)

@Serializable
data class PairClaimResponse(
    @SerialName("device_jwt") val deviceJwt: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("server_proof") val serverProof: String,
    val user: PairUserDto,
    val profile: PairProfileDto,
    @SerialName("computer_name") val computerName: String = "",
    // Дзеркало сітьового блоку телефона. Порожньо — вузол старої збірки:
    // адресу сховка з ним не скласти, і екран мусить сказати це вголос.
    @SerialName("node_id") val nodeId: String = "",
    @SerialName("node_pub_ed25519") val nodePubKey: String = "",
    @SerialName("node_dh_x25519") val nodeDhPubKey: String = "",
) {
    /** null — вузол сітьового блоку не прислав; це стан, а не помилка. */
    fun nodeMesh(): PairProofs.MeshBlock? =
        PairProofs.MeshBlock(nodeId, nodePubKey, nodeDhPubKey).takeIf { it.isComplete }
}

/**
 * Composite return value of [PairFlowClient.claim]. Carries both the
 * server's response *and* the long-term device private key the phone
 * just generated — the caller must persist that key onto a
 * [local.phantom.companion.core.data.profile.PairedHost] so future
 * `/pair/refresh` calls (Phase 1-C-4+) can sign the challenge with it.
 *
 * The private key never leaves the phone and is only emitted by this
 * one call site. Loss of the key requires a fresh /pair/init scan.
 */
data class PairClaimOutcome(
    val response: PairClaimResponse,
    val deviceEd25519PrivateKeyB64: String,
    val deviceEd25519PublicKeyB64: String,
    val sharedKey: ByteArray,
    val successfulBaseUrl: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PairClaimOutcome) return false
        return response == other.response &&
            deviceEd25519PrivateKeyB64 == other.deviceEd25519PrivateKeyB64 &&
            deviceEd25519PublicKeyB64 == other.deviceEd25519PublicKeyB64 &&
            sharedKey.contentEquals(other.sharedKey) &&
            successfulBaseUrl == other.successfulBaseUrl
    }

    override fun hashCode(): Int {
        var result = response.hashCode()
        result = 31 * result + deviceEd25519PrivateKeyB64.hashCode()
        result = 31 * result + deviceEd25519PublicKeyB64.hashCode()
        result = 31 * result + sharedKey.contentHashCode()
        result = 31 * result + successfulBaseUrl.hashCode()
        return result
    }
}

/** Thrown by [PairFlowClient.claim] on transport / HTTP errors. */
class PairFlowException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Ktor client that drives /pair/claim end-to-end.
 *
 *   1. Generate ephemeral X25519 keypair (the phone's pair-only key).
 *   2. Generate long-term Ed25519 keypair (the device's identity).
 *   3. Build proof via [PairProofs.buildClientProof].
 *   4. POST /pair/claim with the proof + device metadata + optional
 *      profile_id / profile_display_name.
 *   5. Return [PairClaimOutcome] carrying the server response + the
 *      Ed25519 private key for the caller to store.
 *
 * The [engine] parameter exposes Ktor's [HttpClientEngineFactory] so
 * tests can swap in `MockEngine`. Production code passes the default
 * [OkHttp] factory (already a transitive dep of `:core-net`).
 */
class PairFlowClient(
    engine: HttpClientEngineFactory<*>? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val pin = java.util.concurrent.atomic.AtomicReference("")

    private val http: HttpClient =
        if (engine != null) {
            HttpClient(engine) { configure() }
        } else {
            HttpClient(OkHttp) {
                engine {
                    preconfigured = PinnedTrust
                        .apply(okhttp3.OkHttpClient.Builder()) { pin.get() }
                        .build()
                }
                configure()
            }
        }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
        expectSuccess = false
    }

    /**
     * Secondary constructor for callers who already hold an
     * [HttpClientEngine] instance (e.g. test runners that reused one
     * MockEngine across the suite).
     */
    constructor(engineInstance: HttpClientEngine, timeoutMs: Long = DEFAULT_TIMEOUT_MS) : this(
        engine = singletonFactory(engineInstance),
        timeoutMs = timeoutMs,
    )

    suspend fun claim(
        qr: PairingQr,
        device: DeviceInfo,
        profileId: String? = null,
        profileDisplayName: String? = null,
        routes: local.phantom.companion.core.net.relay.NodeRoutes? = null,
        /**
         * Сітьова особа цього телефона. Без неї паринг лишається тим, чим був
         * до 03.09.2026: вузол дістає право писати телефону по дроту й не
         * дістає жодного способу покласти йому лист у сховок.
         */
        mesh: PairProofs.MeshBlock? = null,
    ): PairClaimOutcome {
        if (qr.isExpired()) {
            throw PairFlowException("QR is expired (exp=${qr.exp})")
        }

        val ephemeral = X25519.generateKeypair()
        val deviceKp = Ed25519.generateKeypair()
        val carried = mesh?.takeIf { it.isComplete }
        val proof = PairProofs.buildClientProof(
            qr,
            clientPriv = ephemeral.privateKey,
            devicePubEd25519Raw = deviceKp.publicKey,
            mesh = carried,
        )
        val request = PairClaimRequest(
            pairId = qr.pairId,
            clientPubB64 = bytesToB64(ephemeral.publicKey),
            devicePubB64 = bytesToB64(deviceKp.publicKey),
            nonceEcho = qr.nonce,
            clientProofB64 = proof.clientProofB64,
            device = device,
            profileId = profileId,
            profileDisplayName = profileDisplayName,
            peerId = carried?.peerId,
            peerPubKeyB64 = carried?.pubKeyB64,
            peerDhPubKeyB64 = carried?.dhPubKeyB64,
        )

        pin.set(qr.serverCertSha256)
        val candidates = routes?.resolveAll(qr)?.takeIf { it.isNotEmpty() } ?: qr.candidateUrls()
        var lastError: Throwable? = null

        for (baseUrl in candidates) {
            val url = "$baseUrl/api/v1/pair/claim"
            android.util.Log.i("PairFlowClient", "Attempting claim at $url (attempt ${candidates.indexOf(baseUrl) + 1}/${candidates.size})")

            try {
                val httpResponse = http.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                    timeout {
                        requestTimeoutMillis = 5_000
                        connectTimeoutMillis = 3_000
                    }
                }

                // /pair/claim is single-shot — the server consumes the session on
                // first accepted HTTP request and returns 410 on any repeat. Only
                // retry across candidates when we never reached the server at all
                // (transport errors below). Any HTTP response means the server saw
                // the request, so propagate immediately rather than burning the
                // session on the next candidate.
                if (httpResponse.status == HttpStatusCode.OK) {
                    val parsed: PairClaimResponse = httpResponse.body()
                    android.util.Log.i("PairFlowClient", "Successfully claimed pairing via $baseUrl")
                    return PairClaimOutcome(
                        response = parsed,
                        deviceEd25519PrivateKeyB64 = bytesToB64(deviceKp.privateKey),
                        deviceEd25519PublicKeyB64 = bytesToB64(deviceKp.publicKey),
                        sharedKey = proof.sharedKey,
                        successfulBaseUrl = baseUrl,
                    )
                } else {
                    val bodyText = runCatching { httpResponse.bodyAsText() }.getOrDefault("")
                    android.util.Log.w("PairFlowClient", "Endpoint $url rejected claim: ${httpResponse.status} - $bodyText")
                    // Do NOT try the next candidate — server consumed the session.
                    throw PairFlowException("HTTP ${httpResponse.status.value} from $url: $bodyText")
                }
            } catch (e: PairFlowException) {
                // HTTP-level rejection — re-throw immediately, do not continue loop.
                throw e
            } catch (t: java.io.IOException) {
                // Transport error (connection refused, DNS failure, timeout) before
                // the server accepted any HTTP request — safe to try the next candidate.
                android.util.Log.w("PairFlowClient", "Endpoint $url unreachable (transport): ${t.message}")
                lastError = t
            } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
                // Coroutine timeout before server responded — safe to try next candidate.
                android.util.Log.w("PairFlowClient", "Endpoint $url timed out: ${t.message}")
                lastError = t
            }
        }

        throw PairFlowException(
            "Failed to claim pairing across ${candidates.size} endpoints. Last error: ${lastError?.message}",
            lastError
        )
    }

    /** Release the underlying HttpClient; safe to call repeatedly. */
    fun close() {
        runCatching { http.close() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 10_000

        // Tiny helper that wraps an already-constructed engine inside
        // the factory shape Ktor's HttpClient(engine) ctor expects.
        // Inlining a factory here saves callers from learning Ktor's
        // engine-vs-factory split.
        private fun singletonFactory(
            engine: HttpClientEngine,
        ): HttpClientEngineFactory<HttpClientEngineConfigShim> =
            object : HttpClientEngineFactory<HttpClientEngineConfigShim> {
                override fun create(
                    block: HttpClientEngineConfigShim.() -> Unit,
                ): HttpClientEngine = engine
            }

        // The factory's type parameter must satisfy
        // `HttpClientEngineConfig`; we never read the config so an
        // empty subclass is enough.
        private class HttpClientEngineConfigShim :
            io.ktor.client.engine.HttpClientEngineConfig()
    }
}
