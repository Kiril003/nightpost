package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.dossier.dossierTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapHomeChromeTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        MapText.bind(context)
    }

    @Test
    fun `home chrome mounts category chips and single bottom owner`() {
        val ui = MapUiState()
        assertEquals(MapBottomOwner.HOME_PEEK, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        MapBottomSurface.open(ui, MapBottomOwner.SEARCH)
        assertEquals(MapBottomOwner.SEARCH, resolveBottomOwner(ui, guiding = false, hasRoute = false))
        assertFalse(MapBottomSurface.answerVisible(answerLive = true, owner = resolveBottomOwner(ui, guiding = false, hasRoute = false)))

        MapBottomSurface.open(ui, MapBottomOwner.LAYERS)
        assertFalse(ui.searchOpen)
        assertEquals(MapBottomOwner.LAYERS, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        MapBottomSurface.open(ui, MapBottomOwner.PEOPLE)
        assertFalse(ui.toolsExpanded)
        assertEquals(MapBottomOwner.PEOPLE, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        MapBottomSurface.open(ui, MapBottomOwner.HOME_PEEK)
        ui.dossierTarget = AroundPlace(name = "Kava", lat = 50.45, lon = 30.52).dossierTarget()
        assertEquals(MapBottomOwner.PLACE, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        MapBottomSurface.open(ui, MapBottomOwner.HOME_PEEK)
        ui.selectedSavedPlace = PoiEntity(
            id = 1L,
            lat = 50.45,
            lon = 30.52,
            name = "Home",
            category = SavedPlaceCategory.HOME,
            subcategory = null,
            tags = emptyMap(),
        )
        assertEquals(MapBottomOwner.PLACE, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        MapBottomSurface.open(ui, MapBottomOwner.HOME_PEEK)
        assertEquals(MapBottomOwner.ROUTE, resolveBottomOwner(ui, guiding = false, hasRoute = true))
        assertEquals(MapBottomOwner.GUIDANCE, resolveBottomOwner(ui, guiding = true, hasRoute = true))
        assertEquals(MapBottomOwner.HOME_PEEK, resolveBottomOwner(ui, guiding = false, hasRoute = false))

        val nowMs = 1_700_000_000_000L
        val cast = listOf(
            formatTrayPerson(
                id = "peer-iva",
                name = "Iva",
                meters = 320.0,
                atMs = nowMs - 30_000L,
                fresh = true,
                nowMs = nowMs,
            ),
            formatTrayPerson(
                id = "peer-daniel",
                name = "Daniel",
                meters = 1_200.0,
                atMs = nowMs - 15 * 60_000L,
                fresh = false,
                nowMs = nowMs,
            ),
        )

        val homeChrome = resolveHomeChrome(
            ui = ui,
            guiding = false,
            hasRoute = false,
            nearbyActive = null,
            pannedSinceSearch = false,
            trayPeople = cast,
        )
        assertEquals(MapBottomOwner.HOME_PEEK, homeChrome.bottomOwner)
        assertFalse(homeChrome.instrumentPlateVisible)
        assertTrue(homeChrome.searchBarVisible)
        assertTrue(TAG_MAP_HOME_CHIPS in homeChrome.mountedTags)
        assertTrue(TAG_MAP_LOCATE_FAB in homeChrome.mountedTags)
        assertTrue(TAG_MAP_PEOPLE_TRAY in homeChrome.mountedTags)
        assertFalse(TAG_MAP_SEARCH_AREA_CHIP in homeChrome.mountedTags)
        assertEquals(listOf("Iva", "Daniel"), homeChrome.trayPeople.map { it.name })
        assertEquals("4m", homeChrome.trayPeople[0].status)
        assertEquals("15m", homeChrome.trayPeople[1].status)
        assertEquals(6, homeChrome.categoryChips.size)

        val pannedChrome = resolveHomeChrome(
            ui = ui,
            guiding = false,
            hasRoute = false,
            nearbyActive = context.getString(R.string.map_chip_coffee),
            pannedSinceSearch = true,
            trayPeople = cast,
        )
        assertTrue(pannedChrome.searchAreaVisible)
        assertTrue(TAG_MAP_SEARCH_AREA_CHIP in pannedChrome.mountedTags)

        MapBottomSurface.open(ui, MapBottomOwner.SEARCH)
        val searchChrome = resolveHomeChrome(
            ui = ui,
            guiding = false,
            hasRoute = false,
            nearbyActive = null,
            pannedSinceSearch = false,
            trayPeople = cast,
        )
        assertEquals(MapBottomOwner.SEARCH, searchChrome.bottomOwner)
        assertFalse(searchChrome.searchBarVisible)
        assertFalse(TAG_MAP_PEOPLE_TRAY in searchChrome.mountedTags)
    }

    @Test
    @Config(qualifiers = "en")
    fun `home strings resolve in English`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Search places and people", ctx.getString(R.string.map_home_search_hint))
        assertEquals(
            listOf("Coffee", "Food", "Tonight", "Groceries", "Pharmacy", "Transit"),
            HOME_CATEGORY_CHIPS.map { ctx.getString(it.labelRes) },
        )
        assertEquals("Search this area", ctx.getString(R.string.map_search_this_area))
        assertEquals("Plans", ctx.getString(R.string.map_tray_plans))
        assertEquals("Meet", ctx.getString(R.string.map_tray_meet))
        assertEquals("Allow location", ctx.getString(R.string.map_state_allow_location))
    }

    @Test
    @Config(qualifiers = "uk")
    fun `home strings resolve in Ukrainian`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Пошук місць і людей", ctx.getString(R.string.map_home_search_hint))
        assertEquals(
            listOf("Кава", "Їжа", "Сьогодні ввечері", "Продукти", "Аптека", "Транспорт"),
            HOME_CATEGORY_CHIPS.map { ctx.getString(it.labelRes) },
        )
        assertEquals("Шукати в цій області", ctx.getString(R.string.map_search_this_area))
        assertEquals("Плани", ctx.getString(R.string.map_tray_plans))
        assertEquals("Зустріч", ctx.getString(R.string.map_tray_meet))
        assertEquals("Дозволити геопозицію", ctx.getString(R.string.map_state_allow_location))
    }

    @Test
    @Config(qualifiers = "cs")
    fun `home strings resolve in Czech`() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Hledat místa a lidi", ctx.getString(R.string.map_home_search_hint))
        assertEquals(
            listOf("Káva", "Jídlo", "Dnes večer", "Potraviny", "Lékárna", "Doprava"),
            HOME_CATEGORY_CHIPS.map { ctx.getString(it.labelRes) },
        )
        assertEquals("Hledat v této oblasti", ctx.getString(R.string.map_search_this_area))
        assertEquals("Plány", ctx.getString(R.string.map_tray_plans))
        assertEquals("Setkání", ctx.getString(R.string.map_tray_meet))
        assertEquals("Povolit polohu", ctx.getString(R.string.map_state_allow_location))
    }
}
