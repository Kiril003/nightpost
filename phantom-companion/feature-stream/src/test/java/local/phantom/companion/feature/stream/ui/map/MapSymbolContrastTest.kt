package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The phone map's contrast firewall — the thing feature-car has had since
 * CarPaletteTest and the map never did. The owner's «чорні іконки» were not
 * black icons: they were black PLATES under them, and nothing in the build
 * could tell.
 *
 * Every colour here is READ from [MapSymbolPalette], [GroundRegister] and
 * [groundPalette], never re-typed — a constant that drifts fails this file
 * instead of quietly going dark on someone's windscreen. Method is
 * CarPaletteTest's: src-over alpha compositing first (the plate is 95%, the
 * hatch 65% — the number that matters is what lands on the glass), then WCAG
 * relative luminance.
 *
 * The grounds used to be nine hand-copied hexes: five «what this app repaints»
 * (rgb(12,12,12), #4E4E57, #0F0F13…) and four read off OpenFreeMap Liberty.
 * Both sets stopped existing the day applyGroundCartography gave this app its
 * own cartography in BOTH polarities — the app has not painted #4E4E57 since,
 * and it no longer ships Liberty's ground at all. Nine numbers guarding a map
 * that is not drawn: the tests stayed green throughout.
 */
class MapSymbolContrastTest {

    // Every fill applyGroundCartography can put under a symbol, read from the
    // palette itself, plus the roof tone a building volume takes.
    private fun grounds(isDark: Boolean): Map<String, Int> {
        val p = groundPalette(isDark)
        val fills = mapOf(
            "земля" to p.land, "вода" to p.water, "глибока вода" to p.waterDeep,
            "річка" to p.waterway, "ліс" to p.wood, "трава" to p.grass,
            "поле" to p.farmland, "болото" to p.wetland, "пісок" to p.sand,
            "камінь" to p.rock, "лід" to p.ice, "забудова" to p.residential,
            "торгівля" to p.commercial, "промзона" to p.industrial,
            "цвинтар" to p.cemetery,
            "лікарня" to p.hospital, "школа" to p.school, "майданчик" to p.pitch,
            "летовище" to p.aeroway, "будинок" to p.building,
            "національна дорога" to p.roadNational, "обласна дорога" to p.roadRegional,
            "місцева дорога" to p.roadLocal, "облямівка дороги" to p.roadCasing,
        )
        val tones = mapOf("дах будинку" to MapTokens.roof(isDark).removePrefix("#").toInt(16))
        return (fills.mapValues { it.value and 0xFFFFFF }) + tones
    }

    private val floor = 3.0

    /**
     * Кожен значок на цій мапі має ДВА краї, і несе його той, що читається:
     * світле лице на темній землі, чорнильний край на світлій. Питати лише про
     * одне лице означає міряти половину обʼєкта — саме так яскрава нова
     * дорожня драбина опинилася під світлою плашкою (1.19:1) і жоден тест не
     * здригнувся.
     */
    private fun dualEdge(core: Int, edge: Int, ground: Int): Double =
        max(contrast(composite(core, ground), ground), contrast(composite(edge, ground), ground))

    @Test
    fun `the marker face reads on every ground the app paints, in both polarities`() {
        listOf(true, false).forEach { isDark ->
            val p = MapSymbolPalette.resolve(isDark)
            grounds(isDark).forEach { (name, ground) ->
                assertTrue(
                    "плашка на «$name» ${hex(ground)} — лице " +
                        "${ratio(composite(p.plate, ground), ground)}:1, край " +
                        "${ratio(p.plateRim, ground)}:1",
                    dualEdge(p.plate, p.plateRim, ground) >= floor,
                )
            }
        }
    }

    @Test
    fun `the plate face is theme-invariant, and that is the whole point`() {
        // One face for both polarities is the only design that also survives
        // the CUSTOM tile provider, whose ground this app cannot know.
        assertTrue(MapSymbolPalette.DARK.plate == MapSymbolPalette.LIGHT.plate)
        assertTrue(MapSymbolPalette.DARK.plateRim == MapSymbolPalette.LIGHT.plateRim)
    }

    @Test
    fun `every register survives every ground, in both polarities`() {
        // Акцент регістру їде НА облямівці маршруту (installRouteRegisterLayer
        // кладе його вужчим за серцевину, яка вужча за облямівку), тож землю
        // він зустрічає парою, а не сам.
        listOf(true, false).forEach { isDark ->
            val casing = MapSymbolPalette.resolve(isDark).routeCasing
            GroundRegister.entries.forEach { register ->
                val core = register.coreColor(isDark)
                grounds(isDark).forEach { (name, ground) ->
                    assertTrue(
                        "$register на «$name» ${hex(ground)} — серцевина " +
                            "${ratio(core, ground)}:1, облямівка ${ratio(casing, ground)}:1",
                        dualEdge(core, casing, ground) >= floor,
                    )
                }
                assertTrue(
                    "$register на власній облямівці — ${ratio(core, casing)}:1",
                    contrast(core, casing) >= floor,
                )
            }
        }
    }

    @Test
    fun `each register is told apart without colour at all`() {
        // Colour is gone under a red filter, in low sun, and to a colour-blind
        // crew member. Four registers, four textures, no two alike.
        val edges = GroundRegister.entries.map { it.edge() }
        assertTrue("a register with no texture of its own", edges.toSet().size == edges.size)
        assertTrue("MEASURED is the unbroken one", GroundRegister.MEASURED.edge().dashArray() == null)
        GroundRegister.entries.filter { it != GroundRegister.MEASURED }.forEach {
            assertTrue("$it must be broken", it.edge().dashArray() != null)
        }
    }

    @Test
    fun `the alternative route clears every ground, with no exception left`() {
        // Дві землі колись не бралися взагалі (2.75:1 і 2.76:1) і були
        // записані як прийнятий недобір. Відтоді альтернатива отримала власну
        // облямівку (MapStyleLayers.installAltRouteLayer), тож винятків більше
        // немає — і цей тест існує, щоб їх не можна було завести наново.
        listOf(true, false).forEach { isDark ->
            val p = MapSymbolPalette.resolve(isDark)
            grounds(isDark).forEach { (name, ground) ->
                assertTrue(
                    "альтернатива на «$name» ${hex(ground)} — серцевина " +
                        "${ratio(composite(p.altRoute, ground), ground)}:1, облямівка " +
                        "${ratio(p.routeCasing, ground)}:1",
                    dualEdge(p.altRoute, p.routeCasing, ground) >= floor,
                )
            }
        }
    }

    @Test
    fun `the not-downloaded hatch reads on the ground it describes`() {
        // ONLY the two backgrounds. installNotDownloadedHatch puts the layer
        // directly above the basemap's own background layer, so every water,
        // landuse, building and road the style draws lands ON
        // TOP of it — the background is the one ground it ever composites
        // against, and claiming a ratio against the others would be fiction.
        listOf(true, false).forEach { isDark ->
            val land = groundPalette(isDark).land and 0xFFFFFF
            val hatch = MapSymbolPalette.resolve(isDark).hatch
            assertTrue(
                "штрих на землі ${hex(land)} — ${ratio(composite(hatch, land), land)}:1",
                contrast(composite(hatch, land), land) >= floor,
            )
        }
    }

    @Test
    fun `map text reads against its own halo in both polarities`() {
        listOf(MapSymbolPalette.DARK, MapSymbolPalette.LIGHT).forEach { p ->
            assertTrue(
                "text on its halo (isDark=${p.isDark}) is ${ratio(p.textInk, p.textHalo)}:1",
                contrast(p.textInk, p.textHalo) >= floor,
            )
        }
    }

    // ── Geometry: the люфт, and why it cannot come back ──────────────────

    @Test
    fun `the puck chevron turns on its own centre`() {
        assertTrue(
            "chevron centroid is ${centroidY(OPERATOR_CHEVRON_PATH)}, not 0.5 — it will orbit the anchor",
            abs(centroidY(OPERATOR_CHEVRON_PATH) - 0.5) < 0.005,
        )
        assertTrue(
            "chevron centroid x is ${centroidX(OPERATOR_CHEVRON_PATH)}",
            abs(centroidX(OPERATOR_CHEVRON_PATH) - 0.5) < 0.005,
        )
    }

    @Test
    fun `the maneuver arrow turns on the centre of its own disc`() {
        assertTrue(
            "arrow centroid is ${centroidY(MANEUVER_ARROW_PATH)}, not 0.5 — it will orbit the anchor",
            abs(centroidY(MANEUVER_ARROW_PATH) - 0.5) < 0.005,
        )
        assertTrue(
            "arrow centroid x is ${centroidX(MANEUVER_ARROW_PATH)}",
            abs(centroidX(MANEUVER_ARROW_PATH) - 0.5) < 0.005,
        )
    }

    /** Shoelace centroid of a closed polygon given as normalized x,y pairs. */
    private fun centroid(path: FloatArray): Pair<Double, Double> {
        val n = path.size / 2
        var a = 0.0
        var cx = 0.0
        var cy = 0.0
        for (i in 0 until n) {
            val x0 = path[i * 2].toDouble()
            val y0 = path[i * 2 + 1].toDouble()
            val j = (i + 1) % n
            val x1 = path[j * 2].toDouble()
            val y1 = path[j * 2 + 1].toDouble()
            val cross = x0 * y1 - x1 * y0
            a += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        }
        a /= 2.0
        return cx / (6 * a) to cy / (6 * a)
    }

    private fun centroidX(path: FloatArray) = centroid(path).first
    private fun centroidY(path: FloatArray) = centroid(path).second

    // ── WCAG, over src-over compositing (CarPaletteTest's method) ────────

    /** [argb]'s own alpha blended onto an opaque ground, as Canvas does it. */
    private fun composite(argb: Int, ground: Int): Int {
        val alpha = ((argb ushr 24) and 0xFF) / 255f
        fun mix(shift: Int): Int {
            val f = (argb shr shift) and 0xFF
            val b = (ground shr shift) and 0xFF
            return (f * alpha + b * (1 - alpha)).toInt()
        }
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

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
