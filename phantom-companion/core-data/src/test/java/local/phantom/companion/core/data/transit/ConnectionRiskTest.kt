package local.phantom.companion.core.data.transit

import local.phantom.companion.core.data.transit.raptor.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ConnectionRiskTest {

    private val zone = ZoneId.of("Europe/Prague")
    private val day = LocalDate.of(2026, 9, 28)

    private fun ride(label: String, mode: TransitMode, dep: Int, arr: Int, op: String) = Itinerary.Leg.Ride(
        routeLabel = label, mode = mode, colour = -1,
        fromStopName = "A", toStopName = "B", fromLat = 0.0, fromLon = 0.0, toLat = 0.0, toLon = 0.0,
        departSec = dep, arriveSec = arr, intermediateStops = 2, timesAreModelled = false, operatorName = op,
    )

    private fun itin(gapSec: Int) = Itinerary(
        legs = listOf(
            ride("S6", TransitMode.RAIL, 8 * 3600, 8 * 3600 + 1800, "ČD"),
            Itinerary.Leg.Transfer("B", "B", 120),
            ride("12", TransitMode.TRAM, 8 * 3600 + 1800 + 120 + gapSec, 9 * 3600, "DPO"),
        ),
        departSec = 8 * 3600, arriveSec = 9 * 3600, transfers = 1, hasModelledTimes = false,
    )

    private fun memoryLate(minutes: Int) = TransitDelayMemory(null).also { m ->
        val at = day.atStartOfDay(zone).toInstant().toEpochMilli() + (8 * 3600 + 1800) * 1000L
        listOf("a", "b", "c").forEach { m.observe(ConnectionRisk.keyOf(itin(0).legs[0] as Itinerary.Leg.Ride), it, at, minutes * 60, zone) }
    }

    @Test
    fun `a transfer shorter than the usual delay is flagged`() {
        assertEquals(mapOf(0 to 6), ConnectionRisk.tight(itin(gapSec = 180), memoryLate(6), zone, day))
    }

    @Test
    fun `a transfer with room to spare is not`() {
        assertTrue(ConnectionRisk.tight(itin(gapSec = 900), memoryLate(6), zone, day).isEmpty())
    }

    @Test
    fun `a line never seen late raises nothing`() {
        assertTrue(ConnectionRisk.tight(itin(gapSec = 60), TransitDelayMemory(null), zone, day).isEmpty())
    }

    @Test
    fun `a safe option a few minutes later goes first`() {
        val risky = itin(gapSec = 60)
        val safe = itin(gapSec = 600).copy(arriveSec = 9 * 3600 + 300)
        val ordered = ConnectionRisk.saferFirst(listOf(risky, safe), memoryLate(6), zone, day)
        assertTrue(ordered.first() === safe)
    }
}
