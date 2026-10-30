package local.phantom.companion.core.nav

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteVariant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож черговості. Він існує через конкретну ціну: поки мережа стояла
 * першою, кожна перебудова коштувала водієві до 12 секунд чекання при
 * готовому графі в кишені.
 *
 * Чого цей сторож НЕ бачить за побудовою: він судить лише ПОРЯДОК виклику, не
 * якість маршруту і не час відповіді на пристрої. «Граф перший» тут доведено;
 * «граф швидший за 200 мс» доводиться на телефоні секундоміром, не тут.
 */
class RouteOrderTest {

    private fun variant(dist: Double) = RouteVariant(
        points = listOf(50.0 to 30.0, 50.01 to 30.0),
        distanceMeters = dist,
        durationSeconds = 60.0,
        steps = emptyList(),
        speedsMps = emptyList(),
        profile = RouteProfile.CAR,
    )

    @Test
    fun `граф відповів — мережу не питають узагалі`() = runBlocking {
        var networkAsked = false
        val onDevice = variant(1234.0)
        val got = routeGraphFirst(
            onDevice = { listOf(onDevice) },
            network = { networkAsked = true; listOf(variant(9999.0)) },
        )
        assertSame("мусить вернутись маршрут графа", onDevice, got?.first())
        assertFalse(
            "мережу не можна питати, коли свій граф уже відповів — це і є ті 12 секунд",
            networkAsked,
        )
    }

    @Test
    fun `граф каже «не знаю» — тоді мережа`() = runBlocking {
        val got = routeGraphFirst(
            onDevice = { null },
            network = { listOf(variant(777.0)) },
        )
        assertEquals(777.0, got?.first()?.distanceMeters ?: 0.0, 0.001)
    }

    @Test
    fun `порожній список від графа — це теж «не знаю»`() = runBlocking {
        var networkAsked = false
        val got = routeGraphFirst(
            onDevice = { emptyList() },
            network = { networkAsked = true; listOf(variant(42.0)) },
        )
        assertTrue("порожній список не маршрут", networkAsked)
        assertEquals(42.0, got?.first()?.distanceMeters ?: 0.0, 0.001)
    }

    @Test
    fun `обидва мовчать — чесний null, а не пряма лінія`() = runBlocking {
        assertEquals(null, routeGraphFirst(onDevice = { null }, network = { null }))
    }
}
