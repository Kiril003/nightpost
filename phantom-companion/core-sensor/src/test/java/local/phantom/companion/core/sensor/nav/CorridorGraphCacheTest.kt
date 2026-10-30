package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Кеш коридору існує через заміряне: підготовка графа коштує 77 мс проти 24 мс
 * самого пошуку, і робилась вона наново на КОЖНУ перебудову.
 *
 * Ці тести стережуть не швидкість (її міряють інші), а ПРАВДУ кешу: коли він
 * має право відповісти з памʼяті, і — важливіше — коли не має.
 */
class CorridorGraphCacheTest {

    private fun graph(id: Long) = RoadGraph(
        listOf(
            RoadWay(
                id = id,
                points = listOf(50.0 to 30.0, 50.01 to 30.0),
                tags = mapOf("highway" to "residential"),
            ),
        ),
    )

    private var now = 1_000_000L

    private fun cache() = CorridorGraphCache(maxAgeMs = 60_000L, nowMs = { now })

    @Test
    fun `менший прямокутник обслуговує вже зібраний більший`() {
        val c = cache()
        val first = graph(1)
        val got1 = c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { first }
        assertSame(first, got1)

        // Перебудова при сході: коридор майже той самий, але вужчий.
        val got2 = c.graphFor(50.02, 30.02, 50.08, 30.08, revision = 7) {
            throw AssertionError("не можна читати сховище вдруге для вміщеного коридору")
        }
        assertSame(first, got2)
        assertEquals(1L, c.hits)
        assertEquals(1L, c.misses)
    }

    @Test
    fun `ширший прямокутник читається наново`() {
        val c = cache()
        val first = graph(1)
        c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { first }
        val second = graph(2)
        // На північ від кешованого — там доріг у памʼяті немає.
        val got = c.graphFor(50.0, 30.0, 50.2, 30.1, revision = 7) { second }
        assertSame(second, got)
    }

    @Test
    fun `нові тайли скасовують кеш, хоч би коридор збігався`() {
        val c = cache()
        val old = graph(1)
        c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { old }
        val fresh = graph(2)
        val got = c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 8) { fresh }
        assertSame("після запису в мережу граф мусить зібратись наново", fresh, got)
    }

    @Test
    fun `старий кеш не живе вічно`() {
        val c = cache()
        val old = graph(1)
        c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { old }
        now += 60_001
        val fresh = graph(2)
        assertSame(fresh, c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { fresh })
    }

    @Test
    fun `«не знаю» не кешується`() {
        val c = cache()
        assertNull(c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { null })
        val fresh = graph(3)
        // Наступний виклик мусить спробувати ЗНОВУ, а не віддати порожнечу.
        assertNotNull(c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { fresh })
    }

    @Test
    fun `очищення забуває все`() {
        val c = cache()
        val first = graph(1)
        c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { first }
        c.clear()
        val second = graph(2)
        assertSame(second, c.graphFor(50.0, 30.0, 50.1, 30.1, revision = 7) { second })
    }
}
