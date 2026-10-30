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

/** Полиця першого екрана мусить знати, у якій країні стоїть телефон. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "uk")
class MapDiscoveryStanceTest {

    @Before
    fun bindText() {
        MapText.bind(ApplicationProvider.getApplicationContext())
    }

    @Test
    fun `в Україні полиця лишається восьмиркою`() {
        assertEquals(MapDiscovery.shortlist, MapDiscovery.shortlistFor(showsUkraineWartime = true))
    }

    @Test
    fun `поза Україною зникають укриття й генератори`() {
        val shelf = MapDiscovery.shortlistFor(showsUkraineWartime = false)
        assertTrue("полиця не може спорожніти", shelf.isNotEmpty())
        assertFalse("«Укриття» лишилось", shelf.any { it.label == "Укриття" })
        assertFalse("«Генератори» лишились", shelf.any { it.label == "Генератори" })
        assertTrue("аптека потрібна скрізь", shelf.any { it.label.contains("Аптек") })
    }

    @Test
    fun `швидкий ряд теж чистіший поза Україною`() {
        val quick = MapDiscovery.quickFor(showsUkraineWartime = false)
        assertFalse(quick.any { it.label.contains("Незламності") })
        assertFalse(quick.any { it.label == "Метро (Укриття)" })
        assertTrue(quick.size < MapDiscovery.quick.size)
    }

    @Test
    fun `позначено рівно ті категорії, що є реєстром лише в Україні`() {
        assertEquals(
            listOf("Генератори", "Укриття", "Метро (Укриття)", "Пункт незламності / Хаб"),
            MapDiscovery.all.filter { it.ukraineWartime }.map { it.label },
        )
    }
}
