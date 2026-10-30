package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.transit.TransitDelayMemory
import local.phantom.companion.core.net.transit.LineLabel
import local.phantom.companion.core.net.transit.TransitousStopClient.BoardDeparture
import local.phantom.companion.core.net.transit.TransitousStopClient.DepartureBoard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class BoardRow(
    val key: String,
    val label: String,
    /** subway | tram | trolleybus | bus | train | ferry | transit */
    val mode: String,
    val colourArgb: Int?,
    val headsign: String?,
    val atMs: Long,
    val clock: String?,
    /** null — рейс уже пішов або далі ніж година. */
    val inMinutes: Int?,
    val past: Boolean,
    val lateMinutes: Int?,
    val usuallyLateMinutes: Int?,
    val cancelled: Boolean,
    val platform: String?,
    val operator: String?,
    val tripId: String?,
    val lineKey: String,
    val zone: String?,
    val stopName: String?,
)

internal object DepartureBoardModel {

    fun rows(board: DepartureBoard, nowMs: Long, memory: TransitDelayMemory? = null): List<BoardRow> {
        val all = board.recent.map { it to true } + board.upcoming.map { it to false }
        return all.mapNotNull { (d, fromPast) -> row(d, fromPast, nowMs, memory) }
            .distinctBy { it.key }
            .sortedBy { it.atMs }
    }

    fun learn(board: DepartureBoard, memory: TransitDelayMemory) {
        (board.recent + board.upcoming).forEach { d ->
            if (!d.realTime || d.cancelled) return@forEach
            val at = epochMs(d.atIso) ?: return@forEach
            val planned = epochMs(d.scheduledIso) ?: return@forEach
            memory.observe(lineKeyOf(d), d.tripId, planned, ((at - planned) / 1000).toInt(), zoneOf(d) ?: ZoneId.systemDefault())
        }
    }

    fun lineKeyOf(d: BoardDeparture): String = TransitDelayMemory.lineKey(d.label, modeOf(d.mode), d.operator)

    fun modeOf(raw: String): String = when (raw.uppercase()) {
        "SUBWAY", "METRO" -> "subway"
        "TRAM", "LIGHT_RAIL" -> "tram"
        "TROLLEYBUS" -> "trolleybus"
        "BUS", "COACH" -> "bus"
        "RAIL", "REGIONAL_RAIL", "REGIONAL_FAST_RAIL", "LONG_DISTANCE", "HIGHSPEED_RAIL",
        "NIGHT_RAIL", "SUBURBAN", "SUBURBAN_RAIL" -> "train"
        "FERRY" -> "ferry"
        "FUNICULAR", "AERIAL_LIFT" -> "funicular"
        else -> "transit"
    }

    /** Інші рейси тієї ж лінії в тому ж напрямку на цьому табло. */
    fun siblings(rows: List<BoardRow>, of: BoardRow): Pair<List<BoardRow>, List<BoardRow>> {
        val same = rows.filter { it.lineKey == of.lineKey && it.headsign == of.headsign && it.key != of.key }
        return same.filter { it.atMs < of.atMs } to same.filter { it.atMs > of.atMs }
    }

    private fun row(d: BoardDeparture, fromPast: Boolean, nowMs: Long, memory: TransitDelayMemory?): BoardRow? {
        val at = epochMs(d.atIso) ?: return null
        val zone = zoneOf(d)
        val planned = epochMs(d.scheduledIso)
        val late = if (d.realTime && planned != null) ((at - planned) / 60_000).toInt().takeIf { it >= 1 } else null
        val past = fromPast || at < nowMs - 30_000
        val mins = ((at - nowMs) / 60_000).toInt()
        val lineKey = lineKeyOf(d)
        val usual = if (!d.realTime && !past) {
            memory?.typicalDelaySec(lineKey, at, zone ?: ZoneId.systemDefault())?.let { it / 60 }?.takeIf { it >= 2 }
        } else null
        return BoardRow(
            key = d.tripId ?: "${d.label}|${d.atIso}|${d.headsign}",
            label = d.label.takeUnless { LineLabel.looksLikeId(it) }.orEmpty(),
            mode = modeOf(d.mode),
            colourArgb = d.colour?.removePrefix("#")?.takeIf { it.length == 6 }
                ?.let { runCatching { it.toInt(16) or 0xFF000000.toInt() }.getOrNull() },
            headsign = d.headsign,
            atMs = at,
            clock = zone?.let { Instant.ofEpochMilli(at).atZone(it).format(CLOCK) },
            inMinutes = if (!past && mins in 0..59) mins else null,
            past = past,
            lateMinutes = late,
            usuallyLateMinutes = usual,
            cancelled = d.cancelled,
            platform = d.platform?.takeUnless { LineLabel.looksLikeId(it) },
            operator = d.operator,
            tripId = d.tripId,
            lineKey = lineKey,
            zone = d.timeZone,
            stopName = d.stopName,
        )
    }

    private fun zoneOf(d: BoardDeparture): ZoneId? = d.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun epochMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
