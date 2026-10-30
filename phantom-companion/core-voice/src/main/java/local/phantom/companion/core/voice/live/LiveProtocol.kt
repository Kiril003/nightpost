package local.phantom.companion.core.voice.live

import java.net.URLEncoder
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

data class ToolCall(val id: String, val name: String, val args: JsonObject)

data class ToolReply(val id: String, val name: String, val response: JsonObject)

/** Що приходить із сокета Gemini Live — уже розібране. */
sealed interface LiveEvent {
    data object SetupComplete : LiveEvent

    /** 24 кГц, 16 біт, LE, моно. */
    class Audio(val pcm: ByteArray) : LiveEvent

    /** Розпізнане з мови людини (шматок). */
    data class HeardUser(val text: String) : LiveEvent

    /** Те, що PHANTOM каже вголос (шматок). */
    data class SaidModel(val text: String) : LiveEvent

    /** Сервер почув людину посеред відповіді — відтворення гаситься негайно. */
    data object Interrupted : LiveEvent

    data object TurnComplete : LiveEvent

    data class ToolCalls(val calls: List<ToolCall>) : LiveEvent

    data class ToolCancelled(val ids: List<String>) : LiveEvent

    data class GoAway(val timeLeftMs: Long?) : LiveEvent

    data class Resumable(val handle: String) : LiveEvent
}

/**
 * Дріт Gemini Live (BidiGenerateContent): setup, realtimeInput, toolResponse —
 * туди; serverContent, toolCall, goAway, sessionResumptionUpdate — звідти.
 */
object LiveProtocol {
    const val IN_RATE = 16_000
    const val OUT_RATE = 24_000

    /** Ефемерний токен приймає лише «обмежена» точка входу v1alpha. */
    const val WS_URL =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContentConstrained"

    fun url(token: String): String = "$WS_URL?access_token=" + URLEncoder.encode(token, "UTF-8")

    fun setup(model: String, persona: String, tools: JsonArray, resumeHandle: String?): String =
        buildJsonObject {
            putJsonObject("setup") {
                put("model", if (model.startsWith("models/")) model else "models/$model")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add(JsonPrimitive("AUDIO")) }
                    putJsonObject("speechConfig") { put("languageCode", "uk-UA") }
                }
                putJsonObject("systemInstruction") {
                    putJsonArray("parts") { add(buildJsonObject { put("text", persona) }) }
                }
                putJsonArray("tools") { add(buildJsonObject { put("functionDeclarations", tools) }) }
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
                putJsonObject("realtimeInputConfig") {
                    putJsonObject("automaticActivityDetection") {
                        // Бюджет 800 мс від кінця мови до першого звуку: кінець репліки
                        // сервер вирішує сам, і кожна мілісекунда тиші тут — у бюджеті.
                        put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        put("silenceDurationMs", SILENCE_MS)
                        put("prefixPaddingMs", 120)
                    }
                    put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS")
                }
                putJsonObject("sessionResumption") { resumeHandle?.let { put("handle", it) } }
                putJsonObject("contextWindowCompression") { putJsonObject("slidingWindow") {} }
            }
        }.toString()

    fun audio(pcm16le: ByteArray, offset: Int = 0, length: Int = pcm16le.size): String {
        val b64 = Base64.getEncoder().encodeToString(
            if (offset == 0 && length == pcm16le.size) pcm16le else pcm16le.copyOfRange(offset, offset + length),
        )
        return """{"realtimeInput":{"audio":{"mimeType":"audio/pcm;rate=$IN_RATE","data":"$b64"}}}"""
    }

    fun toolResponse(replies: List<ToolReply>): String = buildJsonObject {
        putJsonObject("toolResponse") {
            putJsonArray("functionResponses") {
                replies.forEach { r ->
                    add(
                        buildJsonObject {
                            put("id", r.id)
                            put("name", r.name)
                            put("response", r.response)
                        },
                    )
                }
            }
        }
    }.toString()

    /** Текст від імені людини (кнопка «Так» на екрані дзвінка). */
    fun clientText(text: String): String = buildJsonObject {
        putJsonObject("clientContent") {
            putJsonArray("turns") {
                add(
                    buildJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { add(buildJsonObject { put("text", text) }) }
                    },
                )
            }
            put("turnComplete", true)
        }
    }.toString()

    fun parse(frame: String): List<LiveEvent> {
        val root = runCatching { LiveJson.parseToJsonElement(frame).jsonObject }.getOrNull() ?: return emptyList()
        val out = mutableListOf<LiveEvent>()
        if ("setupComplete" in root) out += LiveEvent.SetupComplete
        root.obj("serverContent")?.let { sc ->
            sc.obj("inputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LiveEvent.HeardUser(it) }
            if (sc.bool("interrupted")) out += LiveEvent.Interrupted
            sc.obj("modelTurn")?.arr("parts")?.forEach { part ->
                val inline = (part as? JsonObject)?.obj("inlineData") ?: return@forEach
                val mime = inline.str("mimeType").orEmpty()
                val data = inline.str("data") ?: return@forEach
                if (mime.startsWith("audio/pcm")) {
                    runCatching { Base64.getDecoder().decode(data) }.getOrNull()?.let { out += LiveEvent.Audio(it) }
                }
            }
            sc.obj("outputTranscription")?.str("text")?.takeIf { it.isNotEmpty() }?.let { out += LiveEvent.SaidModel(it) }
            if (sc.bool("turnComplete")) out += LiveEvent.TurnComplete
        }
        root.obj("toolCall")?.arr("functionCalls")?.let { calls ->
            val parsed = calls.mapNotNull { c ->
                val o = c as? JsonObject ?: return@mapNotNull null
                ToolCall(
                    id = o.str("id").orEmpty(),
                    name = o.str("name") ?: return@mapNotNull null,
                    args = o.obj("args") ?: JsonObject(emptyMap()),
                )
            }
            if (parsed.isNotEmpty()) out += LiveEvent.ToolCalls(parsed)
        }
        root.obj("toolCallCancellation")?.arr("ids")?.let { ids ->
            out += LiveEvent.ToolCancelled(ids.mapNotNull { (it as? JsonPrimitive)?.contentOrNull })
        }
        root.obj("goAway")?.let { out += LiveEvent.GoAway(duration(it.str("timeLeft"))) }
        root.obj("sessionResumptionUpdate")?.let { u ->
            val handle = u.str("newHandle")
            if (!handle.isNullOrBlank() && (u["resumable"] == null || u.bool("resumable"))) {
                out += LiveEvent.Resumable(handle)
            }
        }
        return out
    }

    /** «12s», «1.5s» → мс. */
    internal fun duration(s: String?): Long? =
        s?.removeSuffix("s")?.toDoubleOrNull()?.let { (it * 1000).toLong() }

    const val SILENCE_MS = 300

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
}
