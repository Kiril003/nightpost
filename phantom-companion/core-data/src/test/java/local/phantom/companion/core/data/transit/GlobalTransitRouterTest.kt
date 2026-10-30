package local.phantom.companion.core.data.transit

import java.util.Calendar
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.transit.TransitousTransitClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Час закордонного транзиту.
 *
 * Донедавна розбирач діставав час ноги викликом `parse(null)`, який за
 * побудовою повертав null, тож кожна нога починалась там, де скінчилась
 * попередня. Час чекання на зупинці структурно дорівнював нулю: трамвай
 * «відходив» тією ж секундою, коли ти дійшов. Числа нижче — з реальної
 * відповіді api.transitous.org (Кельн → Штутгарт, 20.09.2026), і саме
 * двохвилинна пауза в Мангаймі ловить стару ваду.
 */
class GlobalTransitRouterTest {

    private class FakeTransitousClient(
        private val planToReturn: TransitousTransitClient.Plan?,
    ) : TransitousTransitClient() {
        var lastDepartTime: String? = null
        var lastDepartDate: String? = null

        override suspend fun plan(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double,
            departTime: String?,
            departDate: String?,
            numItineraries: Int,
        ): TransitousTransitClient.Plan? {
            lastDepartTime = departTime
            lastDepartDate = departDate
            return planToReturn
        }
    }

    private fun leg(
        mode: String,
        from: String,
        to: String,
        durationSeconds: Int,
        startTimeIso: String? = null,
        endTimeIso: String? = null,
        routeShortName: String? = null,
        intermediateStopCount: Int? = null,
    ) = TransitousTransitClient.Leg(
        mode = mode,
        routeShortName = routeShortName,
        routeLongName = null,
        routeColor = null,
        fromName = from,
        fromLat = 50.0,
        fromLon = 8.0,
        toName = to,
        toLat = 49.0,
        toLon = 9.0,
        durationSeconds = durationSeconds,
        distanceMeters = 100.0,
        points = emptyList(),
        startTimeIso = startTimeIso,
        endTimeIso = endTimeIso,
        intermediateStopCount = intermediateStopCount,
    )

    /** Кельн → Штутгарт, сім ніг, справжні часи з дроту. */
    private fun mannheimTrip() = TransitousTransitClient.Plan(
        listOf(
            TransitousTransitClient.Itinerary(
                durationSeconds = 9300,
                startTimeIso = "2026-09-20T04:43:00Z",
                endTimeIso = "2026-09-20T07:18:00Z",
                transfers = 2,
                legs = listOf(
                    leg("WALK", "Köln", "Köln Hbf", 720, "2026-09-20T04:43:00Z", "2026-09-20T04:55:00Z"),
                    leg(
                        "HIGHSPEED_RAIL", "Köln Hbf", "Mannheim, Hauptbahnhof", 5460,
                        "2026-09-20T04:55:00Z", "2026-09-20T06:26:00Z",
                        routeShortName = "ICE 2511", intermediateStopCount = 1,
                    ),
                    leg(
                        "WALK", "Mannheim, Hauptbahnhof", "Mannheim, Hauptbahnhof", 300,
                        "2026-09-20T06:26:00Z", "2026-09-20T06:31:00Z",
                    ),
                    leg(
                        "HIGHSPEED_RAIL", "Mannheim, Hauptbahnhof", "Hauptbahnhof (oben)", 2280,
                        "2026-09-20T06:33:00Z", "2026-09-20T07:11:00Z",
                        routeShortName = "ICE 591", intermediateStopCount = 0,
                    ),
                    leg(
                        "WALK", "Hauptbahnhof (oben)", "Hauptbahnhof", 240,
                        "2026-09-20T07:11:00Z", "2026-09-20T07:15:00Z",
                    ),
                    leg(
                        "SUBWAY", "Hauptbahnhof", "Stadtmitte", 120,
                        "2026-09-20T07:15:00Z", "2026-09-20T07:17:00Z",
                        routeShortName = "U7", intermediateStopCount = 1,
                    ),
                    leg("WALK", "Stadtmitte", "Ціль", 60, "2026-09-20T07:17:00Z", "2026-09-20T07:18:00Z"),
                ),
            ),
        ),
    )

    private suspend fun planned(client: FakeTransitousClient, departSec: Int? = null): List<Itinerary> {
        val outcome = GlobalTransitRouter(client).planTransit(50.94, 6.95, 48.78, 9.18, departSec)
        assertTrue("очікувався Planned, а не $outcome", outcome is GlobalTransitRouter.Outcome.Planned)
        return (outcome as GlobalTransitRouter.Outcome.Planned).itineraries
    }

    /**
     * ГОЛОВНИЙ. Між пішою ногою, що кінчається 06:31, і поїздом, що йде о
     * 06:33, стоять 120 секунд чекання на пероні. Від прибуття ICE 2511
     * (06:26) до відправлення ICE 591 (06:33) — 420 секунд, а не 300 секунд
     * ходу. Стара схема ніс-у-хвіст давала рівно 300.
     */
    @Test
    fun `пауза на пересадці не зникає`() = runBlocking {
        val itin = planned(FakeTransitousClient(mannheimTrip())).single()
        val rides = itin.legs.filterIsInstance<Itinerary.Leg.Ride>()
        assertEquals(3, rides.size)

        val ice2511 = rides[0]
        val ice591 = rides[1]

        assertEquals("ICE 2511", ice2511.routeLabel)
        assertEquals("ICE 591", ice591.routeLabel)

        // 06:26 → 06:33. 300 с ходу + 120 с чекання на пероні в Мангаймі.
        assertEquals(420, ice591.departSec - ice2511.arriveSec)
        // Саме ці 120 секунд і зникали.
        assertEquals(120, (ice591.departSec - ice2511.arriveSec) - 300)

        // Тривалості ніг у сумі дають 9180; справжня поїздка — 9300.
        assertEquals(9180, itin.legs.sumOf { it.seconds })
        assertEquals(9300, itin.durationSec)
    }

