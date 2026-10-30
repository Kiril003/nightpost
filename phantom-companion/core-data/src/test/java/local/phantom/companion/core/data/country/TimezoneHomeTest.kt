package local.phantom.companion.core.data.country

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Test

/** FR-7 (ніч 09.10): телефони в Чехії, мапа відкривалась на Берліні. */
class TimezoneHomeTest {

    @Before
    fun fresh() = CountryPackRegistry.reset()

    @After
    fun clean() = CountryPackRegistry.reset()

    @Test
    fun `no fix, Prague timezone, Europe pack remembered - the map opens on Czechia, not Berlin`() {
        CountryPackRegistry.restore(rememberedId = EuropePack.id, manual = null)
        val home = CountryPackRegistry.firstHome("Europe/Prague")
        assertEquals(50.08, home.lat, 0.01)
        assertEquals(14.44, home.lon, 0.01)
        assertEquals(TimezoneHome.COUNTRY_ZOOM, home.zoom, 0.0)
    }

    @Test
    fun `fresh install without any pack - the timezone country, not the globe`() {
        val home = CountryPackRegistry.firstHome("Europe/Kyiv")
        assertEquals(50.45, home.lat, 0.01)
    }

    @Test
    fun `a country picked by hand wins over the timezone`() {
        CountryPackRegistry.setOverride(UkrainePack.id)
        assertEquals(UkrainePack.home, CountryPackRegistry.firstHome("Europe/Prague"))
    }

    @Test
    fun `unknown timezone - the old order, pack then globe`() {
        assertEquals(WORLD_VIEW, CountryPackRegistry.firstHome("Pacific/Nowhere"))
        CountryPackRegistry.restore(rememberedId = EuropePack.id, manual = null)
        assertEquals(EuropePack.home, CountryPackRegistry.firstHome(null))
    }
}
