package local.phantom.companion.feature.stream.diary

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import local.phantom.companion.core.data.diary.DecisionEntry

/**
 * Phase 3-D2 — typed envelopes for `decision_diary` wire frames.
 *
 * Per `docs/COMPANION_CONTRACT.md` §10.15:
 *   - `created`   {id, ts, title, context, status}    bidirectional
 *   - `updated`   {id, status, context?}              bidirectional
 *   - `deleted`   {id}                                bidirectional
 *   - `follow_up` {id, prompt}                        server → client
 */
sealed interface DiaryWireMessage {
    val id: String

    data class Created(val entry: DecisionEntry) : DiaryWireMessage {
        override val id: String get() = entry.id
    }

    data class Updated(
        override val id: String,
        val status: String,
        val context: String?,
    ) : DiaryWireMessage

    data class Deleted(override val id: String) : DiaryWireMessage

    data class FollowUp(override val id: String, val prompt: String) : DiaryWireMessage
}

/**
 * Phase 3-D2 — parses `decision_diary/<verb>` wire frames into typed
 * [DiaryWireMessage] envelopes. Pure object: never touches the
 * DiaryStore, never throws — bad shapes return null and the caller
 * silently drops the event.
 *
 * Lives in :feature-stream rather than :app to keep the typed-flow
 * boundary on the consumer side (PhantomViewModel exposes raw
 * MiscEvents; this parser converts them at the feature edge).
 */
object DiaryWireParser {

    const val CHANNEL: String = "decision_diary"

    /**
     * Convert a raw envelope into a typed message, or null when the
     * channel/verb/payload don't match the spec. Caller should use
     * the result like:
     *
     * ```
     * miscEvents
     *     .filter { it.channel == DiaryWireParser.CHANNEL }
     *     .mapNotNull { DiaryWireParser.tryParse(it.type, it.raw) }
     * ```
     */
    fun tryParse(type: String, raw: JsonObject): DiaryWireMessage? {
        val data = raw["data"] as? JsonObject ?: return null
        return when (type) {
            "created" -> parseCreated(data)
            "updated" -> parseUpdated(data)
            "deleted" -> parseDeleted(data)
            "follow_up" -> parseFollowUp(data)
            else -> null
        }
    }

    private fun parseCreated(data: JsonObject): DiaryWireMessage.Created? {
        val id = data.string("id") ?: return null
        val ts = data.long("ts") ?: return null
        val title = data.string("title") ?: return null
        val context = data.string("context") ?: ""
        val status = data.string("status") ?: return null
        return DiaryWireMessage.Created(
            DecisionEntry(
                id = id,
                createdAt = ts,
                title = title,
                context = context,
                status = status,
            ),
        )
    }

    private fun parseUpdated(data: JsonObject): DiaryWireMessage.Updated? {
        val id = data.string("id") ?: return null
        val status = data.string("status") ?: return null
        val context = data.string("context") // optional per §10.15
        return DiaryWireMessage.Updated(id = id, status = status, context = context)
    }

    private fun parseDeleted(data: JsonObject): DiaryWireMessage.Deleted? {
        val id = data.string("id") ?: return null
        return DiaryWireMessage.Deleted(id)
    }

    private fun parseFollowUp(data: JsonObject): DiaryWireMessage.FollowUp? {
        val id = data.string("id") ?: return null
        val prompt = data.string("prompt") ?: return null
        return DiaryWireMessage.FollowUp(id = id, prompt = prompt)
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { (this[key] as? JsonPrimitive)?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? =
        runCatching { (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() }.getOrNull()
}
