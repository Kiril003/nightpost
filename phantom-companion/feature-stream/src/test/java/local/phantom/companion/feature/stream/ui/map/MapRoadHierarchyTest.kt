package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The dark basemap's road ladder — the thing `applyTacticalStyling` used to
 * destroy by painting motorway, trunk, residential and footpath in one grey
 * and recolouring every casing to match its own core.
 *
 * Every colour is READ from [MapTerrainLayers][roadInk], never re-typed.
 * Method is MapSymbolContrastTest's: WCAG relative luminance over the grounds
 * this app actually paints under a road.
 */
class MapRoadHierarchyTest {

    // The grounds a road line composites against on the dark basemap.
    //
    // READ from the palette, never re-typed. They used to be four hand-copied
    // hexes with a comment claiming they were «what this app repaints» — and
    // the moment applyGroundCartography gave water and forest real colour,
    // those four numbers guarded a map that no longer existed. A stale ground
    // is worse than no ground: it goes green forever.
    private val ground = groundPalette(isDark = true)
    private val bg = ground.land and 0xFFFFFF
    private val buildingLightest = 0x24242A
    private val grounds = buildMap {
        put("земля ${hex(ground.land)}", bg)
        put("вода ${hex(ground.water)}", ground.water and 0xFFFFFF)
        put("глибока вода ${hex(ground.waterDeep)}", ground.waterDeep and 0xFFFFFF)
        put("ліс ${hex(ground.wood)}", ground.wood and 0xFFFFFF)
        put("трава ${hex(ground.grass)}", ground.grass and 0xFFFFFF)
        put("поле ${hex(ground.farmland)}", ground.farmland and 0xFFFFFF)
        put("болото ${hex(ground.wetland)}", ground.wetland and 0xFFFFFF)
        put("пісок ${hex(ground.sand)}", ground.sand and 0xFFFFFF)
        put("камінь ${hex(ground.rock)}", ground.rock and 0xFFFFFF)
        put("лід ${hex(ground.ice)}", ground.ice and 0xFFFFFF)
        put("забудова ${hex(ground.residential)}", ground.residential and 0xFFFFFF)
        put("торгівля ${hex(ground.commercial)}", ground.commercial and 0xFFFFFF)
        put("промзона ${hex(ground.industrial)}", ground.industrial and 0xFFFFFF)
        put("цвинтар ${hex(ground.cemetery)}", ground.cemetery and 0xFFFFFF)
        put("лікарня ${hex(ground.hospital)}", ground.hospital and 0xFFFFFF)
        put("школа ${hex(ground.school)}", ground.school and 0xFFFFFF)
        put("майданчик ${hex(ground.pitch)}", ground.pitch and 0xFFFFFF)
        put("летовище ${hex(ground.aeroway)}", ground.aeroway and 0xFFFFFF)
        put("будинок ${hex(ground.building)}", ground.building and 0xFFFFFF)
        put("найсвітліший тон будинку #24242A", buildingLightest)
    }

    private val floor = 3.0

    @Test
    fun `Ukraine's national network is on the loudest rung, not beside a farm track`() {
        // Measured on the Ukraine OSM extract: trunk = 16 936, motorway = 33.
        // A ladder that reserves the top rung for `motorway` gives this country
        // 33 loud roads and hides the network it actually drives on.
        assertEquals(RoadRank.NATIONAL, roadRankOf("trunk"))
        assertEquals(RoadRank.NATIONAL, roadRankOf("motorway"))
        assertEquals(RoadRank.REGIONAL, roadRankOf("primary"))
        assertEquals(RoadRank.REGIONAL, roadRankOf("secondary"))
        listOf("tertiary", "minor", "service", "track", "path").forEach {
            assertEquals("$it belongs on the quiet rung", RoadRank.LOCAL, roadRankOf(it))
        }
        assertTrue(
            "trunk and track share an ink — the hierarchy is flat again",
            roadInk(roadRankOf("trunk")) != roadInk(roadRankOf("track")),
        )
    }

    @Test
    fun `all three rungs read on every ground the app puts under a road`() {
        RoadRank.entries.forEach { rank ->
            grounds.forEach { (name, ground) ->
                val ink = roadInk(rank)
                assertTrue(
                    "$rank ${hex(ink)} on $name is ${ratio(ink, ground)}:1",
                    contrast(ink, ground) >= floor,
                )
            }
        }
    }

