package local.phantom.companion.core.data.diary

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Phase 3-D3 — encode the verb-specific JSON body for outgoing
 * `decision_diary/<verb>` envelopes per CONTRACT §10.15.
 *
 * Pure object so the encoding is unit-pinnable without a transport.
 * Output strings are the wire envelope's `data` field, ready to be
 * wrapped by the transport into `{channel, type, data, ts}` and sent.
 *
 * Verb shapes:
 *   created   {id, ts, title, context, status}
 *   updated   {id, status, context?}
 *   deleted   {id}
 */
object DiaryPublishCodec {

    const val CHANNEL: String = "decision_diary"
    const val VERB_CREATED: String = "created"
    const val VERB_UPDATED: String = "updated"
    const val VERB_DELETED: String = "deleted"

    private val json = Json { encodeDefaults = false; explicitNulls = false }

    /**
     * Build the verb-specific `data` JSON for [entry].
     * - CREATED: full payload
     * - UPDATED: id + status, plus context only if non-blank (per
     *   §10.15 the field is optional; null/blank elides cleanly)
     * - DELETED: id only
     *
     * Throws IllegalArgumentException on unknown verb so an
     * upstream typo crashes loudly instead of silently writing
     * the wrong shape.
     */
    fun encodeData(entry: DecisionEntry, verb: String): String {
        val payload: JsonObject = when (verb) {
            VERB_CREATED -> buildJsonObject {
                put("id", entry.id)
                put("ts", entry.createdAt)
                put("title", entry.title)
                put("context", entry.context)
                put("status", entry.status)
            }
            VERB_UPDATED -> buildJsonObject {
                put("id", entry.id)
                put("status", entry.status)
                if (entry.context.isNotBlank()) {
                    put("context", entry.context)
                }
            }
            VERB_DELETED -> buildJsonObject {
                put("id", entry.id)
            }
            else -> throw IllegalArgumentException("Unknown decision_diary verb: '$verb'")
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    /**
     * Build the full WS envelope per §3 standard:
     * `{channel, type, data, ts}`. The transport just JSON-encodes
     * the result and writes it to the WebSocket.
     */
    fun encodeEnvelope(verb: String, dataJson: String, nowMs: Long): String {
        val data = json.parseToJsonElement(dataJson) as JsonObject
        val envelope = buildJsonObject {
            put("channel", CHANNEL)
            put("type", verb)
            put("data", data)
            put("ts", JsonPrimitive(nowMs))
        }
        return json.encodeToString(JsonObject.serializer(), envelope)
    }
}
