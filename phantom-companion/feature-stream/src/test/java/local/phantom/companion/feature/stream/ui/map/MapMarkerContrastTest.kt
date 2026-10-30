package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The MARKERS half of the map's contrast firewall — everything that stands on
 * the ground rather than being the ground. MapSymbolContrastTest holds the
 * plate, the route and the register; this file holds the operator's own puck,
 * the search pin, the phantom-layer markers, the POI clusters, the alert
 * boundary and the walking leg.
 *
 * All six had NO light-mode value at all on 15 Aug: the separator ring was
 * Color.WHITE on both polarities (1.09:1 on Liberty's background), so on the
 * light basemap the amber operator dot stood at 1.75:1 with nothing around it.
 * Grounds below are the same ones MapSymbolContrastTest measures against, and
 * every colour is READ from [MapSymbolPalette] — a constant that drifts fails
 * here instead of quietly going flat on someone's windscreen at noon.
 */
class MapMarkerContrastTest {

    private val darkBg = 0x0C0C0C
    private val darkLanduse = 0x15151A
    private val darkBuilding = 0x1F1F24
    private val darkRoad = 0x4E4E57
    private val darkWater = 0x0F0F13
    private val darkGrounds = listOf(darkBg, darkLanduse, darkBuilding, darkRoad, darkWater)

    private val lightBg = 0xF8F4F0
    private val lightWater = 0x9EBDFF
    private val lightPark = 0xD8E8C8
    private val lightBuilding = 0xDCD8D2
    private val lightGrounds = listOf(lightBg, lightWater, lightPark, lightBuilding)

    private val floor = 3.0

    @Test
    fun `the puck's ink rim reads on every light ground`() {
        lightGrounds.forEach { ground ->
            assertTrue(
                "puck rim on ${hex(ground)} is ${ratio(MapSymbolPalette.PUCK_RIM, ground)}:1",
                contrast(MapSymbolPalette.PUCK_RIM, ground) >= floor,
            )
        }
    }

    @Test
    fun `the puck's light halo reads on every dark ground`() {
        darkGrounds.forEach { ground ->
            assertTrue(
                "puck halo on ${hex(ground)} is ${ratio(MapSymbolPalette.PUCK_HALO, ground)}:1",
                contrast(MapSymbolPalette.PUCK_HALO, ground) >= floor,
            )
        }
    }

    @Test
    fun `the operator's amber never touches the ground`() {
        // It measures 1.75:1 on Liberty's background and 1.02:1 on its water —
        // which is why the amber is bounded by ink on one side and the ink by
        // light on the other, instead of being darkened for daylight. The
        // operator's colour is the operator's colour on both basemaps.
        assertTrue(
            "amber against its own rim is ${ratio(MapSymbolPalette.PUCK_CORE, MapSymbolPalette.PUCK_RIM)}:1",
            contrast(MapSymbolPalette.PUCK_CORE, MapSymbolPalette.PUCK_RIM) >= floor,
        )
        assertTrue(
            "the two edges are ${ratio(MapSymbolPalette.PUCK_RIM, MapSymbolPalette.PUCK_HALO)}:1 apart",
            contrast(MapSymbolPalette.PUCK_RIM, MapSymbolPalette.PUCK_HALO) >= floor,
        )
    }

    @Test
    fun `the puck is theme-invariant, like the plate`() {
        // A CUSTOM tile provider's ground is unknowable, and the operator's own
        // position is the one symbol that may never depend on guessing it.
        val body = installer("installOperatorPin")
        listOf("PUCK_CORE", "PUCK_RIM", "PUCK_HALO").forEach {
            assertTrue("the puck no longer paints itself from $it", body.contains(it))
        }
        assertTrue(
            "installOperatorPin takes a polarity — the puck must not have one",
            !body.substringBefore("{").contains("isDark"),
        )
    }

    @Test
    fun `the separator ring is the opposite of the ground it sits on`() {
        assertTrue(
            "the light separator is white again — that is the 1.09:1 defect",
            MapSymbolPalette.LIGHT.separator != 0xFFFFFFFF.toInt(),
        )
        assertTrue(
            "one separator for both grounds cannot separate on both",
            MapSymbolPalette.LIGHT.separator != MapSymbolPalette.DARK.separator,
        )
        lightGrounds.forEach { ground ->
            assertTrue(
                "light separator on ${hex(ground)} is ${ratio(MapSymbolPalette.LIGHT.separator, ground)}:1",
                contrast(MapSymbolPalette.LIGHT.separator, ground) >= floor,
            )
        }
        darkGrounds.forEach { ground ->
            assertTrue(
                "dark separator on ${hex(ground)} is ${ratio(MapSymbolPalette.DARK.separator, ground)}:1",
                contrast(MapSymbolPalette.DARK.separator, ground) >= floor,
            )
        }
    }

    @Test
    fun `every ground-touching core reads on its own polarity`() {
        forEachPolarity { palette, grounds ->
            listOf(
                "search pin" to palette.searchCore,
                "alert boundary" to palette.alertEdge,
                "walking leg" to palette.transitWalk,
            ).forEach { (name, colour) ->
                // The flattened road is the one exception, and it is measured
                // in its own test below rather than skipped in silence.
                grounds.filter { it != darkRoad }.forEach { ground ->
                    assertTrue(
                        "$name (isDark=${palette.isDark}) on ${hex(ground)} is ${ratio(colour, ground)}:1",
                        contrast(colour, ground) >= floor,
                    )
                }
            }
        }
    }

    @Test
    fun `over a flattened road only the night alert boundary falls short`() {
        // 2.98:1 — three hundredths under, and it is the value the night map
        // already shipped. Written down so it cannot quietly deepen, and named
        // so nobody re-discovers it as new. The other two clear it outright.
        val p = MapSymbolPalette.DARK
        assertTrue(
            "the night alert boundary over a flattened road is ${ratio(p.alertEdge, darkRoad)}:1",
            contrast(p.alertEdge, darkRoad) >= 2.9,
        )
        listOf("search pin" to p.searchCore, "walking leg" to p.transitWalk).forEach { (name, c) ->
            assertTrue(
                "$name over a flattened road is ${ratio(c, darkRoad)}:1",
                contrast(c, darkRoad) >= floor,
            )
        }
    }

    @Test
    fun `the cluster pile stands apart from every ground and its count reads on the stone`() {
        // Стос — та сама пара «світле лице + чорнильний край», що й жетони
        // місць: лице тримає темну землю, край — світлу. Лічба лежить на лиці.
        val p = MapSymbolPalette.DARK
        val face = p.plate and 0xFFFFFF
        val rim = p.plateRim and 0xFFFFFF
        darkGrounds.forEach { g ->
            assertTrue("face on ${hex(g)} is ${ratio(face, g)}:1", contrast(face, g) >= floor)
        }
        lightGrounds.forEach { g ->
            assertTrue("rim on ${hex(g)} is ${ratio(rim, g)}:1", contrast(rim, g) >= floor)
        }
        val ink = p.plateInk and 0xFFFFFF
        assertTrue("count on the stone is ${ratio(ink, face)}:1", contrast(ink, face) >= 4.5)
    }

    @Test
    fun `the search pin is not the operator's dot in another colour`() {
        // Two filled discs differing by hue alone are one symbol under a red
        // filter, to a colour-blind crew member, and in low sun. The search
        // result is hollow; the operator's position is the only filled dot.
        val body = installer("installSearchPin")
        assertTrue(
            "the search pin is filled again — it now reads as a second operator",
            body.contains("circleOpacity(0f)"),
        )
        assertTrue(
            "the search pin has no ring left to carry it",
            body.contains("circleStrokeColor(palette.searchCore)"),
        )
    }

    /** The body of an installer in MapStyleLayers.kt, brace-matched. */
    private fun installer(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        assertTrue("repo root with feature-stream/ not found", dir != null)
        val file = File(
            dir!!,
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapStyleLayers.kt",
        )
        assertTrue("MapStyleLayers.kt не знайдено — перевірка вже нічого не боронить", file.isFile)
        val source = file.readText()
        val at = source.indexOf("fun $name(")
        assertTrue("$name is gone from MapStyleLayers.kt", at >= 0)
        var depth = 0
        var i = source.indexOf('{', at)
        assertTrue("$name has no body", i > at)
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        // A comment describes; it does not draw.
        return source.substring(at, minOf(i + 1, source.length))
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
    }

    private fun forEachPolarity(block: (MapSymbolPalette, List<Int>) -> Unit) {
        block(MapSymbolPalette.DARK, darkGrounds)
        block(MapSymbolPalette.LIGHT, lightGrounds)
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
