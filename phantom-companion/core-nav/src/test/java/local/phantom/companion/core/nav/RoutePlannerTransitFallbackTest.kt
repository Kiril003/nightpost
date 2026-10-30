package local.phantom.companion.core.nav

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Сторож запасного плану транзиту: провал веде в ПІШКИ і каже чому.
 *
 * Раніше >2,5 км мовчки давав АВТО — людина, що просила транспорт, діставала
 * автомобільний маршрут без пояснення, а профіль підмінювався тихо. Цей тест
 * червоніє, щойно в запасному шляху знову зʼявиться CAR або зникне причина.
 */
@kotlinx.coroutines.ExperimentalCoroutinesApi
class RoutePlannerTransitFallbackTest {

    private val kyiv = 50.4501 to 30.5234

    // ~19 км: стара логіка на цій відстані обирала саме CAR.
    private val brovary = 50.5111 to 30.7909

    // Одна точка в геометрії навмисно: RouteIntel.analyze() відмовляє на < 2
    // точках, тож фонове збагачення не ходить у мережу і тест герметичний.
    private fun variant(profile: RouteProfile) = RouteVariant(
        points = listOf(kyiv),
        distanceMeters = 19_000.0,
        durationSeconds = 14_000.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = profile,
    )

    @Test
    fun `транзит без розкладу падає в пішки, ніколи в авто`() = runTest {
        val asked = mutableListOf<RouteProfile>()
        val planner = RoutePlanner(
            scope = this,
            routeSource = { profile, _ -> asked += profile; listOf(variant(profile)) },
            nowMs = { 0L },
        )
        val selected = planner.plan(listOf(kyiv, brovary), RouteProfile.TRANSIT)
        assertNotNull(selected)
        assertEquals(listOf(RouteProfile.FOOT), asked)
        assertEquals(RouteProfile.FOOT, planner.profile)
        assertNotNull("причина провалу транзиту мусить пережити пішу заміну", planner.transitFailureReason)
    }
}
