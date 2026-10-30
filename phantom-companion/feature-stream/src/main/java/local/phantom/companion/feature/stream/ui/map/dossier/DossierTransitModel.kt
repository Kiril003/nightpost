package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.net.map.PlaceDossierClient
import kotlin.math.ceil

/**
 * Full Transit Stop & Line Domain Models for Dossier & Interactive Tracking.
 */
internal data class DossierStopSummary(
    val id: String,
    val name: String,
    val isCurrent: Boolean = false,
    val travelMinutesFromCurrent: Int = 0,
)

internal data class TransitDepartureCountdown(
    /** null — годинника стопу ми не знаємо; «хв» від цього не залежить. */
    val timeClock: String?,
    val inMinutes: Int,
    val isImminent: Boolean = inMinutes <= 3,
    /**
     * true — хвилина порахована з інтервальної моделі, false — прочитана
     * з опублікованого розкладу міста.
     *
     * Без цього поля картка не може не брехати: «12:08» з розкладу і
     * «12:08», яке модель щойно вигадала, виглядають однаково, а це два
     * різні твердження про світ. Підпис над рядком іде саме звідси.
     */
    val modelled: Boolean = true,
)

internal data class DossierTransitLine(
    val ref: String,
    /** bus | trolleybus | tram | subway | train | share_taxi */
    val mode: String,
    val direction: String?,
    val headwayMinutes: Int?,
    /** null — джерело не сказало; false стверджує «не курсує». */
    val running: Boolean? = null,
    val colourArgb: Int? = null,
    val relationId: Long? = null,
    val shape: String? = null,
    val stops: List<DossierStopSummary> = emptyList(),
    val departures: List<TransitDepartureCountdown> = emptyList(),
    val operatorName: String? = null,
)

internal data class DossierInterchange(
    val station: String,
    val lineRef: String,
    val walkMinutes: Int,
)

internal data class DossierTransit(
    val lines: List<DossierTransitLine>,
    val interchanges: List<DossierInterchange> = emptyList(),
    val serviceStartSec: Int? = null,
    val serviceEndSec: Int? = null,
    val bothDirections: Boolean = false,
) {
    val isEmpty: Boolean get() = lines.isEmpty() && interchanges.isEmpty()
}

internal interface DossierTransitSource {
    suspend fun transitAt(lat: Double, lon: Double, radiusM: Int): DossierTransit?
}

internal fun PlaceDossierClient.RouteLineRef.asTransitLine(): DossierTransitLine = DossierTransitLine(
    ref = ref ?: name ?: "",
    mode = mode,
    direction = listOfNotNull(from, to).takeIf { it.isNotEmpty() }?.joinToString(" → "),
    headwayMinutes = null,
    colourArgb = colour
        ?.removePrefix("#")
        ?.takeIf { it.length == 6 }
        ?.let { runCatching { it.toInt(16) or ALPHA_OPAQUE }.getOrNull() },
    relationId = relationId,
)

internal fun mergeTransitLines(
    local: List<DossierTransitLine>,
    live: List<DossierTransitLine>,
): List<DossierTransitLine> {
    val out = LinkedHashMap<String, DossierTransitLine>()
    local.forEach { out[it.key()] = it }
    live.forEach { line ->
        val existing = out[line.key()]
        out[line.key()] = when {
            existing == null -> line
            existing.direction == null -> existing.copy(
                direction = line.direction,
                relationId = existing.relationId ?: line.relationId,
                stops = if (existing.stops.isNotEmpty()) existing.stops else line.stops,
                departures = if (existing.departures.isNotEmpty()) existing.departures else line.departures,
            )
            else -> existing.copy(
                relationId = existing.relationId ?: line.relationId,
                stops = if (existing.stops.isNotEmpty()) existing.stops else line.stops,
                departures = if (existing.departures.isNotEmpty()) existing.departures else line.departures,
            )
        }
    }
    return out.values.sortedWith(compareBy({ MODE_ORDER.indexOf(it.mode) }, { it.ref.padStart(4, '0') }))
}

private fun DossierTransitLine.key(): String = "${ref.trim().lowercase()}|$mode"

internal fun walkMinutesUp(seconds: Int): Int = maxOf(1, ceil(seconds / 60.0).toInt())

internal fun headwayMinutesOf(seconds: Int): Int = maxOf(1, (seconds + 30) / 60)

/**
 * СКІЛЬКИ НАСПРАВДІ ЧЕКАТИ, коли відома лише частота, а не розклад.
 *
 * Половина інтервалу — зручна й НЕПРАВИЛЬНА відповідь (так рахує OsmAnd).
 * Для людини, що приходить на зупинку в довільну мить, середнє чекання за
 * теорією відновлення дорівнює E[H²] / 2E[H], тобто (H/2)·(1 + CV²), де
 * CV — коефіцієнт варіації інтервалу. Половина виходить ЛИШЕ при CV = 0,
 * тобто коли машини йдуть як метроном.
 *
 * Український наземний транспорт диспетчерського вирівнювання не має:
 * автобуси збиваються в пачки, і CV ≈ 1 — звідки середнє чекання дорівнює
 * ПОВНОМУ інтервалу, а не половині. Заниження тут не нейтральне: воно
 * систематично обіцяє людині приїхати вчасно і систематично її запізнює.
 *
 * @param cv коефіцієнт варіації. 1.0 — чесна підлога для вуличного руху;
 *   метро з витриманим тактом заслуговує на менший, коли його виміряють.
 */
internal fun expectedWaitSeconds(headwaySeconds: Int, cv: Double = 1.0): Int =
    ((headwaySeconds / 2.0) * (1.0 + cv * cv)).toInt()

internal fun expectedWaitMinutes(headwaySeconds: Int, cv: Double = 1.0): Int =
    maxOf(1, (expectedWaitSeconds(headwaySeconds, cv) + 30) / 60)

internal fun fmtClockSec(sec: Int): String {
    val minuteOfDay = (sec / 60) % (24 * 60)
    return "%02d:%02d".format(minuteOfDay / 60, minuteOfDay % 60)
}

private val MODE_ORDER = listOf("subway", "tram", "trolleybus", "bus", "train", "share_taxi")

private const val ALPHA_OPAQUE = 0xFF000000.toInt()
