package local.phantom.companion.core.data.transit.gtfs

import local.phantom.companion.core.data.transit.raptor.RawStop
import local.phantom.companion.core.data.transit.raptor.RawStopTime
import local.phantom.companion.core.data.transit.raptor.RawTrip
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.LineLabel
import java.io.InputStream
import java.util.zip.ZipInputStream

/** What one GTFS zip yields once we've thrown away everything RAPTOR won't read. */
data class GtfsFeed(
    val stops: List<RawStop>,
    val trips: List<RawTrip>,
    /** From `transfers.txt` — (fromStopId, toStopId, seconds). */
    val transfers: List<Triple<String, String, Int>>,
    val feedVersion: String?,
    val routeLabels: Map<String, String> = emptyMap(),
)

/**
 * MULTIMODAL CONTINUUM — GTFS static → [RawTrip]s, streamed.
 *
 * `stop_times.txt` is 17 MB in the Lviv feed and 100 k rows in Kyiv's. It
 * is read once, as a stream, and only the rows belonging to trips that
 * actually run on the requested service day are kept — the other five days
 * of the week never become objects. Parsing the whole file into a list
 * first, then filtering, is how a transit app earns an OOM on a mid-range
 * phone; we don't do it.
 *
 * We read the zip through [ZipInputStream] in a single forward pass, which
 * means we cannot seek back to a file we've already passed. GTFS gives no
 * ordering guarantee inside the archive, so the pass collects raw rows and
 * the resolution (which service runs today → which trips → which calls)
 * happens afterwards, in [assemble].
 */
object GtfsStaticParser {

