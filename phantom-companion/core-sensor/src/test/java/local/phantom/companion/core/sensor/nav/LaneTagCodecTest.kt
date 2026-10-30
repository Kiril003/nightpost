package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Смуги мусять пережити дорогу від пекарні до скла.
 *
 * ЩО ЦЕ СТЕРЕЖЕ. Підказка смуг офлайн не існувала — і причина була глибша за
 * «тега немає в переліку». Формат тегів тримається на `k=v|k=v`, а `turn:lanes`
 * записує смуги саме через вертикальну риску: `left|through;right`. Старий
 * кодер такі значення **мовчки викидав**, тож смугам не було куди поміститись:
 * додати ключ у whitelist було б замало, значення однаково не доїхало б.
 *
 * Тепер риска екранується в `%7C`. Ці тести стережуть три речі, кожна з яких
 * ламається окремо: що значення доїжджає цілим, що старі пакети читаються без
 * змін, і що ключ, який ми не веземо, не зʼявляється нізвідки.
 */
class LaneTagCodecTest {

    @Test
    fun `значення зі смугами доїжджає цілим`() {
        val tags = mapOf("highway" to "primary", "turn:lanes" to "left|through;right")
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(tags))
        assertEquals("left|through;right", back["turn:lanes"])
        assertEquals("primary", back["highway"])
    }

    @Test
    fun `риска не розриває сусідні теги`() {
        // Саме тут ламався б формат: після сирої риски «primary» стало б
        // окремою парою-сміттям, а клас дороги — брехнею.
        val tags = mapOf(
            "turn:lanes" to "left|left|through",
            "highway" to "secondary",
            "maxspeed" to "50",
        )
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(tags))
        assertEquals(3, back.size)
        assertEquals("secondary", back["highway"])
        assertEquals("50", back["maxspeed"])
        assertEquals("left|left|through", back["turn:lanes"])
    }

    @Test
    fun `старі пакети читаються без змін`() {
        // Гарантія «не зіпсувати те, що вже в людей». У старих пакетах
        // послідовності %7C немає, тож розекранування там — порожня дія.
        val old = "highway=residential|surface=asphalt|maxspeed=50"
        val back = RoadWayCodec.decodeTags(old)
        assertEquals("residential", back["highway"])
        assertEquals("asphalt", back["surface"])
        assertEquals("50", back["maxspeed"])
        assertEquals(3, back.size)
    }

    @Test
    fun `кількість смуг і вказівник теж везуться`() {
        val tags = mapOf("lanes" to "3", "destination" to "Житомир")
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(tags))
        assertEquals("3", back["lanes"])
        assertEquals("Житомир", back["destination"])
    }

    @Test
    fun `ключ поза переліком не проїжджає`() {
        // Перелік — не смак: мережа доріг і так найбільше, що зберігає
        // пристрій, а повний набір тегів OSM необмежений.
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(mapOf("wikipedia" to "uk:Хрещатик")))
        assertNull(back["wikipedia"])
        assertTrue(back.isEmpty())
    }

    @Test
    fun `значення зі знаком рівності досі пропускається`() {
        // Його ні на що замінити, не зруйнувавши саму пару k=v. Це правило
        // лишилось, і послаблювати його разом із рискою не можна.
        val back = RoadWayCodec.decodeTags(RoadWayCodec.encodeTags(mapOf("destination" to "A=B")))
        assertNull(back["destination"])
    }
}
