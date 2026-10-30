package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Контракт полиці «поруч»: капсула обіцяє дванадцять карток, і кожен ярлик
 * мусить існувати в [MapDiscovery.all]. Раніше `mapNotNull` мовчки зʼїдав
 * перейменовані категорії — полиця всихала з 12 до 5 без жодного звуку.
 *
 * 14.09: полиця розділена на [NEARBY_CATS_ALL] (повний перелік — саме той
 * контракт, який тримали ці тести) і [nearbyCatsFor] (те, що бачить
 * оператор за кодом країни з `LocalConfiguration` — 10 карток поза
 * Україною: «Укриття» й «Нова Пошта / Укрпошта» ховаються). Контракт на
 * дванадцять лишається на ALL, а не на видиме.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class NearbyCatsTest {

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `повна полиця несе рівно дванадцять карток`() {
        assertEquals(12, NEARBY_CATS_ALL.size)
    }

    @Test
    fun `картки не дублюються`() {
        assertEquals(NEARBY_CATS_ALL.size, NEARBY_CATS_ALL.distinctBy { it.label }.size)
    }

    @Test
    fun `поза Україною полиця ховає Укриття й Укрпошту`() {
        val visible = nearbyCatsFor("DE")
        assertEquals(10, visible.size)
        assertFalse(visible.any { it.label == "Укриття" })
        assertFalse(visible.any { it.label == "Нова Пошта / Укрпошта" })
    }

    @Test
    fun `в Україні полиця лишається повною`() {
        val visible = nearbyCatsFor("UA")
        assertEquals(12, visible.size)
        assertTrue(visible.any { it.label == "Укриття" })
        assertTrue(visible.any { it.label == "Нова Пошта / Укрпошта" })
    }

    @Test
    fun `код країни звіряється без чутливості до регістру`() {
        assertEquals(nearbyCatsFor("ua"), nearbyCatsFor("UA"))
    }

    @Test
    fun `селектори полиці збігаються з тими, що перевіряє NearbyButtonsTest у core-data`() {
        val mirror = listOf(
            "Укриття" to local.phantom.companion.core.data.evac.ShelterTruth.BOMB_SHELTER_SELECTOR,
            "Аптека" to "amenity=pharmacy",
            "АЗС" to "amenity=fuel",
            "Банкомат / Готівка" to "amenity=atm",
            "Супермаркет" to "shop~\"supermarket|convenience\"",
            "Кавʼярня" to "amenity=cafe",
            "Лікарня / Травмпункт" to "amenity~\"hospital|clinic\"",
            "Питна вода / Бювети" to "amenity=drinking_water",
            "Метро та вокзали" to "railway~\"subway_entrance|station|halt\"|amenity=bus_station",
            "Нова Пошта / Укрпошта" to "amenity=post_office",
            "Поліція" to "amenity=police",
            "Підземний паркінг" to "parking=underground",
        )
        assertEquals(mirror, NEARBY_CATS_ALL.map { it.label to it.selector })
    }
}