    /**
     * @param serviceDate the day to extract, as GTFS writes it: `yyyyMMdd`.
     *   A timetable is meaningless without one — Sunday's tram is not
     *   Monday's, and a router that ignores the calendar will cheerfully
     *   put the operator on a bus that hasn't run since Friday.
     * @param weekday 1=Monday … 7=Sunday, matching `calendar.txt`'s columns.
     */
    fun parse(zip: InputStream, serviceDate: String, weekday: Int): GtfsFeed {
        val stops = ArrayList<RawStop>()
        val routes = HashMap<String, RouteMeta>()
        val tripRoute = HashMap<String, String>()      // trip_id → route_id
        val tripService = HashMap<String, String>()    // trip_id → service_id
        val calls = HashMap<String, MutableList<Seq>>() // trip_id → its calls
        val calendar = HashMap<String, Boolean>()      // service_id → runs on `weekday`
        val calendarRange = HashMap<String, Pair<String, String>>()
        val exceptions = HashMap<String, Boolean>()    // service_id → added(true)/removed(false) on serviceDate
        val transfers = ArrayList<Triple<String, String, Int>>()
        var feedVersion: String? = null

        ZipInputStream(zip).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                when (entry.name.substringAfterLast('/')) {
                    "stops.txt" -> readCsv(zin) { row ->
                        val id = row["stop_id"] ?: return@readCsv
                        val lat = row["stop_lat"]?.toDoubleOrNull() ?: return@readCsv
                        val lon = row["stop_lon"]?.toDoubleOrNull() ?: return@readCsv
                        // location_type 1 = a station parent, 2..4 = entrances and
                        // generic nodes. None of them is a place a vehicle calls at.
                        val type = row["location_type"]?.trim()
                        if (type == "1" || type == "2" || type == "3" || type == "4") return@readCsv
                        stops += RawStop(id, row["stop_name"].orEmpty(), lat, lon)
                    }

                    "routes.txt" -> readCsv(zin) { row ->
                        val id = row["route_id"] ?: return@readCsv
                        val label = LineLabel.of(row["route_short_name"], row["route_long_name"], id)
                        routes[id] = RouteMeta(
                            label = label,
                            mode = TransitMode.fromGtfs(row["route_type"]?.toIntOrNull() ?: -1),
                            colour = row["route_color"]?.takeIf { it.isNotBlank() }
                                ?.let { runCatching { it.trim().removePrefix("#").toInt(16) }.getOrNull() }
                                ?: -1,
                        )
                    }

                    "trips.txt" -> readCsv(zin) { row ->
                        val id = row["trip_id"] ?: return@readCsv
                        tripRoute[id] = row["route_id"] ?: return@readCsv
                        tripService[id] = row["service_id"].orEmpty()
                    }

                    "calendar.txt" -> readCsv(zin) { row ->
                        val id = row["service_id"] ?: return@readCsv
                        val col = when (weekday) {
                            1 -> "monday"; 2 -> "tuesday"; 3 -> "wednesday"; 4 -> "thursday"
                            5 -> "friday"; 6 -> "saturday"; else -> "sunday"
                        }
                        calendar[id] = row[col]?.trim() == "1"
                        val from = row["start_date"].orEmpty()
                        val to = row["end_date"].orEmpty()
                        if (from.isNotEmpty() && to.isNotEmpty()) calendarRange[id] = from to to
                    }

                    "calendar_dates.txt" -> readCsv(zin) { row ->
                        val id = row["service_id"] ?: return@readCsv
                        if (row["date"]?.trim() != serviceDate) return@readCsv
                        // 1 = service added on this date, 2 = removed. Holidays live here,
                        // and they override calendar.txt — that is the whole point of the file.
                        exceptions[id] = row["exception_type"]?.trim() == "1"
                    }

                    "stop_times.txt" -> readCsv(zin) { row ->
                        val trip = row["trip_id"] ?: return@readCsv
                        val stop = row["stop_id"] ?: return@readCsv
                        val seq = row["stop_sequence"]?.toIntOrNull() ?: return@readCsv
                        val dep = parseGtfsTime(row["departure_time"])
                        val arr = parseGtfsTime(row["arrival_time"]) ?: dep
                        if (dep == null || arr == null) return@readCsv   // an unscheduled call cannot be boarded
                        calls.getOrPut(trip) { ArrayList() } += Seq(seq, stop, arr, dep)
                    }

                    "transfers.txt" -> readCsv(zin) { row ->
                        val from = row["from_stop_id"] ?: return@readCsv
                        val to = row["to_stop_id"] ?: return@readCsv
                        // transfer_type 3 = transfer NOT possible here. Honouring it
                        // matters: it usually marks two stops that look 30 m apart on
                        // the map with a river or a railway between them.
                        if (row["transfer_type"]?.trim() == "3") return@readCsv
                        val secs = row["min_transfer_time"]?.toIntOrNull() ?: 120
                        transfers += Triple(from, to, secs)
                    }

                    "feed_info.txt" -> readCsv(zin) { row ->
                        feedVersion = feedVersion ?: row["feed_version"]
                    }
                }
                zin.closeEntry()
            }
        }

        val active = activeServices(calendar, calendarRange, exceptions, serviceDate)
        return assemble(stops, routes, tripRoute, tripService, calls, active, transfers, feedVersion)
    }

    private fun activeServices(
        calendar: Map<String, Boolean>,
        ranges: Map<String, Pair<String, String>>,
        exceptions: Map<String, Boolean>,
        serviceDate: String,
    ): Set<String> {
        val out = HashSet<String>()
        for ((id, runsToday) in calendar) {
            val inRange = ranges[id]?.let { (from, to) -> serviceDate >= from && serviceDate <= to } ?: true
            if (runsToday && inRange) out += id
        }
        for ((id, added) in exceptions) if (added) out += id else out -= id
        return out
    }

    private fun assemble(
        stops: List<RawStop>,
        routes: Map<String, RouteMeta>,
        tripRoute: Map<String, String>,
        tripService: Map<String, String>,
        calls: Map<String, MutableList<Seq>>,
        active: Set<String>,
        transfers: List<Triple<String, String, Int>>,
        feedVersion: String?,
    ): GtfsFeed {
        val trips = ArrayList<RawTrip>(calls.size)
        for ((tripId, seq) in calls) {
            if (tripService[tripId] !in active) continue
            val meta = routes[tripRoute[tripId]] ?: continue
            seq.sortBy { it.seq }                       // stop_sequence need not be dense, or even sorted
            trips += RawTrip(
                routeLabel = meta.label,
                mode = meta.mode,
                colour = meta.colour,
                calls = seq.map { RawStopTime(it.stopId, it.arrivalSec, it.departureSec) },
                timesAreModelled = false,
            )
        }
        val labels = routes.mapNotNull { (id, meta) -> meta.label.takeIf { it.isNotEmpty() }?.let { id to it } }.toMap()
        return GtfsFeed(stops, trips, transfers, feedVersion, labels)
    }

    /**
     * GTFS times are `H:MM:SS` and may pass 24:00:00 — "25:10:00" is 01:10
     * on the next calendar day, still belonging to *this* service day. We
     * keep them past 86 400 on purpose; wrapping them would sever the night
     * routes and the metro's last train from the day that runs them.
     */
    internal fun parseGtfsTime(raw: String?): Int? {
        val s = raw?.trim() ?: return null
        if (s.isEmpty()) return null
        var h = 0; var m = 0; var sec = 0; var field = 0; var cur = 0; var any = false
        for (c in s) {
            when {
                c.isDigit() -> { cur = cur * 10 + (c - '0'); any = true }
                c == ':' -> {
                    when (field) { 0 -> h = cur; 1 -> m = cur }
                    field++; cur = 0
                }
                else -> return null
            }
        }
        if (!any) return null
        when (field) { 0 -> h = cur; 1 -> m = cur; else -> sec = cur }
        return h * 3600 + m * 60 + sec
    }

    private data class RouteMeta(val label: String, val mode: TransitMode, val colour: Int)
    private data class Seq(val seq: Int, val stopId: String, val arrivalSec: Int, val departureSec: Int)

    /**
     * Minimal RFC-4180 reader: quoted fields, doubled quotes inside them, and
     * the UTF-8 BOM that Ukrainian feeds ship on every single file (both the
     * Kyivpastrans and Lviv archives do — an unstripped BOM turns the first
     * header into `﻿stop_id` and every lookup of `stop_id` then misses).
     *
     * Does not close [stream] — it is one entry of a still-open zip.
     */
    private inline fun readCsv(stream: InputStream, onRow: (Map<String, String>) -> Unit) {
        val reader = stream.bufferedReader(Charsets.UTF_8)
        val headerLine = reader.readLine() ?: return
        val header = splitCsv(headerLine.removePrefix("﻿")).map { it.trim() }
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val cells = splitCsv(line)
            if (cells.isEmpty()) continue
            val row = HashMap<String, String>(header.size * 2)
            for (i in header.indices) row[header[i]] = cells.getOrNull(i).orEmpty()
            onRow(row)
        }
    }

    internal fun splitCsv(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.setLength(0) }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }
}