    @Test
    fun `часи ніг узяті з дроту не змодельовані`() = runBlocking {
        val itin = planned(FakeTransitousClient(mannheimTrip())).single()
        assertFalse(itin.hasModelledTimes)
        assertTrue(itin.legs.filterIsInstance<Itinerary.Leg.Ride>().none { it.timesAreModelled })
    }

    @Test
    fun `проміжні зупинки беруться з дроту`() = runBlocking {
        val rides = planned(FakeTransitousClient(mannheimTrip()))
            .single().legs.filterIsInstance<Itinerary.Leg.Ride>()
        assertEquals(1, rides[0].intermediateStops)
        // Дріт сказав «нуль» — це нуль, і рядок «· N зупинок» UI не намалює.
        assertEquals(0, rides[1].intermediateStops)
        assertEquals(1, rides[2].intermediateStops)
    }

    /**
     * Дріт без часів. Вигадувати впевнену хвилину не можна: ноги склеюються
     * ніс-у-хвіст як відверто модельована оцінка, і вирок це каже вголос,
     * щоб UI поставив «≈».
     */
    @Test
    fun `без часу в дроті вирок чесний`() = runBlocking {
        val plan = TransitousTransitClient.Plan(
            listOf(
                TransitousTransitClient.Itinerary(
                    durationSeconds = 1020,
                    startTimeIso = null,
                    endTimeIso = null,
                    transfers = 0,
                    legs = listOf(
                        leg("WALK", "Двері", "Зупинка", 240),
                        leg("TRAM", "Зупинка", "Кінець", 600, routeShortName = "M5"),
                        leg("WALK", "Кінець", "Ціль", 180),
                    ),
                ),
            ),
        )

        val itin = planned(FakeTransitousClient(plan), departSec = 8 * 3600).single()
        val ride = itin.legs.filterIsInstance<Itinerary.Leg.Ride>().single()

        assertTrue("час вигаданий нами — це мусить бути видно", ride.timesAreModelled)
        assertTrue(itin.hasModelledTimes)
        assertEquals(8 * 3600, itin.departSec)
        assertEquals(8 * 3600 + 240, ride.departSec)
        assertEquals(1020, itin.durationSec)
    }

    @Test
    fun `мовчання дроту це не відсутність рейсів`() = runBlocking {
        val silent = GlobalTransitRouter(FakeTransitousClient(null))
            .planTransit(50.0, 8.0, 49.0, 9.0)
        assertSame(GlobalTransitRouter.Outcome.Unreachable, silent)

        val empty = GlobalTransitRouter(FakeTransitousClient(TransitousTransitClient.Plan(emptyList())))
            .planTransit(50.0, 8.0, 49.0, 9.0)
        assertSame(GlobalTransitRouter.Outcome.NoService, empty)
    }

    /**
     * Дата мусить слухатись години виїзду. Раніше вона була завжди
     * сьогоднішня, тож «виїхати о 8:00», обране ввечері, летіло запитом у
     * сьогоднішній ранок — у минуле, і відповідь була про поїздки, що вже
     * відбулись.
     */
    @Test
    fun `обрана година в минулому означає завтра`() = runBlocking {
        val now = Calendar.getInstance()
        val nowSec = now.get(Calendar.HOUR_OF_DAY) * 3600 +
            now.get(Calendar.MINUTE) * 60 +
            now.get(Calendar.SECOND)

        // Дві години тому — людина має на увазі завтра.
        if (nowSec >= 2 * 3600) {
            val past = FakeTransitousClient(mannheimTrip())
            planned(past, departSec = nowSec - 2 * 3600)
            assertEquals(dateAfter(1), past.lastDepartDate)
        }

        // Через дві години — сьогодні.
        if (nowSec + 2 * 3600 < 86_400) {
            val future = FakeTransitousClient(mannheimTrip())
            planned(future, departSec = nowSec + 2 * 3600)
            assertEquals(dateAfter(0), future.lastDepartDate)
        }

        // «Зараз» дати не зсуває.
        val nowClient = FakeTransitousClient(mannheimTrip())
        planned(nowClient, departSec = null)
        assertEquals(dateAfter(0), nowClient.lastDepartDate)
    }

    @Test
    fun `час після півночі несе добу вперед`() = runBlocking {
        val client = FakeTransitousClient(mannheimTrip())
        // 25:30 за нотацією розкладу — 01:30 наступної доби.
        planned(client, departSec = 25 * 3600 + 30 * 60)
        assertEquals("01:30", client.lastDepartTime)
        assertEquals(dateAfter(1), client.lastDepartDate)
    }

    @Test
    fun `режим і колір ноги беруться з дроту`() = runBlocking {
        val rides = planned(FakeTransitousClient(mannheimTrip()))
            .single().legs.filterIsInstance<Itinerary.Leg.Ride>()
        assertEquals(TransitMode.RAIL, rides[0].mode)
        assertEquals(TransitMode.METRO, rides[2].mode)
        assertEquals(0xEF4444, rides[2].colour)
    }

    private fun dateAfter(days: Int): String {
        val c = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, days) }
        return String.format(
            "%04d-%02d-%02d",
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
        )
    }
}
