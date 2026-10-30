package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.voice.SttRouteKind
import local.phantom.companion.core.voice.audio.AudioClipDecoder
import local.phantom.companion.data.AppContainer
import java.util.Base64

class WebMessengerTranscribe(
    private val decode: suspend (ByteArray, String) -> ByteArray?,
    private val hear: suspend (ByteArray) -> String?,
    private val hasEngine: () -> Boolean,
    private val maxBytes: Int = 2 * 1024 * 1024,
) {

    fun claims(seg: List<String>): Boolean =
        seg.size == 3 && seg[0] == "messenger" && seg[1] == "voice" && seg[2] == "transcribe"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = try {
        withTimeout(TIMEOUT_MS) {
            if (!claims(seg) || method != "POST") return@withTimeout fail(404, "not_found")
            val obj = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull()
                ?: return@withTimeout fail(400, "bad_body")
            val mime = obj.str("mime").ifEmpty { "audio/webm" }
            val b64 = obj.str("data_b64")
            if (b64.isEmpty()) return@withTimeout fail(400, "bad_body")
            val bytes = try {
                Base64.getDecoder().decode(b64)
            } catch (_: IllegalArgumentException) {
                return@withTimeout fail(400, "bad_body")
            }
            if (bytes.isEmpty()) return@withTimeout fail(400, "bad_body")
            if (bytes.size > maxBytes) return@withTimeout fail(413, "too_big")
            if (!hasEngine()) return@withTimeout fail(503, "no_engine")
            val pcm = decode(bytes, mime) ?: return@withTimeout fail(422, "undecodable")
            val text = hear(pcm)?.trim().orEmpty()
            ok(buildJsonObject { put("text", text) })
        }
    } catch (_: TimeoutCancellationException) {
        fail(504, "slow")
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        fail(500, "failed")
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String, detail: String = ""): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", detail)
    }.toString()

    companion object {
        private const val TIMEOUT_MS = 15_000L

        fun forApp(c: AppContainer): WebMessengerTranscribe = WebMessengerTranscribe(
            decode = AudioClipDecoder::decode,
            hear = { pcm -> withContext(Dispatchers.Default) { c.voiceManager.transcribeClip(pcm) } },
            hasEngine = { c.voiceManager.activeEngine.value != SttRouteKind.None },
        )
    }
}
