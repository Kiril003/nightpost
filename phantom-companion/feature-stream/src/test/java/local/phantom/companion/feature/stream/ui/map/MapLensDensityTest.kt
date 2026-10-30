package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.feature.stream.ui.map.atlas.DensityBudget
import local.phantom.companion.feature.stream.ui.map.atlas.LensFrame
import local.phantom.companion.feature.stream.ui.map.atlas.LensRules
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.icons.marks.MarkKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-2 — бюджет, який нарешті кусається.
 *
 * Дві речі доводяться тут і більше ніде: що з екрана зникає САМЕ зайве (а не
 * випадкові точки), і що результат детермінований — інакше два кадри з тими
 * самими даними показували б різні місця, і мапа почала б мигтіти.
 */
class MapLensDensityTest {

    private var nextId = 1L

    private fun poi(
        sub: String,
        category: String = "amenity",
        name: String = sub,
        tags: Map<String, String> = emptyMap(),
    ) = PoiEntity(
        id = nextId++,
        lat = 50.45,
        lon = 30.52,
        name = name,
        category = category,
        subcategory = sub,
        tags = tags,
    )

    /** Сховище — це `amenity=shelter` ПЛЮС заява про захист; без неї це навіс. */
    private fun bombShelter() =
        poi("shelter", name = "Сховище", tags = mapOf("amenity" to "shelter", "shelter_type" to "bomb_shelter"))

    private fun canopy() =
        poi("shelter", name = "Альтанка", tags = mapOf("amenity" to "shelter"))

    // ── чистий трим ───────────────────────────────────────────────────────

    @Test
    fun `трим бере менші ключі й лишає порядок джерела`() {
        val items = listOf(3, 1, 2, 0, 5)
        assertEquals(listOf(1, 0), DensityBudget.trim(items, 2) { it })
    }

    @Test
    fun `рівні ключі розводить порядок джерела, і він стабільний`() {
        val items = listOf("a", "b", "c", "d")
        val once = DensityBudget.trim(items, 2) { 7 }
        val twice = DensityBudget.trim(items, 2) { 7 }
        assertEquals(listOf("a", "b"), once)
        assertEquals(once, twice)
    }

    @Test
    fun `стеля і нуль — два різні «не рахуй»`() {
        val items = listOf(1, 2, 3)
        assertEquals(items, DensityBudget.trim(items, LensFrame.UNLIMITED) { it })
        assertEquals(emptyList<Int>(), DensityBudget.trim(items, 0) { it })
        assertEquals(items, DensityBudget.trim(items, 99) { it })
    }

    // ── хто виживає в тісноті ─────────────────────────────────────────────

    @Test
    fun `у тісноті аптека витісняє крамницю в кожному режимі`() {
        val shops = List(30) { poi("supermarket", "shop") }
        val pharmacy = poi("pharmacy")
        for (scene in SceneMode.entries) {
            val kept = PlaceLens.select(shops + pharmacy, scene, zoom = 17.0, budget = 3)
            assertTrue("$scene викинув аптеку", kept.any { it.subcategory == "pharmacy" })
        }
    }

    @Test
    fun `за кермом лишається пальне і зникає кавʼярня-сусідка`() {
        val fuel = poi("fuel")
        val cafes = List(20) { poi("cafe") }
        val kept = PlaceLens.select(
            listOf(fuel) + cafes,
            SceneMode.DRIVE,
            zoom = 15.0,
            budget = LensRules.DRIVE_PLACES,
        )
        assertEquals(LensRules.DRIVE_PLACES, kept.size)
        assertTrue(kept.contains(fuel))
    }

    @Test
    fun `за кермом вивіски не пускають узагалі, а лікарню пускають`() {
        assertFalse(PlaceLens.admits(SceneMode.DRIVE, poi("supermarket", "shop")))
        assertFalse(PlaceLens.admits(SceneMode.DRIVE, poi("hotel")))
        assertTrue(PlaceLens.admits(SceneMode.DRIVE, poi("fuel")))
        assertTrue(PlaceLens.admits(SceneMode.DRIVE, poi("checkpoint", "barrier")))
        assertTrue("критичне не викидає жоден режим", PlaceLens.admits(SceneMode.DRIVE, poi("hospital")))
    }

    @Test
    fun `під сиреною лишається стійке — вода, ліки, укриття, лікарня`() {
        listOf("drinking_water", "pharmacy", "hospital").forEach {
            assertTrue(it, PlaceLens.admits(SceneMode.THREAT, poi(it)))
        }
        assertTrue("сховище", PlaceLens.admits(SceneMode.THREAT, bombShelter()))
        listOf("cafe", "supermarket", "hotel", "fuel").forEach {
            assertFalse(it, PlaceLens.admits(SceneMode.THREAT, poi(it)))
        }
    }

