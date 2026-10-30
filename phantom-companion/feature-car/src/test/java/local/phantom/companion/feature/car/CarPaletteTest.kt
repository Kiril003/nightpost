package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Day and night on the car display.
 *
 * The map used to be painted in one hardcoded dark palette whatever the head
 * unit was showing — right at night by accident, washed out in daylight in
 * silence. These tests assert two different sets of pixels, not that a callback
 * fired: a config change that repaints the same colours would satisfy the
 * second kind of test and prove nothing.
 */
class CarPaletteTest {

    /** Any dimmed state on the light ground — the road already driven today,
     *  a downgraded route register later — must still clear this against it. */
    private val dimFloor = 1.8

    @Test
    fun `the head unit's theme decides the palette`() {
        assertEquals(CarPalette.NIGHT, CarPalette.of(dark = true))
        assertEquals(CarPalette.DAY, CarPalette.of(dark = false))
    }

    @Test
    fun `the two palettes share no colour at all`() {
        val day = CarPalette.DAY
        val night = CarPalette.NIGHT
        assertNotEquals(day.ground, night.ground)
        assertNotEquals(day.ahead, night.ahead)
        assertNotEquals(day.behind, night.behind)
        assertNotEquals(day.puck, night.puck)
        assertNotEquals(day.ink, night.ink)
    }

    @Test
    fun `day is a light ground and night is a dark one`() {
        assertTrue(luminance(CarPalette.DAY.ground) > 0.6)
        assertTrue(luminance(CarPalette.NIGHT.ground) < 0.05)
    }

    @Test
    fun `the road ahead, the driver and the waiting line read in both`() {
        listOf(CarPalette.DAY, CarPalette.NIGHT).forEach { p ->
            assertTrue("the route ahead", contrast(p.ahead, p.ground) >= 3.0)
            assertTrue("the puck", contrast(p.puck, p.ground) >= 3.0)
            assertTrue("the waiting line", contrast(p.ink, p.ground) >= 3.0)
        }
    }

    @Test
    fun `the road already driven is dimmer than the road ahead, and still there`() {
        // The floor matters more than it looks: a dim state derived for a
        // near-black ground turns into "absent" on a light one in sunlight,
        // which is how a light palette silently deletes an honesty signal.
        listOf(CarPalette.DAY, CarPalette.NIGHT).forEach { p ->
            assertTrue(contrast(p.behind, p.ground) < contrast(p.ahead, p.ground))
            assertTrue("must not vanish on this ground", contrast(p.behind, p.ground) >= dimFloor)
        }
    }

    /**
     * The car is the same product as the phone (identity v1.2, 04.10): night
     * ground is «Ніч», the driver is the phone map's own amber puck
     * (MapSymbolPalette.PUCK_CORE), and the day route is the phone's day
     * route. Until 04.10 the car had its own warm-brown palette (#0E0B06,
     * #FFC24B, #0B6F62) — a third look next to the old and the new.
     */
    @Test
    fun `the car wears the phone's identity`() {
        assertEquals(0xFF0A0C10.toInt(), CarPalette.NIGHT.ground)
        assertEquals(0xFFF8F4EE.toInt(), CarPalette.DAY.ground)
        assertEquals(phoneHex("MapSymbolPalette.kt", Regex("""PUCK_CORE = 0x([0-9A-Fa-f]{8})""")), CarPalette.NIGHT.puck)
        assertEquals(dToken("""val NightColors = PhantomColors\([\s\S]*?\n    accent = Color\(0x([0-9A-Fa-f]{8})\)"""), CarPalette.NIGHT.puck)
        assertEquals(phoneHex("MapStyleLayers.kt", Regex("""ROUTE_CORE_COLOR = android\.graphics\.Color\.parseColor\("#([0-9A-Fa-f]{6})"\)""")), CarPalette.NIGHT.ahead)
        assertEquals(0xFF00786A.toInt(), CarPalette.DAY.ahead)
    }

    @Test
    fun `overspeed is the palette's danger, readable in both themes`() {
        assertNotEquals(CarPalette.DAY.danger, CarPalette.NIGHT.danger)
        listOf(CarPalette.DAY, CarPalette.NIGHT).forEach { p ->
            assertTrue("danger on ground", contrast(p.danger, p.ground) >= 3.0)
        }
        val src = sourceOf("feature-car", "CarMapSurface.kt")
        assertTrue("overspeed must not paint a private red", "0xFFFF4D4D" !in src)
        assertEquals(
            "speed number and box border both turn danger",
            2,
            Regex("""if \(isOverspeed\) palette\.danger""").findAll(src).count(),
        )
    }

    private fun tree(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-car").isDirectory) dir = dir.parentFile
        return dir ?: error("tree root with feature-car/ not found")
    }

    private fun sourceOf(module: String, name: String): String =
        File(tree(), "$module/src/main/java").walkTopDown().first { it.name == name }.readText()

    private fun dToken(pattern: String): Int {
        val hex = Regex(pattern).find(sourceOf("core-design", "PhantomTokens.kt"))?.groupValues?.get(1)
            ?: error("PhantomTokens.kt: token not found")
        return hex.toLong(16).toInt()
    }

    private fun phoneHex(name: String, re: Regex): Int {
        val hex = re.find(sourceOf("feature-stream", name))?.groupValues?.get(1)
            ?: error("$name: colour not found")
        return (if (hex.length == 6) "FF$hex" else hex).toLong(16).toInt()
    }

    /**
     * The transfer this file's own comment warned about (see [dimFloor]):
     * a GUESSED/REMEMBERED route segment draws `ahead` at
     * [CarMapSurface.REGISTER_ALPHA] over the ground, and DAY is the tight
     * side — it starts from a darker `ahead` colour composited onto a near-
     * white ground, which is exactly the direction that silently deletes a
     * dim signal in daylight. Simulates Canvas's own src-over blend rather
     * than trusting the alpha number, because the number alone proves
     * nothing about what a driver actually sees.
     */
    @Test
    fun `a register-downgraded segment still clears the dim floor in daylight`() {
        listOf(CarPalette.DAY, CarPalette.NIGHT).forEach { p ->
            val blended = blend(p.ahead, p.ground, CarMapSurface.REGISTER_ALPHA / 255f)
            assertTrue(
                "downgraded register must not vanish on this ground",
                contrast(blended, p.ground) >= dimFloor,
            )
        }
    }

    private fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun channelOf(c: Int, shift: Int) = (c shr shift) and 0xFF
        fun mix(f: Int, b: Int) = (f * alpha + b * (1 - alpha)).toInt()
        val r = mix(channelOf(fg, 16), channelOf(bg, 16))
        val g = mix(channelOf(fg, 8), channelOf(bg, 8))
        val b = mix(channelOf(fg, 0), channelOf(bg, 0))
        return (r shl 16) or (g shl 8) or b
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
}
