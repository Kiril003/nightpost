package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Усі моменти на цьому телефоні: свої й ті, що приїхали. Чиста функція над
 * кадрами [MomentWire]: старша ревізія перемагає, видалення остаточне.
 */
data class MomentBook(val entries: Map<String, Entry> = emptyMap()) {

    data class Entry(
        val moment: MomentWire.Moment,
        /** Кому надіслано — щоб правка й видалення дійшли тим самим людям. */
        val recipients: Set<String> = emptySet(),
        val viewers: Map<String, Long> = emptyMap(),
        val reactions: Map<String, String> = emptyMap(),
    )

    private fun key(author: String, id: String) = "$author/$id"

    fun get(author: String, id: String): Entry? = entries[key(author, id)]

    fun put(entry: Entry): MomentBook = copy(entries = entries + (key(entry.moment.author, entry.moment.id) to entry))

    /** Кадр від [fromId]. [me] — власний id: позначки «бачив» і смайли приймаються лише на свої моменти. */
    fun apply(op: MomentWire.Op, fromId: String, me: String): MomentBook = when (op) {
        is MomentWire.Op.Put -> {
            val m = op.moment
            val cur = get(m.author, m.id)
            when {
                m.author == me -> this
                cur == null -> put(Entry(m))
                cur.moment.deleted || cur.moment.rev >= m.rev -> this
                else -> put(cur.copy(moment = m))
            }
        }
        is MomentWire.Op.Del -> {
            val cur = get(fromId, op.id)
            if (fromId == me || cur?.moment?.deleted == true) {
                this
            } else {
                val base = cur?.moment ?: MomentWire.Moment(
                    id = op.id, author = fromId, media = emptyList(), text = "",
                    audience = MomentWire.Audience(MomentWire.Reach.ONLY_ME), untilMs = 0L,
                    createdAtMs = op.atMs, editedAtMs = op.atMs, rev = op.rev,
                )
                put(Entry(tombstone(base, maxOf(op.rev, base.rev), op.atMs)))
            }
        }
        is MomentWire.Op.Seen -> mine(me, op.id, fromId)?.let { put(it.copy(viewers = it.viewers + (fromId to op.atMs))) } ?: this
        is MomentWire.Op.React -> mine(me, op.id, fromId)?.let {
            put(it.copy(reactions = if (op.emoji.isEmpty()) it.reactions - fromId else it.reactions + (fromId to op.emoji)))
        } ?: this
    }

    private fun mine(me: String, id: String, viewer: String): Entry? =
        get(me, id)?.takeIf { !it.moment.deleted && viewer in it.recipients }

    fun live(nowMs: Long): List<Entry> = entries.values
        .filter { alive(it.moment, nowMs) }
        .sortedByDescending { it.moment.createdAtMs }

    fun highlights(author: String): List<MomentWire.Moment> = entries.values
        .map { it.moment }
        .filter { it.author == author && it.kept && !it.deleted }
        .sortedByDescending { it.createdAtMs }

    /** Що прибрати: минула доба, і давні надгробки. */
    fun expired(nowMs: Long): List<MomentWire.Moment> = entries.values.map { it.moment }.filter { m ->
        if (m.deleted) nowMs - m.editedAtMs > TOMBSTONE_MS else !m.kept && m.untilMs <= nowMs
    }

    fun drop(gone: Collection<MomentWire.Moment>): MomentBook =
        copy(entries = entries - gone.map { key(it.author, it.id) }.toSet())

    fun encode(): String = buildJsonArray {
        entries.values.forEach { e ->
            add(buildJsonObject {
                put("m", MomentWire.toJson(e.moment))
                put("to", buildJsonArray { e.recipients.forEach { add(JsonPrimitive(it)) } })
                put("v", buildJsonObject { e.viewers.forEach { (k, v) -> put(k, v) } })
                put("r", buildJsonObject { e.reactions.forEach { (k, v) -> put(k, v) } })
            })
        }
    }.toString()

    companion object {
        const val SKEW_MS = 60 * 60 * 1000L
        const val TOMBSTONE_MS = 7 * MomentWire.DAY_MS
        private val json = Json { ignoreUnknownKeys = true }

        fun alive(m: MomentWire.Moment, nowMs: Long): Boolean =
            !m.deleted && (m.kept || m.untilMs > nowMs) && m.createdAtMs <= nowMs + SKEW_MS

        fun tombstone(m: MomentWire.Moment, rev: Int, atMs: Long): MomentWire.Moment =
            m.copy(media = emptyList(), text = "", rev = rev, editedAtMs = atMs, deleted = true)

        fun decode(raw: String?): MomentBook {
            val arr = runCatching { json.parseToJsonElement(raw ?: return MomentBook()) as? JsonArray }.getOrNull() ?: return MomentBook()
            var book = MomentBook()
            arr.forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val m = (o["m"] as? JsonObject)?.let(MomentWire::fromJson) ?: return@forEach
                val to = (o["to"] as? JsonArray).orEmpty().mapNotNull { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.toSet()
                val v = (o["v"] as? JsonObject).orEmpty().mapNotNull { (k, x) -> runCatching { x.jsonPrimitive.longOrNull }.getOrNull()?.let { k to it } }.toMap()
                val r = (o["r"] as? JsonObject).orEmpty().mapNotNull { (k, x) -> runCatching { x.jsonPrimitive.contentOrNull }.getOrNull()?.let { k to it } }.toMap()
                book = book.put(Entry(m, to, v, r))
            }
            return book
        }
    }
}
