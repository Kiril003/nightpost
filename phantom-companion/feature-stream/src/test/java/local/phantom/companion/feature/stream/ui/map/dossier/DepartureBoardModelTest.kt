package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.core.data.transit.TransitDelayMemory
import local.phantom.companion.core.net.transit.LineLabel
import local.phantom.companion.core.net.transit.TransitousStopClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DepartureBoardModelTest {

    private val client = TransitousStopClient()
    private val now = Instant.parse("2026-09-28T13:20:00Z").toEpochMilli()

    private fun stopTime(short: String, at: String, sched: String = at, rt: Boolean = false, trip: String, track: String? = null, cancelled: Boolean = false) = """
        {"routeShortName":"$short","routeId":"cz-JDF-merged_-CISR-$short-1","tripId":"$trip","mode":"BUS",
         "headsign":"Ostrava,Dubina","agencyName":"Dopravní podnik Ostrava a.s.","realTime":$rt,"cancelled":$cancelled,
         "place":{"name":"Ostrava,hlavní nádraží","departure":"$at","scheduledDeparture":"$sched","tz":"Europe/Prague"${track?.let { ",\"track\":\"$it\"" } ?: ""}}}
    """.trimIndent()

    private fun board(): TransitousStopClient.DepartureBoard = TransitousStopClient.DepartureBoard(
        upcoming = client.parseBoard("""{"stopTimes":[${listOf(
            stopTime("915012", "2026-09-28T13:23:00Z", trip = "t2"),
            stopTime("915012", "2026-09-28T13:41:00Z", "2026-09-28T13:37:00Z", rt = true, trip = "t3", track = "B"),
            stopTime("915108", "2026-09-28T14:40:00Z", trip = "t4", cancelled = true),
        ).joinToString(",")}]}"""),
        recent = client.parseBoard("""{"stopTimes":[${stopTime("915012", "2026-09-28T13:08:00Z", trip = "t1")}]}"""),
    )

    @Test
    fun `the board speaks the sign number, never the licence`() {
        val rows = DepartureBoardModel.rows(board(), now)
        assertEquals(listOf("12", "12", "12", "108"), rows.map { it.label })
        assertTrue(rows.none { LineLabel.looksLikeId(it.label) })
    }

    @Test
    fun `past departures stay on the board, dimmed, in time order`() {
        val rows = DepartureBoardModel.rows(board(), now)
        assertEquals(listOf(true, false, false, false), rows.map { it.past })
        assertEquals("15:08", rows[0].clock)
        assertNull(rows[0].inMinutes)
    }

    @Test
    fun `near departures count minutes, far ones show the clock`() {
        val rows = DepartureBoardModel.rows(board(), now)
        assertEquals(3, rows[1].inMinutes)
        assertEquals("15:23", rows[1].clock)
        assertNull(rows[3].inMinutes)
        assertEquals("16:40", rows[3].clock)
    }

    @Test
    fun `live delay, platform and cancellation reach the row`() {
        val rows = DepartureBoardModel.rows(board(), now)
        assertEquals(4, rows[2].lateMinutes)
        assertEquals("B", rows[2].platform)
        assertTrue(rows[3].cancelled)
        assertNull(rows[1].lateMinutes)
    }

    @Test
    fun `earlier and later trips of the same line are found on the board`() {
        val rows = DepartureBoardModel.rows(board(), now)
        val (earlier, later) = DepartureBoardModel.siblings(rows, rows[1])
        assertEquals(listOf("t1"), earlier.map { it.tripId })
        assertEquals(listOf("t3"), later.map { it.tripId })
    }

    @Test
    fun `a line that usually runs late says so once it was seen live`() {
        val memory = TransitDelayMemory(null)
        listOf("a", "b", "c").forEachIndexed { i, trip ->
            val live = TransitousStopClient.DepartureBoard(
                upcoming = client.parseBoard("""{"stopTimes":[${stopTime("915012", "2026-09-2${i + 1}T13:28:00Z", "2026-09-2${i + 1}T13:23:00Z", rt = true, trip = trip)}]}"""),
                recent = emptyList(),
            )
            DepartureBoardModel.learn(live, memory)
        }
        val rows = DepartureBoardModel.rows(board(), now, memory)
        assertEquals(5, rows[1].usuallyLateMinutes)
        assertFalse(rows[0].usuallyLateMinutes != null)
    }
}
