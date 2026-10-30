package local.phantom.companion.self

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.BuildConfig
import local.phantom.companion.core.ai.route.BrainRequestSigner
import local.phantom.companion.core.ai.route.NightpostBrainRoute
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.DeviceBrainSigner
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class BrainLiveToken(
    base: String,
    private val signer: BrainRequestSigner,
    private val allowed: () -> Boolean,
    private val post: suspend (url: String, headers: Map<String, String>, body: String) -> Pair<Int, String>,
) {
    private val root = base.trim().trimEnd('/')

    suspend fun fetch(): Pair<Int, String>? {
        if (root.isEmpty() || !allowed()) return null
        val body = "{}"
        val pub = signer.publicKeyB64
        val nonce = signer.newNonce()
        val ts = signer.nowMs()
        val bodyHash = NightpostBrainRoute.sha256Hex(body.toByteArray(Charsets.UTF_8))
        val msg = "${NightpostBrainRoute.BRAIN_SIG_PREFIX}|$pub|$nonce|$ts|live|$bodyHash".toByteArray(Charsets.UTF_8)
        val sig = signer.sign(msg)
        val headers = mapOf(
            NightpostBrainRoute.KEY_HEADER to pub,
            NightpostBrainRoute.TS_HEADER to ts.toString(),
            NightpostBrainRoute.NONCE_HEADER to nonce,
            NightpostBrainRoute.SIG_HEADER to sig,
        )
        return post("$root/api/brain/live-token", headers, body)
    }

    companion object {
        private const val CALL_TIMEOUT_MS = 8_000L

        fun forApp(c: AppContainer, http: OkHttpClient): BrainLiveToken {
            val client = http.newBuilder()
                .callTimeout(CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
            return BrainLiveToken(
                base = BuildConfig.LICENSE_BASE_URL,
                signer = DeviceBrainSigner(c.identity),
                allowed = {
                    NightpostBrainRoute.mayAsk(
                        c.brainPrefs.routing.value,
                        c.aiKeyStore.geminiKey.value,
                        c.ownKeyDead(),
                    )
                },
                post = { url, headers, body ->
                    withContext(Dispatchers.IO) {
                        val req = Request.Builder()
                            .url(url)
                            .apply { headers.forEach { (k, v) -> header(k, v) } }
                            .post(body.toRequestBody("application/json".toMediaType()))
                            .build()
                        client.newCall(req).execute().use { it.code to (it.body?.string().orEmpty()) }
                    }
                },
            )
        }
    }
}
