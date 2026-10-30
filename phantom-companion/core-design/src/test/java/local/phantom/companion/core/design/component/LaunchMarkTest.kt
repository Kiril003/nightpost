package local.phantom.companion.core.design.component

import androidx.compose.ui.graphics.Color
import java.io.File
import kotlin.math.hypot
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Знак живе в кількох копіях: SVG бренду (docs/brand), значок, монохромний
 * значок, системна заставка, знак вкладки і Compose-кадр передачі. Розійдуться —
 * на запуску знак стрибне чи змінить колір між системною заставкою і
 * застосунком.
 *
 * Палітра першого кадру (збірка 16): системна заставка — values/values-night
 * (нічний режим застосунку веде SplashNight за фазою Living), завіса Compose —
 * розвʼязана палітра застосунку, не тема телефона.
 */
class LaunchMarkTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with app/ not found") as Nothing
    }

    private fun read(rel: String): String {
        val f = File(repoRoot(), rel)
        if (!f.isFile) fail("$rel is missing")
        return f.readText()
    }

    private fun color(file: String, name: String): Color {
        val raw = Regex("""<color name="$name">#([0-9A-Fa-f]{6})</color>""").find(read(file))
            ?.groupValues?.get(1) ?: fail("$name is gone from $file") as Nothing
        return Color(("FF$raw").toLong(16))
    }

    private fun attr(xml: String, name: String): Float =
        Regex("""android:$name="([-0-9.]+)"""").find(xml)?.groupValues?.get(1)?.toFloat()
            ?: fail("android:$name not found") as Nothing

    @Test
    fun `every copy of the mark draws the same path`() {
        for (rel in listOf(
            "../docs/brand/assets/symbol/nightpost-symbol-24.svg",
            "app/src/main/res/drawable/ic_launcher_foreground.xml",
            "app/src/main/res/drawable/ic_launcher_monochrome.xml",
            "app/src/main/res/drawable/ic_splash_mark.xml",
            "app/src/main/res/drawable/lc_phantom_mark.xml",
        )) {
            assertTrue("$rel draws a different mark than PhantomMark.PATH", read(rel).contains(PhantomMark.PATH))
        }
    }

    @Test
    fun `the old P is gone from every drawable`() {
        val drawables = File(repoRoot(), "app/src/main/res/drawable").listFiles().orEmpty()
        assertTrue(drawables.size > 3)
        drawables.forEach { assertFalse("${it.name} still draws the P", it.readText().contains("M4.5 21V3H12.5")) }
    }

    /** Точки обох півкіл знака у вʼюпорті 24: центри (11,10) і (13,14), радіус 8. */
    private fun markOutline(): List<Pair<Float, Float>> = (0..180).flatMap { i ->
        val a = Math.toRadians(90.0 + i)   // ліва половина: 90°..270°
        val b = Math.toRadians(-90.0 + i)  // права половина: −90°..90°
        listOf(
            (11 + 8 * kotlin.math.cos(a)).toFloat() to (10 + 8 * kotlin.math.sin(a)).toFloat(),
            (13 + 8 * kotlin.math.cos(b)).toFloat() to (14 + 8 * kotlin.math.sin(b)).toFloat(),
        )
    }

    @Test
    fun `the launcher mark stays inside the adaptive safe zone`() {
        val xml = read("app/src/main/res/drawable/ic_launcher_foreground.xml")
        val s = attr(xml, "scaleX")
        val t = attr(xml, "translateX")
        assertEquals(s, attr(xml, "scaleY"), 0f)
        assertEquals(t, attr(xml, "translateY"), 0f)
        assertEquals("the mark is off centre", 54f, 12f * s + t, 1e-3f)
        val far = markOutline().maxOf { (x, y) -> hypot(x * s + t - 54f, y * s + t - 54f) }
        assertTrue("the mark reaches $far from centre; launchers crop past 33", far <= 33f)
    }

    @Test
    fun `the system splash and the compose handoff draw the mark at one size and place`() {
        val xml = read("app/src/main/res/drawable/ic_splash_mark.xml")
        val s = attr(xml, "scaleX")
        assertEquals(288f, attr(xml, "viewportWidth"), 0f)
        assertEquals(PhantomMark.SplashUnit.value, s, 0f)
        assertEquals("the splash mark is off centre", 144f - PhantomMark.VIEWPORT / 2f * s, attr(xml, "translateX"), 1e-3f)
        assertEquals(attr(xml, "translateX"), attr(xml, "translateY"), 0f)
        // Система кадрує коло d192 (r96 від центру 144): знак мусить лишитись усередині.
        val far = markOutline().maxOf { (x, y) -> hypot(x * s + attr(xml, "translateX") - 144f, y * s + attr(xml, "translateY") - 144f) }
        assertTrue("the splash mark reaches $far; the system crops past 96", far <= 96f)
        assertTrue(xml.contains("@color/brand_mark"))
    }

    @Test
    fun `splash canvas, ink and mark are the token colours of each theme`() {
        assertEquals(DayColors.canvas, color("app/src/main/res/values/colors.xml", "phantom_canvas"))
        assertEquals(DayColors.text, color("app/src/main/res/values/colors.xml", "phantom_ink"))
        assertEquals(NightColors.canvas, color("app/src/main/res/values-night/colors.xml", "phantom_canvas"))
        assertEquals(NightColors.text, color("app/src/main/res/values-night/colors.xml", "phantom_ink"))
        // Знак — лінія бурштину своєї теми: та сама, якою його малює завіса.
        assertEquals(DayColors.accentLine, color("app/src/main/res/values/brand_colors.xml", "brand_mark"))
        assertEquals(NightColors.accentLine, color("app/src/main/res/values-night/brand_colors.xml", "brand_mark"))
        assertEquals(NightColors.canvas, color("app/src/main/res/values/colors.xml", "ic_launcher_background"))
        assertTrue(read("app/src/main/res/values/bools.xml").contains("""<bool name="phantom_light_bars">true</bool>"""))
        assertTrue(read("app/src/main/res/values-night/bools.xml").contains("""<bool name="phantom_light_bars">false</bool>"""))
    }

    @Test
    fun `the compose veil is the resolved palette, not the phone theme`() {
        val src = read("core-design/src/main/java/local/phantom/companion/core/design/component/PhantomSplash.kt")
        val host = src.substringAfter("fun PhantomSplashHost(").substringBefore("internal const val SPLASH_HOLD_MS")
        // Тема телефона — це НЕ фаза Living: світлий телефон увечері дав би
        // кремову завісу над чорним застосунком.
        assertFalse("завіса знову йде за темою телефона", "isSystemInDarkTheme()" in host)
        assertFalse("завіса знову прибита до однієї палітри", "NightColors" in host || "DayColors" in host)
        assertTrue(host.contains("LocalPhantomColors.current"))
        assertTrue(host.contains("canvas = colors.canvas"))
        assertTrue(host.contains("ink = colors.accentLine"))
    }

    @Test
    fun `the theme wires the splash to the canvas with no icon disc`() {
        val theme = read("app/src/main/res/values/themes.xml")
        assertTrue(theme.contains("""name="android:windowSplashScreenBackground" tools:targetApi="s">@color/phantom_canvas<"""))
        assertTrue(theme.contains("""name="android:windowSplashScreenAnimatedIcon" tools:targetApi="s">@drawable/ic_splash_mark<"""))
        assertTrue("window under Compose differs from the splash", theme.contains("""name="android:windowBackground">@color/phantom_canvas<"""))
        assertFalse("an icon background colour puts a disc behind the mark", theme.contains("""name="android:windowSplashScreenIconBackgroundColor""""))
    }
}
