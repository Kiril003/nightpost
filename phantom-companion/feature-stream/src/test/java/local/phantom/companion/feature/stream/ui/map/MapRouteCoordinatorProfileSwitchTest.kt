package local.phantom.companion.feature.stream.ui.map

import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.nav.NavigationEngine
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Апарат: тап по «авто» на смузі відмови нічого не міняв — дерево екрана
 * лишало обраним профіль ПЕРШОЇ спроби назавжди. Причина:
 * [RoutePlanner.plan] пише `waypoints`/`profile` лише при УСПІХУ (щоб
 * невдала «через цю точку» не стерла живий маршрут на карті) — тож коли
 * ПЕРША Ж спроба провалюється, `planner.waypoints` лишається порожнім, і
 * `reRouteProfile` тихо виходить на `wp.size < 2` для будь-якого наступного
 * профілю, назавжди.
 *
 * `UnconfinedTestDispatcher`, не стандартний: [NavigationEngine.init]
 * запускає два нескінченні `collect` (voiceCues, guidance.state), і на
 * стандартному диспетчері `runTest`'s `advanceUntilIdle()` жодного разу не
 * просунув жодну launch-корутину цього дерева — під unconfined кожна
 * запущена корутина виконується одразу, без черги.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class MapRouteCoordinatorProfileSwitchTest {

    private val kyiv = 50.4501 to 30.5234
    private val podil = 50.4622 to 30.5168

    @Test
    fun `профіль перемикається на смузі відмови, навіть коли ПЕРША спроба вже провалилась`() =
        runTest(UnconfinedTestDispatcher()) {
            // Кожен профіль провалюється — смуга відмови ніколи не сходить з екрана.
            val engine = NavigationEngine(
                scope = backgroundScope,
                locationSource = { null },
                planner = RoutePlanner(scope = backgroundScope, routeSource = { _, _ -> null }),
            )
            val coord = MapRouteCoordinator(
                scope = backgroundScope,
                engine = engine,
                postNotice = {},
                syncThreatWatch = {},
                navCommands = null,
            )

            coord.routeTo(kyiv.first, kyiv.second, podil.first, podil.second, RouteProfile.FOOT)
            assertEquals(
                "перша спроба мала показати ПІШКИ",
                RouteProfile.FOOT,
                coord.routeFailure.value?.profile,
            )

            coord.reRouteProfile(kyiv.first, kyiv.second, RouteProfile.CAR)
            assertEquals(
                "тап «авто» на мертвому маршруті не мав лишити попередній профіль обраним",
                RouteProfile.CAR,
                coord.routeFailure.value?.profile,
            )
        }
}
