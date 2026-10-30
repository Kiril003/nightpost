package local.phantom.companion.core.geo.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «поділитись місцем» вело на сайт OSM — вердикт тестувальника 11.09.
 *
 * Лист ішов трьома рядками, і єдиним, що месенджер робив клікабельним, було
 * посилання на openstreetmap.org. Продукт із власною офлайн-мапою відправляв
 * співрозмовника в браузер.
 */
class MapLinkTest {

    private val kyiv = MapLink.Point(50.4501, 30.5234, "Укриття на Хрещатику")

    @Test
    fun `власне посилання стоїть перед чужим сайтом`() {
        val text = MapLink.shareText(kyiv)
        val own = text.indexOf("phantom://map")
        val geo = text.indexOf("geo:")
        val osm = text.indexOf("openstreetmap.org")
        assertTrue("нашого посилання в листі немає", own >= 0)
        assertTrue("geo: мусить бути запасним, а не першим", own < geo)
        assertTrue("сайт OSM мусить бути останнім", geo < osm)
    }

    @Test
    fun `власне посилання читається назад разом із назвою`() {
        val back = MapLink.parse(MapLink.own(kyiv))
        assertEquals(kyiv.lat, back?.lat)
        assertEquals(kyiv.lon, back?.lon)
        assertEquals(kyiv.label, back?.label)
    }

    @Test
    fun `приймаємо geo від чужої мапи`() {
        val p = MapLink.parse("geo:50.4501,30.5234")
        assertEquals(50.4501, p?.lat)
        assertEquals(30.5234, p?.lon)
    }

    @Test
    fun `приймаємо geo з позначкою і назвою`() {
        val p = MapLink.parse("geo:0,0?q=50.4501,30.5234(Склад)")
        assertEquals(50.4501, p?.lat)
        assertEquals(30.5234, p?.lon)
        assertEquals("Склад", p?.label)
    }

    @Test
    fun `приймаємо посилання openstreetmap`() {
        val p = MapLink.parse(
            "https://www.openstreetmap.org/?mlat=50.4501&mlon=30.5234#map=17/50.4501/30.5234",
        )
        assertEquals(50.4501, p?.lat)
        assertEquals(30.5234, p?.lon)
    }

    @Test
    fun `приймаємо камеру osm без позначки`() {
        assertNotNull(MapLink.parse("https://www.openstreetmap.org/#map=17/49.84/24.03"))
    }

    @Test
    fun `у листі з трьох рядків перемагає наш`() {
        val p = MapLink.parseFirst(MapLink.shareText(kyiv))
        assertEquals(kyiv.label, p?.label)
    }

    @Test
    fun `чужий текст не стає місцем`() {
        assertNull(MapLink.parse(null))
        assertNull(MapLink.parse("   "))
        assertNull(MapLink.parse("https://example.com/?mlat=1&mlon=2"))
        assertNull(MapLink.parse("geo:отут,десь"))
    }

    @Test
    fun `координати поза глобусом відкидаються`() {
        assertNull(MapLink.parse("geo:91.0,30.0"))
        assertNull(MapLink.parse("geo:50.0,181.0"))
        assertNull(MapLink.parse("phantom://map?lat=50.0"))
    }

    @Test
    fun `debug-схема теж відкривається`() {
        assertNotNull(MapLink.parse("phantom-debug://map?lat=50.4501&lon=30.5234"))
    }
}