    @Test
    fun `the old single grey did NOT read, whatever its comment claimed`() {
        // #4E4E57 with a comment promising «~3.5:1». It measures 2.38:1 on the
        // background it was chosen for. Kept as a floor so no rung may sink
        // back to it.
        val flattened = 0x4E4E57
        assertTrue(
            "the flattened grey measured ${ratio(flattened, bg)}:1, not 3.5:1",
            contrast(flattened, bg) < floor,
        )
        RoadRank.entries.forEach { rank ->
            assertTrue(
                "$rank is no brighter than the grey it replaced",
                contrast(roadInk(rank), bg) > contrast(flattened, bg),
            )
        }
    }

    @Test
    fun `each rung is told apart from the one below it`() {
        val step = 1.5
        listOf(
            RoadRank.NATIONAL to RoadRank.REGIONAL,
            RoadRank.REGIONAL to RoadRank.LOCAL,
        ).forEach { (louder, quieter) ->
            val c = contrast(roadInk(louder), roadInk(quieter))
            assertTrue("$louder against $quieter is only ${"%.2f".format(c)}:1", c >= step)
            assertTrue(
                "$louder is not louder than $quieter",
                contrast(roadInk(louder), bg) > contrast(roadInk(quieter), bg),
            )
        }
    }

    @Test
    fun `the casing stays under the core instead of becoming it`() {
        // The defect this file exists for: every casing was set to the same
        // value as its own core, so a road had no edge and two roads meeting
        // had no seam.
        RoadRank.entries.forEach { rank ->
            val core = roadInk(rank)
            assertTrue(
                "$rank core and casing are the same ink",
                core != ROAD_INK_CASING,
            )
            assertTrue(
                "$rank core over its casing is ${ratio(core, ROAD_INK_CASING)}:1",
                contrast(core, ROAD_INK_CASING) >= floor,
            )
        }
    }

    @Test
    fun `colour is not the only thing that says which rung this is`() {
        // Gone under a red filter, in low sun, to a colour-blind crew member.
        assertTrue(
            "the national network is not drawn any wider than a regional road",
            roadWidthScale(RoadRank.NATIONAL) > roadWidthScale(RoadRank.REGIONAL),
        )
        assertTrue(
            "a regional road is not drawn any wider than a farm track",
            roadWidthScale(RoadRank.REGIONAL) > roadWidthScale(RoadRank.LOCAL),
        )
        // Never THINNER than the tiles drew it: a rung that shrinks its own
        // roads buys hierarchy by deleting the bottom of the network.
        RoadRank.entries.forEach {
            assertTrue("$it is drawn thinner than the tiles asked", roadWidthScale(it) >= 1f)
        }
    }

    @Test
    fun `the T-roads between villages are wider than a yard driveway`() {
        // Обласна мережа ділить чорнило з дворовим проїздом (це виміряно й не
        // чіпається), тож єдине, чим вона відрізняється, — ширина. Рівність тут
        // означає, що сітка руху між селами намальована як службовий проїзд.
        assertTrue(
            "tertiary ${roadWidthScaleOf("tertiary")} проти minor ${roadWidthScaleOf("minor")}",
            roadWidthScaleOf("tertiary") > roadWidthScaleOf("minor"),
        )
        assertTrue(
            "tertiary голосніший за обласну primary",
            roadWidthScaleOf("tertiary") < roadWidthScaleOf("primary"),
        )
        listOf("motorway", "trunk", "primary", "secondary", "tertiary", "minor", "service", "track")
            .forEach {
                assertTrue("$it малюється тоншим, ніж просили тайли", roadWidthScaleOf(it) >= 1f)
            }
    }

    @Test
    fun `the casing is scaled by the same multiplier as the core it carries`() {
        // D4: множник ішов лише серцевині (`if (!casing && scaleRoads)`), і на
        // z20 національна серцевина 18×1.5 = 27px ковтала власну облямівку в
        // 22px — дорога з двох ліній ставала однією білою смугою. Компілятор
        // цього не бачить, а на склі це видно лише на великому наближенні.
        val call = source("ui/map/MapGroundBackfill.kt")
            .filter { it.contains("scaleRoadWidth(layer") }
        assertEquals("виклик масштабування ширини зник або роздвоївся", 1, call.size)
        assertFalse(
            "ширину знову отримує лише серцевина: ${call.first().trim()}",
            call.first().contains("casing"),
        )
        assertTrue(
            "масштабування більше не за позначкою TACTICAL_ROADS_MARK — множники складуться",
            call.first().contains("scaleRoads"),
        )
    }

    /* ── День: паперова карта ─────────────────────────────────────────────── */

