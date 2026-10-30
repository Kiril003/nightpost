package local.phantom.companion.core.resilience.queue

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.AIIntent

/**
 * Phase 3.B — serialisable form of an [AIIntent.Text] that survives
 * process death and reboot.
 *
 * [historyJson] is the [AIIntent.Text.history] list encoded as a JSON
 * array string. Each element is a JSON object with `role` (string) and
 * `content` (string) fields. Using a nested JSON string rather than a
 * `@Serializable` inner type keeps the payload schema stable even if
 * [AIIntent.Text.Turn] acquires new fields in the future — the drain
 * worker only needs to round-trip role+content.
 *
 * [maxTokens] and [temperature] are NOT persisted. The drain worker
 * replays with the defaults declared on [AIIntent.Text] so that an
 * intent queued at a custom temperature doesn't cause surprises when
 * drained hours later. If that becomes a problem, add the fields here.
 */
@Serializable
data class PendingIntentPayload(
    val prompt: String,
    val systemPrompt: String? = null,
    val historyJson: String? = null,     // serialised AIIntent.Text.history as a JsonArray string
    val contextString: String? = null,
)

// ---------------------------------------------------------------------------
// Round-trip helpers
// ---------------------------------------------------------------------------

/**
 * Convert an [AIIntent.Text] into its persistable [PendingIntentPayload].
 *
 * Each [AIIntent.Text.Turn] becomes `{"role":"USER","content":"…"}` inside
 * the JSON array. The role enum name is used as-is so the decode side can
 * reconstruct the same enum constant without a bespoke codec.
 */
fun AIIntent.Text.toPendingPayload(): PendingIntentPayload {
    val historyJson: String? = if (history.isEmpty()) null else {
        val elements = history.map { turn ->
            buildJsonObject {
                put("role", JsonPrimitive(turn.role.name))
                put("content", JsonPrimitive(turn.content))
            }
        }
        Json.encodeToString(JsonArray(elements))
    }
    return PendingIntentPayload(
        prompt = prompt,
        systemPrompt = systemPrompt,
        historyJson = historyJson,
        contextString = context,
    )
}

/**
 * Reconstruct an [AIIntent.Text] from its persisted [PendingIntentPayload].
 *
 * Missing or malformed history elements are silently skipped — a partial
 * history is always better than a crash on drain.
 */
fun PendingIntentPayload.toAIIntent(): AIIntent.Text {
    val historyJsonLocal = historyJson
    val history: List<AIIntent.Text.Turn> = if (historyJsonLocal.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching {
            val array = Json.parseToJsonElement(historyJsonLocal).jsonArray
            array.mapNotNull { element ->
                runCatching {
                    val obj = element.jsonObject
                    val roleName = obj["role"]?.jsonPrimitive?.content ?: return@runCatching null
                    val content = obj["content"]?.jsonPrimitive?.content ?: return@runCatching null
                    val role = AIIntent.Text.Role.valueOf(roleName)
                    AIIntent.Text.Turn(role = role, content = content)
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }
    return AIIntent.Text(
        prompt = prompt,
        systemPrompt = systemPrompt,
        history = history,
        context = contextString,
    )
}
