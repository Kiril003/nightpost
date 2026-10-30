package local.phantom.companion.feature.stream.ui.map.dossier

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.transit.gtfs.GtfsSchedule
import local.phantom.companion.core.data.transit.gtfs.ScheduledLine
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import java.util.Calendar

/**
 * Local Transit Schedule, Stop Sequence & Live Departures Engine.
 *
 * ДВА ЯРУСИ, І ВОНИ НЕ ЗЛИВАЮТЬСЯ:
 *  1. опублікований розклад міста (GTFS static) — справжні хвилини;
 *  2. інтервальна модель з OSM — частота, і ЛИШЕ частота.
 *
 * До 13.09.2026 існував тільки другий, і він видавав годинник: брав
 * `serviceStartSec`, ділив на headway і друкував «12:08» так, ніби це
 * рядок розкладу. Саме на це поскаржився власник. Тепер годинник має
 * право писати тільки ярус 1; ярус 2 каже «раз на N хвилин» і мовчить
 * про те, чого не знає.
 */
internal class LocalTransitSource(
    private val surface: () -> SurfaceFeed?,
    private val metro: () -> MetroFeed?,
    /**
     * Опублікований розклад, якщо він уже приїхав і саме на сьогодні.
     * Null — розкладу нема, і модель лишається НАЗВАНОЮ моделлю.
     */
    private val schedule: () -> GtfsSchedule? = { null },
    private val nowSecOfDay: () -> Int = ::secondOfDayNow,
) : DossierTransitSource {

    override suspend fun transitAt(lat: Double, lon: Double, radiusM: Int): DossierTransit? =
        withContext(Dispatchers.Default) {
            val now = nowSecOfDay()
            val fromSurface = surfaceAt(lat, lon, radiusM, now)
            val fromMetro = metroAt(lat, lon, now)
            val lines = fromMetro.first + fromSurface.first
            if (lines.isEmpty() && fromMetro.second.isEmpty()) {
                null
            } else {
                DossierTransit(
                    lines = lines,
                    interchanges = fromMetro.second,
                    serviceStartSec = fromMetro.third?.first ?: surface()?.doc?.serviceStartSec,
                    serviceEndSec = fromMetro.third?.second ?: surface()?.doc?.serviceEndSec,
                    bothDirections = fromSurface.second,
                )
            }
        }

    private fun surfaceAt(
        lat: Double,
        lon: Double,
        radiusM: Int,
        now: Int,
    ): Pair<List<DossierTransitLine>, Boolean> {
        // Ярус 1 спершу: якщо місто опублікувало розклад на СЬОГОДНІ,
        // хвилини беруться звідти. Протухлий знімок сюди не потрапляє —
        // за це відповідає той, хто віддає [schedule].
        val real = schedule()
            ?.linesNear(lat, lon, radiusM, now)
            ?.associateBy { it.routeLabel.trim().lowercase() to modeWord(it.mode) }
            .orEmpty()

        val feed = surface()
        if (feed == null) {
            // Моделі нема взагалі — але розклад може бути, і тоді він
            // говорить сам, без жодної геометрії з OSM.
            val only = real.values.map { it.asDossierLine(now) }
            return only to (only.map { it.ref }.distinct().size > 1)
        }

        val running = now in feed.doc.serviceStartSec..feed.doc.serviceEndSec
        val matchedStops = HashSet<String>()
        val lines = ArrayList<DossierTransitLine>()
        val consumed = HashSet<Pair<String, String>>()

        for (line in feed.doc.lines) {
            val hit = line.stops.firstOrNull {
                haversineMeters(lat, lon, it.lat, it.lon) <= radiusM
            } ?: continue
            matchedStops += hit.id
            val currentIdx = line.stops.indexOfFirst { it.id == hit.id }.coerceAtLeast(0)
            val headwaySec = feed.headwayAt(line.headway, now)
            val headwayMin = headwayMinutesOf(headwaySec)

            val key = line.ref.trim().lowercase() to line.mode
            val scheduled = real[key]
            if (scheduled != null) consumed += key

            val departures = when {
                // Справжні хвилини з розкладу міста.
                scheduled != null -> scheduled.departureSecs.map { sec ->
                    val inMin = maxOf(1, ((sec - now) + 30) / 60)
                    TransitDepartureCountdown(
                        timeClock = fmtClockSec(sec),
                        inMinutes = inMin,
                        isImminent = inMin <= 3,
                        modelled = false,
                    )
                }

                // Розкладу на цей маршрут нема. Модель знає ЧАСТОТУ і не
                // знає годинника — тож годинника й не пише. Одне число,
                // і воно чесне: очікування при CV ≈ 1 дорівнює повному
                // інтервалу, а не його половині (див. expectedWaitSeconds).
                running && headwaySec > 0 -> listOf(
                    TransitDepartureCountdown(
                        timeClock = null,
                        inMinutes = expectedWaitMinutes(headwaySec),
                        isImminent = false,
                        modelled = true,
                    ),
                )

                else -> emptyList()
            }

            // Calculate full stop sequence and travel minutes
            val stops = line.stops.mapIndexed { idx, s ->
                val travelMin = if (idx > currentIdx && idx <= line.hopSeconds.size) {
                    val sec = line.hopSeconds.subList(currentIdx, idx).sum()
                    maxOf(1, (sec + 30) / 60)
                } else 0
                DossierStopSummary(
                    id = s.id,
                    name = s.name,
                    isCurrent = idx == currentIdx,
                    travelMinutesFromCurrent = travelMin,
                )
            }

            lines += DossierTransitLine(
                ref = line.ref,
                mode = line.mode,
                direction = line.name.substringAfter(": ", "").trim().takeIf { it.isNotEmpty() },
                // Розклад знає СВОЇ проміжки; модельна «частота» поруч із
                // ним була б другим, суперечливим твердженням.
                headwayMinutes = if (scheduled != null) null else headwayMin,
                running = if (scheduled != null) scheduled.departureSecs.isNotEmpty() else running,
                colourArgb = scheduled?.colour?.let { it or ALPHA_OPAQUE },
                shape = line.shape,
                stops = stops,
                departures = departures,
            )
        }

        // Маршрути, які місто опублікувало, а модель з OSM не знає. Без
        // них розклад умів би лише дописувати хвилини до чужого переліку,
        // і половина справжньої мережі лишалась би невидимою.
        for ((key, scheduled) in real) {
            if (key in consumed) continue
            lines += scheduled.asDossierLine(now)
            matchedStops += scheduled.boardStopId
        }

        return lines to (matchedStops.size > 1)
    }

    /** Лінія, яку знає ЛИШЕ розклад: геометрії нема, часи справжні. */
    private fun ScheduledLine.asDossierLine(now: Int): DossierTransitLine = DossierTransitLine(
        ref = routeLabel,
        mode = modeWord(mode),
        direction = terminusName?.let { "→ $it" },
        // Розклад не оголошує частоти — і не вигадує її.
        headwayMinutes = null,
        running = departureSecs.isNotEmpty(),
        colourArgb = colour?.let { it or ALPHA_OPAQUE },
        stops = stops.mapIndexed { idx, s ->
            DossierStopSummary(
                id = s.id,
                name = s.name,
                isCurrent = idx == boardIndex,
                travelMinutesFromCurrent =
                if (idx > boardIndex) maxOf(1, (s.secondsFromBoard + 30) / 60) else 0,
            )
        },
        departures = departureSecs.map { sec ->
            val inMin = maxOf(1, ((sec - now) + 30) / 60)
            TransitDepartureCountdown(
                timeClock = fmtClockSec(sec),
                inMinutes = inMin,
                isImminent = inMin <= 3,
                modelled = false,
            )
        },
    )

    /** Словник видів у тій самій мові, якою говорить [SurfaceFeed] і картка. */
    private fun modeWord(mode: TransitMode): String = when (mode) {
        TransitMode.TRAM -> "tram"
        TransitMode.TROLLEYBUS -> "trolleybus"
        TransitMode.METRO -> "subway"
        TransitMode.RAIL -> "train"
        else -> "bus"
    }

    private fun metroAt(
        lat: Double,
        lon: Double,
        now: Int,
    ): Triple<List<DossierTransitLine>, List<DossierInterchange>, Pair<Int, Int>?> {
        val feed = metro() ?: return Triple(emptyList(), emptyList(), null)
        var bestId: String? = null
        var bestDistance = Double.MAX_VALUE
        for (line in feed.doc.lines) {
            for (station in line.stations) {
                val d = haversineMeters(lat, lon, station.lat, station.lon)
                if (d <= METRO_REACH_M && d < bestDistance) {
                    bestDistance = d
                    bestId = station.id
                }
            }
        }
        val stationId = bestId ?: return Triple(emptyList(), emptyList(), null)
        val running = now in feed.doc.serviceStartSec..feed.doc.serviceEndSec
        val headwaySec = feed.headwayAt(now)
        val headwayMin = headwayMinutesOf(headwaySec)

        // Метро КП «Київпастранс» у GTFS не публікує (route_type 1 в архіві
        // нема — переміряно 13.09.2026), тож тут лишається модель. Але
        // модель не пише годинника: вона знає такт, не розклад.
        //
        // CV = 0,5, а не 1: метро ЄДИНЕ тут має диспетчерське вирівнювання,
        // і рівняти його до автобусної пачки означало б удвічі завищити
        // чекання. Це припущення, не вимір, — і воно тут назване.
        val departures = if (running && headwaySec > 0) {
            listOf(
                TransitDepartureCountdown(
                    timeClock = null,
                    inMinutes = expectedWaitMinutes(headwaySec, cv = 0.5),
                    isImminent = false,
                    modelled = true,
                ),
            )
        } else emptyList()

        val lines = feed.doc.lines
            .filter { line -> line.stations.any { it.id == stationId } }
            .map { line ->
                val currentIdx = line.stations.indexOfFirst { it.id == stationId }.coerceAtLeast(0)
                val stops = line.stations.mapIndexed { idx, st ->
                    val travelMin = if (idx > currentIdx && idx <= line.hopSeconds.size) {
                        val sec = line.hopSeconds.subList(currentIdx, idx).sum()
                        maxOf(1, (sec + 30) / 60)
                    } else 0
                    DossierStopSummary(
                        id = st.id,
                        name = st.name,
                        isCurrent = idx == currentIdx,
                        travelMinutesFromCurrent = travelMin,
                    )
                }

                DossierTransitLine(
                    ref = line.ref,
                    mode = "subway",
                    direction = line.stations.firstOrNull()?.name?.let { first ->
                        line.stations.lastOrNull()?.name?.let { last -> "$first ↔ $last" }
                    },
                    headwayMinutes = headwayMin,
                    running = running,
                    colourArgb = line.colour
                        ?.removePrefix("#")
                        ?.takeIf { it.length == 6 }
                        ?.let { runCatching { it.toInt(16) or ALPHA_OPAQUE }.getOrNull() },
                    stops = stops,
                    departures = departures,
                )
            }

        val lineOfStation = { id: String ->
            feed.doc.lines.firstOrNull { line -> line.stations.any { it.id == id } }?.ref.orEmpty()
        }
        val interchanges = feed.doc.interchanges.mapNotNull { link ->
            when (stationId) {
                link.a -> DossierInterchange(link.bName, lineOfStation(link.b), walkMinutesUp(link.walkSeconds))
                link.b -> DossierInterchange(link.aName, lineOfStation(link.a), walkMinutesUp(link.walkSeconds))
                else -> null
            }
        }
        return Triple(lines, interchanges, feed.doc.serviceStartSec to feed.doc.serviceEndSec)
    }

    private companion object {
        const val METRO_REACH_M = 320.0
        const val ALPHA_OPAQUE = 0xFF000000.toInt()
    }
}

internal fun secondOfDayNow(): Int = Calendar.getInstance().let {
    it.get(Calendar.HOUR_OF_DAY) * 3600 + it.get(Calendar.MINUTE) * 60 + it.get(Calendar.SECOND)
}
