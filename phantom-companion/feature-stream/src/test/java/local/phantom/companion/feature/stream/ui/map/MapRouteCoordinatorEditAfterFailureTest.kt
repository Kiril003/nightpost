package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.nav.NavigationEngine
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Той самий провал, що й у [MapRouteCoordinatorProfileSwitchTest], на пʼятьох
 * інших ручках. [RoutePlanner.plan] пише `waypoints` і `profile` лише при
 * УСПІХУ — тож коли ПЕРША спроба провалюється, кожна правка маршруту читала
 * порожній список і тихо виходила: «через цю точку», зняти проміжну, новий
 * старт, нова мета. А [MapRouteCoordinator.reorderWaypoints] списку не читає,
 * але читала типовий профіль — і «авто», що не проклалось, мовчки ставало
 * «пішки» на маршруті вздовж лінії.
 *
 * Міряємо на ДЖЕРЕЛІ маршрутів, не на смузі: скільки разів і з чим планувальник
 * пішов по маршрут — це і є відповідь «зробила щось ручка чи ні».
 *
 * `UnconfinedTestDispatcher` і `backgroundScope` — з тієї ж причини, що й у
 * сусідньому тесті: [NavigationEngine.init] запускає два нескінченні `collect`.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class MapRouteCoordinatorEditAfterFailureTest {

    private val kyiv = 50.4501 to 30.5234
    private val podil = 50.4622 to 30.5168
    private val obolon = 50.5019 to 30.4982
    private val via = 50.4547 to 30.5238

    /** Джерело маршрутів, яке памʼятає кожен запит і відповідає тим, що йому скажуть. */
    private class Harness(scope: CoroutineScope) {
        val calls = mutableListOf<Pair<RouteProfile, List<Pair<Double, Double>>>>()
        var answer: List<RouteVariant>? = null
        val engine = NavigationEngine(
            scope = scope,
            locationSource = { null },
            planner = RoutePlanner(
                scope = scope,
                routeSource = { profile, waypoints ->
                    calls += profile to waypoints
                    answer
                },
            ),
        )
        val coord = MapRouteCoordinator(
            scope = scope,
            engine = engine,
            postNotice = {},
            syncThreatWatch = {},
            navCommands = null,
        )
    }

    /** Одна точка навмисно: RouteIntel.analyze() відмовляє < 2 точок, і збагачення не йде в мережу. */
    private fun variant(profile: RouteProfile) = RouteVariant(
        points = listOf(kyiv),
        distanceMeters = 1500.0,
        durationSeconds = 1100.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = profile,
    )

    @Test
    fun `«через цю точку» перепрокладає, коли перша спроба вже провалилась`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR)
            assertEquals(1, h.calls.size)

            h.coord.addViaPoint(via.first, via.second)

            assertEquals("ручка мала піти по маршрут ще раз", 2, h.calls.size)
            assertEquals(listOf(kyiv, via, podil), h.calls[1].second)
            assertEquals("профіль — той, що пробували, не типовий пішки", RouteProfile.CAR, h.calls[1].first)
        }

    @Test
    fun `зняти проміжну точку перепрокладає, коли перша спроба вже провалилась`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.navigateTo(
                kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR,
                via = listOf(via),
            )
            assertEquals(listOf(kyiv, via, podil), h.calls.single().second)

            h.coord.removeViaPoint(1)

            assertEquals("ручка мала піти по маршрут ще раз", 2, h.calls.size)
            assertEquals(listOf(kyiv, podil), h.calls[1].second)
            assertEquals(RouteProfile.CAR, h.calls[1].first)
        }

    @Test
    fun `маршрут вздовж лінії тримає профіль провальної спроби, а не типовий`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR)

            h.coord.reorderWaypoints(listOf(podil, obolon, kyiv))

            assertEquals(2, h.calls.size)
            assertEquals(listOf(podil, obolon, kyiv), h.calls[1].second)
            assertEquals(
                "людина просила авто; лінія не має мовчки стати пішою",
                RouteProfile.CAR,
                h.calls[1].first,
            )
        }

    @Test
    fun `новий старт перепрокладає, коли перша спроба вже провалилась`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR)

            h.coord.setRouteOrigin(obolon.first, obolon.second)

            assertEquals("ручка мала піти по маршрут ще раз", 2, h.calls.size)
            assertEquals(listOf(obolon, podil), h.calls[1].second)
            assertEquals(RouteProfile.CAR, h.calls[1].first)
        }

    @Test
    fun `нова мета перепрокладає, коли перша спроба вже провалилась`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR)

            h.coord.setRouteDestination(obolon.first, obolon.second, name = "Оболонь")

            assertEquals("ручка мала піти по маршрут ще раз", 2, h.calls.size)
            assertEquals(listOf(kyiv, obolon), h.calls[1].second)
            assertEquals(RouteProfile.CAR, h.calls[1].first)
            assertEquals("смуга відмови — про нову спробу, тим же профілем", RouteProfile.CAR, h.coord.routeFailure.value?.profile)
        }

    /**
     * Контракт планувальника лишається: невдала спроба поверх ЖИВОГО маршруту
     * не стає тим, що редагують. «Через цю точку» після провального
     * перепланування веде живий маршрут, а не мертвий.
     */
    @Test
    fun `живий маршрут на карті переважає провальну спробу поверх нього`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.answer = listOf(variant(RouteProfile.FOOT))
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.FOOT)
            assertEquals(listOf(kyiv, podil), h.engine.routePlanner.waypoints)

            h.answer = null
            h.coord.routeTo(kyiv.first, kyiv.second, obolon.first, obolon.second, RouteProfile.CAR)
            assertEquals("провал не мав стерти живий маршрут", listOf(kyiv, podil), h.engine.routePlanner.waypoints)

            h.coord.addViaPoint(via.first, via.second)

            assertEquals(3, h.calls.size)
            assertEquals("редагується живий маршрут, не провальний", listOf(kyiv, via, podil), h.calls[2].second)
            assertEquals("і його профілем", RouteProfile.FOOT, h.calls[2].first)
        }

    @Test
    fun `знятий маршрут ніщо не воскрешає`() =
        runTest(UnconfinedTestDispatcher()) {
            val h = Harness(backgroundScope)
            h.coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.CAR)
            h.coord.clearRoute()

            h.coord.addViaPoint(via.first, via.second)
            h.coord.setRouteOrigin(obolon.first, obolon.second)
            h.coord.setRouteDestination(obolon.first, obolon.second)
            h.coord.reRouteProfile(kyiv.first, kyiv.second, RouteProfile.FOOT)

            assertEquals("після «прибрати маршрут» жодна ручка не має нікуди йти", 1, h.calls.size)
        }
}