    /**
     * `amenity=shelter` в OSM — це навіс. Тридцять дві альтанки київського
     * пакета трималися на склі під сиреною в найвищому ярусі, поки ярус читав
     * підкатегорію й не дивився в теги.
     */
    @Test
    fun `навіс не тримається на склі під сиреною`() {
        assertFalse("альтанка лишилась критичною", PlaceLens.admits(SceneMode.THREAT, canopy()))
        assertTrue(
            "сховище опустилось разом із навісом",
            PlaceLens.rank(SceneMode.THREAT, bombShelter(), zoom = 16.0) <
                PlaceLens.rank(SceneMode.THREAT, canopy(), zoom = 16.0),
        )
    }

    @Test
    fun `огляд не забирає нічого — жодної видимої зміни поза режимами`() {
        val all = listOf(poi("cafe"), poi("supermarket", "shop"), poi("fuel"), poi("hospital"))
        for (scene in listOf(SceneMode.CALM, SceneMode.EXPLORE, SceneMode.GUIDE)) {
            assertEquals(all, PlaceLens.select(all, scene, zoom = 17.0))
        }
    }

    /** Правило «ніколи не заглушка» лишається старшим за будь-який режим. */
    @Test
    fun `порожні й генеричні назви не пускає жоден режим`() {
        val junk = listOf(
            poi("cafe", name = "  "),
            poi("cafe", name = "Unknown"),
            poi("parking", name = "parking"),
        )
        for (scene in SceneMode.entries) {
            assertTrue(scene.name, PlaceLens.select(junk, scene, zoom = 17.0).isEmpty())
        }
    }

    // ── підняття класу режимом ────────────────────────────────────────────

    @Test
    fun `пішки сходи виживають там, де звичайне місце вже ні`() {
        val ordinary = List(20) { poi("supermarket", "shop") }
        val stairs = poi("steps", "highway")
        val kept = PlaceLens.select(ordinary + stairs, SceneMode.WALK, zoom = 17.0, budget = 5)
        assertTrue(kept.contains(stairs))
        assertFalse(
            "поза пішим режимом сходи не мають переваги",
            PlaceLens.select(ordinary + stairs, SceneMode.EXPLORE, zoom = 17.0, budget = 5)
                .contains(stairs),
        )
    }

    @Test
    fun `підняття пішохідних меблів — про масштаб, а не про клас`() {
        val stairs = poi("steps", "highway")
        assertEquals(0, PlaceLens.rank(SceneMode.WALK, stairs, PlaceLens.ON_FOOT_MIN_ZOOM))
        assertTrue(PlaceLens.rank(SceneMode.WALK, stairs, PlaceLens.ON_FOOT_MIN_ZOOM - 1.0) > 0)
    }

    @Test
    fun `у транспорті зупинка виживає поперед крамниці`() {
        val shops = List(20) { poi("supermarket", "shop") }
        val stop = poi("bus_stop", "highway")
        val kept = PlaceLens.select(shops + stop, SceneMode.TRANSIT, zoom = 15.0, budget = 4)
        assertTrue(kept.contains(stop))
    }

    // ── знаки подій ───────────────────────────────────────────────────────

    @Test
    fun `за кермом дворові ворота геть, а блокпост лишається`() {
        assertFalse(EventLens.admits(SceneMode.DRIVE, MarkKind.GATE))
        listOf(MarkKind.CHECKPOINT, MarkKind.CRASH, MarkKind.CAMERA)
            .forEach { assertTrue(it.name, EventLens.admits(SceneMode.DRIVE, it)) }
    }

    @Test
    fun `поза кермом жоден клас знака не ховається`() {
        for (scene in SceneMode.entries - SceneMode.DRIVE) {
            MarkKind.entries.forEach {
                assertTrue("$scene/$it", EventLens.admits(scene, it))
            }
        }
    }

    @Test
    fun `під сиреною укриття стоїть попереду решти знаків`() {
        val shelter = EventLens.rank(SceneMode.THREAT, MarkKind.SHELTER)
        MarkKind.entries.filterNot { it == MarkKind.SHELTER }.forEach {
            assertTrue("$it обігнав укриття", EventLens.rank(SceneMode.THREAT, it) > shelter)
        }
        assertEquals(
            "поза загрозою укриття не привілейоване",
            EventLens.rank(SceneMode.EXPLORE, MarkKind.CRASH),
            EventLens.rank(SceneMode.EXPLORE, MarkKind.SHELTER),
        )
    }

    // ── голос стежок ──────────────────────────────────────────────────────

    @Test
    fun `пішки стежка голосніша, а поза цим — байт-у-байт базова рампа`() {
        assertEquals(PATH_ALPHA_BASE.size, PATH_ALPHA_WALK.size)
        PATH_ALPHA_BASE.zip(PATH_ALPHA_WALK).forEach { (base, walk) ->
            assertEquals("рампи розійшлись по зумах", base.first, walk.first, 0.0)
            assertTrue("пішки стежка мусить бути голоснішою", walk.second > base.second)
            assertTrue("альфа не буває більшою за одиницю", walk.second <= 1f)
        }
    }
}
