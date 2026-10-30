package local.phantom.companion.core.net.pair

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import android.util.Log
import local.phantom.companion.core.net.discovery.MdnsDiscovery

/**
 * Resolves an 8-digit numeric PIN into a full [PairingQr] payload.
 *
 * Implements two discovery paths, tried in order:
 *   1. Local Discovery (mDNS/LAN): Scans for `_phantom._tcp.` on the local
 *      network using [MdnsDiscovery]. If a Phantom-OS host is found within
 *      [LAN_TIMEOUT_MS], queries it directly at /api/v1/pair/resolve/<pin>.
 *      TXT records from mDNS carry only `version` and `app` — not enough
 *      for a PairingQr — so we still do an HTTPS call, but to the LAN host.
 *
 * Другого шляху немає: код — доказ присутності поруч. Здалеку заходять по QR,
 * який несе і адресу вузла на точці зустрічі, і відбиток його сертифіката.
 */
class PairDiscoveryClient(
    engine: HttpClientEngineFactory<*> = OkHttp,
    private val mdnsDiscovery: MdnsDiscovery? = null,
) {
    /**
     * Secondary constructor for callers who already hold an [HttpClientEngine]
     * instance (e.g. test runners using MockEngine).
     */
    constructor(
        engineInstance: HttpClientEngine,
        mdnsDiscovery: MdnsDiscovery? = null,
    ) : this(
        engine = singletonFactory(engineInstance),
        mdnsDiscovery = mdnsDiscovery,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient(engine) {
        if (engine === OkHttp) {
            engine {
                (this as? io.ktor.client.engine.okhttp.OkHttpConfig)?.preconfigured =
                    PinnedTrust.bootstrap(okhttp3.OkHttpClient.Builder()).build()
            }
        }
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 10_000
            connectTimeoutMillis = 5_000
        }
    }

    /**
     * Resolve PIN to QR config. Tries LAN first (3 s timeout), then relay.
     * @param pin 8-digit numeric string.
     */
    suspend fun resolve(pin: String): Result<PairingQr> {
        if (pin.length < 4 || !pin.all { it.isDigit() }) {
            return Result.failure(IllegalArgumentException("Invalid PIN format"))
        }

        // Path 1: LAN Discovery via mDNS
        if (mdnsDiscovery != null) {
            Log.i(TAG, "PIN $pin — trying LAN discovery (timeout ${LAN_TIMEOUT_MS}ms)")
            val lanHost = mdnsDiscovery.discoverFirst(timeoutMs = LAN_TIMEOUT_MS)
            if (lanHost != null) {
                Log.i(TAG, "LAN discovery hit: ${lanHost.host}:${lanHost.port} — querying directly")
                val lanResult = resolveFromHost(pin, lanHost.host, lanHost.port)
                if (lanResult.isSuccess) return lanResult
                Log.w(TAG, "LAN host responded but resolve failed: ${lanResult.exceptionOrNull()?.message}")
            } else {
                Log.i(TAG, "LAN miss → relay fallback")
            }
        }

        // Коду в точки зустрічі спитати нема як: вона не знає, чий це вузол, а
        // перебір восьми цифр через неї був би подарунком тому, хто його робить.
        // Код — це доказ присутності поруч, і працює він рівно поруч.
        Log.i(TAG, "PIN $pin — вузла в цій мережі немає; код працює лише поруч")
        return Result.failure(
            IllegalStateException("Вузла в цій мережі немає. Для входу здалеку потрібен QR."),
        )
    }

    private suspend fun resolveFromHost(pin: String, host: String, port: Int): Result<PairingQr> {
        // Відкритого HTTP у мережі більше немає — він лишився на самому вузлі.
        return resolveFromUrl(pin, "https://$host:$port/api/v1/pair/resolve/$pin")
    }

    private suspend fun resolveFromUrl(pin: String, url: String): Result<PairingQr> {
        return try {
            val response = http.get(url) {
                timeout {
                    requestTimeoutMillis = 5_000
                }
            }

            if (response.status == HttpStatusCode.OK) {
                val qr: PairingQr = response.body()
                Log.i(TAG, "PIN $pin resolved to pair_id=${qr.pairId}")
                Result.success(qr)
            } else {
                Log.w(TAG, "Server returned ${response.status} for PIN $pin at $url")
                Result.failure(Exception("PIN not found or server error: ${response.status}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reach $url: ${e.message}")
            Result.failure(e)
        }
    }

    companion object {
        private const val LAN_TIMEOUT_MS = 3000L
        private const val TAG = "PairDiscovery"

        /**
         * Wraps an [HttpClientEngine] instance in a factory. Mirrors the same
         * helper in [PairFlowClient] so tests can pass MockEngine directly.
         */
        private fun singletonFactory(engine: HttpClientEngine): HttpClientEngineFactory<*> =
            object : HttpClientEngineFactory<io.ktor.client.engine.HttpClientEngineConfig> {
                override fun create(block: io.ktor.client.engine.HttpClientEngineConfig.() -> Unit) = engine
            }
    }
}