    // Денна драбина не має нічної розкоші світності: земля світла, і серцевина
    // на ній не може бути ще світлішою. Тому вдень щабель несуть ТРИ сигнали —
    // тепла серцевина, власна облямівка і ширина.
    private val day = groundPalette(isDark = false)
    private val dayLand = day.land and 0xFFFFFF

    @Test
    fun `a daylight road has a body, not just an edge`() {
        // Було: обидві верхні серцевини #FFFFFF на землі #F6F3EC — 1.11:1 і
        // 1.07:1, тобто дорога існувала рівно настільки, наскільки її облямівка.
        assertTrue(
            "національна серцевина ${hex(day.roadNational)} на землі — ${ratio(day.roadNational, dayLand)}:1",
            contrast(day.roadNational, dayLand) >= 1.4,
        )
        listOf(
            "національна" to day.roadNational,
            "обласна" to day.roadRegional,
        ).forEach { (what, core) ->
            val d = distance(core, dayLand)
            assertTrue(
                "$what серцевина ${hex(core)} проти землі — відстань ${"%.1f".format(d)}",
                d >= 60.0,
            )
        }
        assertTrue(
            "національна і обласна серцевини — один колір",
            distance(day.roadNational, day.roadRegional) >= 40.0,
        )
    }

    @Test
    fun `every daylight core reads on the casing it lies on`() {
        listOf(
            "національна" to (day.roadNational to day.roadCasingNational),
            "обласна" to (day.roadRegional to day.roadCasing),
            "місцева" to (day.roadLocal to day.roadCasing),
        ).forEach { (what, pair) ->
            val (core, casing) = pair
            assertTrue(
                "$what: серцевина ${hex(core)} на облямівці ${hex(casing)} — " +
                    "${ratio(core, casing)}:1",
                contrast(core, casing) >= 2.8,
            )
        }
    }

    @Test
    fun `two casings, not one — the national edge is the darker of them`() {
        assertTrue(
            "облямівка національної мережі не відрізняється від решти",
            day.roadCasingNational != day.roadCasing,
        )
        assertTrue(
            "національна облямівка ${hex(day.roadCasingNational)} світліша за звичайну " +
                "${hex(day.roadCasing)} — ієрархія перевернута",
            contrast(day.roadCasingNational, dayLand) > contrast(day.roadCasing, dayLand),
        )
        // Обидві мусять читатися на землі самі по собі: удень саме облямівка —
        // єдина частина дороги, що темніша за папір.
        listOf(day.roadCasing, day.roadCasingNational).forEach {
            assertTrue("облямівка ${hex(it)} на землі — ${ratio(it, dayLand)}:1", contrast(it, dayLand) >= floor)
        }
    }

    @Test
    fun `a track is not a road, and is still visible over the new fields`() {
        // Стежка й польова дорога лежать здебільшого на полі, а не на голій
        // землі, — і саме поле цією хвилею вперше отримало колір.
        listOf("земля" to dayLand, "поле" to (day.farmland and 0xFFFFFF)).forEach { (what, ground) ->
            assertTrue(
                "стежка ${hex(day.path)} на «$what» — ${ratio(day.path, ground)}:1",
                contrast(day.path, ground) >= floor,
            )
        }
    }

    private fun distance(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    private fun source(relative: String): List<String> {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val file = java.io.File(
            dir,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/$relative",
        )
        assertTrue("$relative не знайдено — перевірка вже нічого не боронить", file.isFile)
        return file.readLines()
    }

    @Test
    fun `the route ribbon stays findable over its own brightened roads`() {
        // The teal core alone is 1.27:1 over the NATIONAL ink — the ink casing
        // is what carries the ribbon, exactly as it carries it over Liberty's
        // pale water. Both routes now have one; the alternative did not.
        listOf(MapSymbolPalette.DARK.routeCasing, MapSymbolPalette.LIGHT.routeCasing).forEach { casing ->
            RoadRank.entries.forEach { rank ->
                assertTrue(
                    "route casing ${hex(casing)} over $rank is ${ratio(casing, roadInk(rank))}:1",
                    contrast(casing, roadInk(rank)) >= floor,
                )
            }
        }
    }

    // ── WCAG (MapSymbolContrastTest's method) ────────────────────────────

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Int): Double =
        0.2126 * channel((color shr 16) and 0xFF) +
            0.7152 * channel((color shr 8) and 0xFF) +
            0.0722 * channel(color and 0xFF)

    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private fun ratio(a: Int, b: Int) = "%.2f".format(contrast(a, b))

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)
}
