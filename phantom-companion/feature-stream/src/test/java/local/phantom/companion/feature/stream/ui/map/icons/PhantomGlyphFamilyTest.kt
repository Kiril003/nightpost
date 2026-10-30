package local.phantom.companion.feature.stream.ui.map.icons

import local.phantom.companion.core.data.places.SavedPlaceCategory
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.feature.stream.ui.map.ThreatType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Родина знаків як контракт, а не як набір гарних фігур.
 *
 * Ці перевірки ловлять три речі, які інакше видно лише на склі пристрою й лише
 * якщо саме той пін трапиться: гліф, який нічого не малює; гліф, що виліз за
 * живе поле й ліг на кільце; і тег, який тихо провалився у «просто місце».
 */
class PhantomGlyphFamilyTest {

    private fun record(glyph: PhantomGlyph) = RecordingGlyphPen().also { glyph.drawOn(it) }

    @Test
    fun `every glyph in the registry actually draws something`() {
        PhantomGlyph.entries.forEach { glyph ->
            assertTrue("$glyph нічого не малює — це пін без знака", record(glyph).marks > 0)
        }
    }

    /**
     * Живе поле — не педантизм: гліф вписано в коробку, чия діагональ ледве
     * влазить у кільце піна. Знак, що вийшов за 2..22, лягає на кільце і
     * зчитується як бруд.
     */
    @Test
    fun `no glyph leaves the live field of the grid`() {
        val eps = 0.01f
        PhantomGlyph.entries.forEach { glyph ->
            val pen = record(glyph)
            assertTrue(
                "$glyph: x ${pen.minX}..${pen.maxX} поза ${GlyphGrid.MIN_U}..${GlyphGrid.MAX_U}",
                pen.minX >= GlyphGrid.MIN_U - eps && pen.maxX <= GlyphGrid.MAX_U + eps,
            )
            assertTrue(
                "$glyph: y ${pen.minY}..${pen.maxY} поза ${GlyphGrid.MIN_U}..${GlyphGrid.MAX_U}",
                pen.minY >= GlyphGrid.MIN_U - eps && pen.maxY <= GlyphGrid.MAX_U + eps,
            )
        }
    }

    /** Знак, що займає чверть коробки, на 14dp — це крапка. */
    @Test
    fun `every glyph fills the grid it was given`() {
        PhantomGlyph.entries.forEach { glyph ->
            val pen = record(glyph)
            val w = pen.maxX - pen.minX
            val h = pen.maxY - pen.minY
            assertTrue("$glyph займає лише ${w}×${h} з 20×20", maxOf(w, h) >= 12f)
        }
    }

    /* ── Таксономія: жодного хвоста, що впав у «просто місце» ────────────── */

    /**
     * Теги, які реально приходять з фідів PHANTOM: усе, що вміє віддати
     * `MapDiscovery` своїми селекторами, плюс те, що Overpass кладе в
     * `subcategory` на звичайному міському екрані. Кожен ЗОБОВʼЯЗАНИЙ мати
     * власну родину, а не дефолтний ромб.
     */
    private val mustResolve = listOf(
        "generator", "substation", "transformer",
        "drinking_water", "water_well", "spring",
        "shelter", "subway_entrance", "defibrillator", "fire_station", "police",
        "pharmacy", "hospital", "clinic", "ambulance_station", "dentist", "veterinary",
        "post_office", "parcel_locker", "atm", "bank",
        "bus_stop", "platform", "bus_station", "station", "halt", "fuel", "parking",
        "car_repair", "tyres", "charging_station", "taxi",
        "supermarket", "convenience", "marketplace", "bakery", "hardware", "doityourself",
        "cafe", "restaurant", "fast_food", "bar", "pub",
        "community_centre", "government", "townhall", "courthouse", "library", "museum",
        "school", "university", "kindergarten", "place_of_worship",
        "park", "garden", "playground", "peak", "viewpoint", "attraction",
        "hotel", "hostel", "guest_house", "toilets",
        "barrier", "gate", "lift_gate", "bollard", "checkpoint", "speed_camera",
        "entrance", "elevator", "escalator", "steps",
    )

    @Test
    fun `every tag the app can ask for lands on a family, not on the fallback`() {
        mustResolve.forEach { tag ->
            val family = PoiGlyphTable.resolve(category = null, subcategory = tag)
            assertTrue(
                "«$tag» провалився у дефолтний ромб — таксономія має дірку",
                family != PoiGlyphTable.DEFAULT,
            )
        }
    }

    @Test
    fun `an unknown tag still gets a drawn glyph, never a fallback font`() {
        val family = PoiGlyphTable.resolve(category = "other", subcategory = "vending_machine")
        assertEquals(PhantomGlyph.PLACE, family.glyph)
        assertTrue(record(family.glyph).marks > 0)
    }

    /** Категорія — остання зупинка перед ромбом, і вона теж має малюватись. */
    @Test
    fun `every category fallback resolves to a drawn glyph`() {
        PoiGlyphTable.byCategory.forEach { (key, family) ->
            assertTrue("категорія «$key» без знака", record(family.glyph).marks > 0)
        }
    }

    /**
     * Ярус вирішує не лише колір, а й чергу на розрідження: ключ, що потрапив
     * би в обидві множини, зробив би сортування недетермінованим.
     */
    @Test
    fun `the thinning tiers are disjoint and neither is empty`() {
        assertTrue(PoiGlyphTable.criticalKeys.isNotEmpty())
        assertTrue(PoiGlyphTable.sustainKeys.isNotEmpty())
        assertTrue(
            "ключ у двох ярусах: ${PoiGlyphTable.criticalKeys intersect PoiGlyphTable.sustainKeys}",
            (PoiGlyphTable.criticalKeys intersect PoiGlyphTable.sustainKeys).isEmpty(),
        )
        assertTrue("укриття мусить бути критичним", "shelter" in PoiGlyphTable.criticalKeys)
        assertTrue("АЗС мусить переживати розрідження", "fuel" in PoiGlyphTable.sustainKeys)
    }

    /* ── Інші реєстри мапи на тій самій родині ───────────────────────────── */

    @Test
    fun `every threat type wears its own drawn silhouette`() {
        val used = mutableMapOf<PhantomGlyph, ThreatType>()
        ThreatType.entries.forEach { type ->
            val glyph = type.glyph()
            assertTrue("$type без знака", record(glyph).marks > 0)
            val clash = used.put(glyph, type)
            assertTrue("$type і $clash носять один силует", clash == null)
        }
    }

    @Test
    fun `every road hazard wears its own drawn silhouette`() {
        val used = mutableMapOf<PhantomGlyph, HazardType>()
        HazardType.entries.forEach { type ->
            val glyph = type.glyph()
            assertTrue("$type без знака", record(glyph).marks > 0)
            val clash = used.put(glyph, type)
            assertTrue("$type і $clash носять один силует", clash == null)
        }
    }

    @Test
    fun `every transit mode and saved category resolves to a drawn glyph`() {
        TransitMode.entries.forEach {
            assertTrue("$it без знака", record(it.glyph()).marks > 0)
        }
        SavedPlaceCategory.ALL.forEach {
            assertTrue("«$it» без знака", record(savedPlaceGlyph(it)).marks > 0)
        }
        assertEquals(PhantomGlyph.SHELTER, savedPlaceGlyph(SavedPlaceCategory.SHELTER))
        assertEquals(PhantomGlyph.STAR, savedPlaceGlyph(SavedPlaceCategory.FAVOURITE))
    }
}
