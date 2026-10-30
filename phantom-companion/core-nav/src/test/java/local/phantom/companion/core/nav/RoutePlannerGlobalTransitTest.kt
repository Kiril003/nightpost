package local.phantom.companion.core.nav

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.GlobalTransitRouter
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.net.map.ElevationClient
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.RouteIntel
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

@kotlinx.coroutines.ExperimentalCoroutinesApi
class RoutePlannerGlobalTransitTest {

    private val alexanderplatz = 52.5219 to 13.4132
    private val brandenburgGate = 52.5163 to 13.3777

    private class FakeGlobalTransitRouter(
        private val outcome: GlobalTransitRouter.Outcome,
    ) : GlobalTransitRouter() {
        constructor(itineraries: List<Itinerary>) : this(
            if (itineraries.isEmpty()) Outcome.NoService else Outcome.Planned(itineraries),
        )

        override suspend fun planTransit(
            fromLat: Double,
            fromLon: Double,
            toLat: Double,
            toLon: Double,
            departSec: Int?,
        ): Outcome = outcome
    }

    private fun plannerWithFootFallback(router: GlobalTransitRouter, scope: kotlinx.coroutines.CoroutineScope) =
        RoutePlanner(
            scope = scope,
            // Пішки після транзиту збагачується коридором у фоні; з типовим RouteIntel
            // це живі Overpass і open-elevation, і runTest чекав їхніх тайм-аутів (30-60 с).
            intel = RouteIntel(OverpassClient(endpoints = emptyList()), ElevationClient("http://127.0.0.1:9/")),
            routeSource = { profile, pts ->
                listOf(
                    RouteVariant(
                        points = pts,
                        distanceMeters = 2500.0,
                        durationSeconds = 1800.0,
                        steps = emptyList(),
                        speedsMps = emptyList(),
                        profile = profile,
                    ),
                )
            },
            multimodalPlanner = null,
            globalTransitRouter = router,
            worldScheduleAllowed = { true },
            nowMs = { 0L },
        )

    @Test
    fun `глобальний транзит повертає маршрут транзиту за межами локального пака`() = runTest {
        val fakeItinerary = Itinerary(
            legs = listOf(
                Itinerary.Leg.Access(
                    mode = AccessMode.WALK,
                    fromLat = 52.5219,
                    fromLon = 13.4132,
                    toLat = 52.5215,
                    toLon = 52.4120,
                    toStopName = "U Alexanderplatz",
                    seconds = 120,
                    meters = 100,
                    points = listOf(52.5219 to 13.4132, 52.5215 to 13.4120),
                    estimated = false,
                ),
                Itinerary.Leg.Ride(
                    routeLabel = "U2",
                    mode = TransitMode.METRO,
                    colour = 0xEF4444,
                    fromStopName = "U Alexanderplatz",
                    toStopName = "U Unter den Linden",
                    fromLat = 52.5215,
                    fromLon = 13.4120,
                    toLat = 52.5170,
                    toLon = 13.3888,
                    departSec = 43200,
                    arriveSec = 43560,
                    intermediateStops = 4,
                    points = listOf(52.5215 to 13.4120, 52.5170 to 13.3888),
                    timesAreModelled = false,
                ),
            ),
            departSec = 43080,
            arriveSec = 43560,
            transfers = 0,
            hasModelledTimes = false,
        )

        val globalRouter = FakeGlobalTransitRouter(listOf(fakeItinerary))
        val planner = RoutePlanner(
            scope = this,
            multimodalPlanner = null,
            globalTransitRouter = globalRouter,
            worldScheduleAllowed = { true },
            nowMs = { 0L },
        )

        val selected = planner.plan(listOf(alexanderplatz, brandenburgGate), RouteProfile.TRANSIT)

        assertNotNull(selected)
        assertEquals(RouteProfile.TRANSIT, selected?.profile)
        assertEquals(RouteProfile.TRANSIT, planner.profile)
        assertNull(planner.transitFailureReason)
        assertNotNull(planner.itineraryFor(0))
    }

    @Test
    fun `коли глобальний транзит порожній відбувається перехід у пішки`() = runTest {
        val planner = plannerWithFootFallback(FakeGlobalTransitRouter(emptyList()), this)

        val selected = planner.plan(listOf(alexanderplatz, brandenburgGate), RouteProfile.TRANSIT)

        assertNotNull(selected)
        assertEquals(RouteProfile.FOOT, selected?.profile)
        assertEquals(RouteProfile.FOOT, planner.profile)
        assertNotNull(planner.transitFailureReason)
    }

    /**
     * Діагноз провалу був мертвим кодом: перша гілка `globalTransitRouter != null`
     * у продакшені істинна ЗАВЖДИ, тож людина без мережі чула «маршрутів не
     * знайдено» — заяву про світ замість зізнання про себе.
     */
    @Test
    fun `без мережі планувальник зізнається про себе а не судить про світ`() = runTest {
        val planner = plannerWithFootFallback(
            FakeGlobalTransitRouter(GlobalTransitRouter.Outcome.Unreachable),
            this,
        )

        planner.plan(listOf(alexanderplatz, brandenburgGate), RouteProfile.TRANSIT)

        assertEquals(TransitFailure.Unreachable, planner.transitFailureReason)
    }

    @Test
    fun `коли дріт відповів і рейсів нема причина називає годину`() = runTest {
        val planner = plannerWithFootFallback(
            FakeGlobalTransitRouter(GlobalTransitRouter.Outcome.NoService),
            this,
        )

        planner.plan(listOf(alexanderplatz, brandenburgGate), RouteProfile.TRANSIT, departSec = 3 * 3600)

        assertEquals(TransitFailure.NoTripsAt(3 * 3600), planner.transitFailureReason)
    }

    @Test
    fun `без згоди людини світовий розклад не питають - нуль запитів і чесне без розкладу`() = runTest {
        var asked = 0
        val counting = object : GlobalTransitRouter() {
            override suspend fun planTransit(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, departSec: Int?): Outcome {
                asked++
                return Outcome.NoService
            }
        }
        val planner = RoutePlanner(
            scope = this,
            intel = RouteIntel(OverpassClient(endpoints = emptyList()), ElevationClient("http://127.0.0.1:9/")),
            routeSource = { _, _ -> emptyList() },
            multimodalPlanner = null,
            globalTransitRouter = counting,
            nowMs = { 0L },
        )

        planner.plan(listOf(alexanderplatz, brandenburgGate), RouteProfile.TRANSIT)

        assertEquals(0, asked)
        assertEquals(TransitFailure.NoSchedule, planner.transitFailureReason)
    }
}
