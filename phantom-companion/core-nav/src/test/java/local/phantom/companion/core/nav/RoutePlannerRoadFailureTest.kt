package local.phantom.companion.core.nav

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * «маршрутизатор недоступний» був тим самим реченням і для «сервер не
 * налаштований», і для «на пристрої нема доріг цієї ділянки» — два різні
 * світи, одна брехня спрощення. [RoutePlanner.roadFailureReason] несе те,
 * що знає лише той, хто побудував [routeSource] — тут ми граємо за нього.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class RoutePlannerRoadFailureTest {

    private val kyiv = 50.4501 to 30.5234
    private val podil = 50.4622 to 30.5168

    private fun variant() = RouteVariant(
        points = listOf(kyiv),
        distanceMeters = 1500.0,
        durationSeconds = 1100.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.FOOT,
    )

    @Test
    fun `відмова несе причину, названу тим, хто побудував routeSource`() = runTest {
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> null },
            routeRefusal = { RoadRefusal.NO_DATA },
        )

        assertNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertEquals(RoadRefusal.NO_DATA, planner.roadFailureReason)
    }

    @Test
    fun `наступний успіх стирає стару причину`() = runTest {
        var fail = true
        val planner = RoutePlanner(
            scope = this,
            routeSource = { _, _ -> if (fail) null else listOf(variant()) },
            routeRefusal = { RoadRefusal.TOO_LONG },
        )

        assertNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertEquals(RoadRefusal.TOO_LONG, planner.roadFailureReason)

        fail = false
        assertNotNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertNull("живий маршрут не має тягнути за собою стару відмову", planner.roadFailureReason)
    }

    @Test
    fun `без routeRefusal причина мовчить, як і раніше`() = runTest {
        val planner = RoutePlanner(scope = this, routeSource = { _, _ -> null })

        assertNull(planner.plan(listOf(kyiv, podil), RouteProfile.FOOT))
        assertNull(planner.roadFailureReason)
    }
}
