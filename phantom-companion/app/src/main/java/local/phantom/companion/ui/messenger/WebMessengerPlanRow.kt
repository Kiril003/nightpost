package local.phantom.companion.ui.messenger

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Зведення групи плану (`plan` у рядку переліку розмов): коли зустріч кінчається,
 * хто її лишив, чи скасована і чи перенесена — щоб веб міг сховати групу зі
 * списку навіть тоді, коли стрічка повідомлень ще не завантажена.
 */
internal object WebMessengerPlanRow {
    const val TTL_MS = 24 * 3_600_000L
    private const val MAX_SLOTS = 3

    data class Plan(
        val meetupId: String,
        val venue: String?,
        val organizerId: String,
        val atMs: Long?,
        val expiresAtMs: Long?,
        val keptBy: List<String>,
        val cancelled: Boolean,
    ) {
        enum class State { PERMANENT, ACTIVE, KEPT, CANCELLED, EXPIRED }

        fun state(nowMs: Long): State = when {
            keptBy.isNotEmpty() -> State.KEPT
            expiresAtMs == null -> State.PERMANENT
            cancelled -> State.CANCELLED
            nowMs >= expiresAtMs -> State.EXPIRED
            else -> State.ACTIVE
        }
    }

    private data class ParsedCard(
        val id: String,
        val venue: String?,
        val atMs: Long,
        val expiresAtMs: Long?,
        val slots: List<Long>?,
    )

    fun of(
        lines: List<PeerMessage>,
        members: Collection<String>,
        folded: () -> Map<String, Map<String, List<String>>>,
    ): Plan? {
        val real = lines.filter { WebMessengerGroups.actOf(it.body) == null }
        val firstIdx = real.indexOfFirst { meetupCard(it) != null }
        if (firstIdx < 0) return null
        if (firstIdx != 0 && !(firstIdx == 1 && real[0].fromId == real[1].fromId)) return null

        var newest: Pair<PeerMessage, ParsedCard>? = null
        for (line in real) {
            if (line.deletedAtMs != 0L) continue
            val parsed = parseCard(line) ?: continue
            if (newest == null || line.sentAtMs >= newest.first.sentAtMs) {
                newest = line to parsed
            }
        }
        val (cardLine, card) = newest ?: return null
        val organizerId = cardLine.fromId
        val marks = WebMessengerCardMarks.marksOf(folded(), cardLine, members)
        val keptBy = marks["keep:1"].orEmpty()
        val cancelled = organizerId in marks["cancel:1"].orEmpty()
        val moved = marks.entries
            .asSequence()
            .filter { (k, authors) -> k.startsWith("at:") && organizerId in authors }
            .mapNotNull { (k, _) -> k.removePrefix("at:").toLongOrNull() }
            .maxOrNull()
            ?.times(60_000L)
        val atMs = moved ?: if (card.slots != null) {
            card.slots.indices
                .firstOrNull { k -> organizerId in marks["lock:$k"].orEmpty() }
                ?.let { card.slots[it] }
        } else {
            card.atMs
        }
        val expiresAtMs = when {
            card.expiresAtMs == null -> null
            moved != null -> moved + TTL_MS
            else -> card.expiresAtMs
        }
        return Plan(
            meetupId = card.id,
            venue = card.venue,
            organizerId = organizerId,
            atMs = atMs,
            expiresAtMs = expiresAtMs,
            keptBy = keptBy,
            cancelled = cancelled,
        )
    }

    fun json(p: Plan): JsonObject = JsonObject(
        mapOf(
            "meetup_id" to JsonPrimitive(p.meetupId),
            "venue" to (p.venue?.let(::JsonPrimitive) ?: JsonNull),
            "organizer" to JsonPrimitive(p.organizerId),
            "at" to (p.atMs?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull),
            "expires_at" to (p.expiresAtMs?.let { JsonPrimitive(Instant.ofEpochMilli(it).toString()) } ?: JsonNull),
            "kept_by" to JsonArray(p.keptBy.map(::JsonPrimitive)),
            "cancelled" to JsonPrimitive(p.cancelled),
        ),
    )

    private fun meetupCard(line: PeerMessage): PeerCard.Body? {
        val said = WebMessengerGroups.said(line.body) ?: return null
        return PeerCard.decode(said)?.takeIf { it.what == PeerCard.Card.MEETUP }
    }

    private fun parseCard(line: PeerMessage): ParsedCard? {
        val body = meetupCard(line) ?: return null
        val obj = runCatching { Json.parseToJsonElement(body.payload) as? JsonObject }.getOrNull() ?: return null
        val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() } ?: return null
        val atMs = (obj["at"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: return null
        val expiresEl = obj["expiresAt"]
        val expiresAtMs = when {
            expiresEl == null || expiresEl is JsonNull -> null
            expiresEl is JsonPrimitive && !expiresEl.isString -> expiresEl.longOrNull ?: return null
            else -> return null
        }
        val venue = ((obj["venue"] as? JsonObject)?.get("name") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val slots = (obj["slots"] as? JsonArray)?.let { arr ->
            val nums = arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.longOrNull }
            nums.takeIf { it.size == arr.size && it.size in 2..MAX_SLOTS && it.first() == atMs }
        }
        return ParsedCard(id = id, venue = venue, atMs = atMs, expiresAtMs = expiresAtMs, slots = slots)
    }
}
