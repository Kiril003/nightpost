package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.DawnBloom
import local.phantom.companion.core.design.theme.DayClear
import local.phantom.companion.core.design.theme.DayPhase
import local.phantom.companion.core.design.theme.DuskEmber
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.design.theme.resolveLivingTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Дзеркало контракту теми між рідним боком і вебом.
 *
 * Веб читає тему двома шляхами — `PhantomBridge.getTheme()` на запит і поштовх
 * `window.__phantomTheme(...)` на зміну. Обидва мусять існувати на боці Kotlin,
 * інакше це рівно той клас, що й решта цієї хвилі: бандл кличе, рідний бік
 * мовчить, і ніхто не червоніє.
 *
 * НЕ дублює `WebBundleRoutesReachTest` (у цій гілці його немає) — при зведенні
 * з гілкою мосту цей файл слід скласти в той сторож.
 */
class WebThemeBridgeTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val pkgDir = "app/src/main/java/local/phantom/companion/ui/messenger"
    private val bridge get() = File(repoRoot(), "$pkgDir/WebMessengerBridge.kt")
    private val host get() = File(repoRoot(), "$pkgDir/WebMessengerHost.kt")

    @Test
    fun `bridge exposes getTheme to javascript`() {
        val src = bridge.readText()
        val at = src.indexOf("fun getTheme(")
        assertTrue("getTheme() немає у WebMessengerBridge", at > 0)
        val before = src.substring(0, at)
        assertTrue(
            "getTheme() є, але без @JavascriptInterface — веб його не побачить",
            before.trimEnd().endsWith("@JavascriptInterface"),
        )
    }

    @Test
    fun `host pushes the theme when it changes`() {
        val src = host.readText()
        assertTrue("поштовху window.__phantomTheme немає", src.contains("window.__phantomTheme"))
        assertTrue(
            "поштовх є, але не з LaunchedEffect на палітрі — зміна теми не доїде",
            src.contains("LaunchedEffect(webView, themePayload, themePhase, sunLook)"),
        )
    }

    /** Обидві сторони мусять говорити про ОДИН рядок, інакше веб розбиратиме інше. */
    @Test
    fun `getTheme returns the same payload the push sends`() {
        assertTrue(bridge.readText().contains("container.webTheme.value"))
        assertTrue(host.readText().contains("container.webTheme.value = themePayload"))
    }

    /**
     * Форму диктує БАНДЛ, а не рідний бік, тому й звіряємо з бандлом.
     *
     * 12.09 дві гілки незалежно додали `getTheme()` з різними контрактами:
     * `day`/`night` і JSON `{"mode","surface","ink","accent"}`. Обидві
     * компілювались. Але бандл робить `getTheme()==="day"?"day":"night"`, тож
     * JSON звівся б до ночі — денна палітра не засвітилась би НІКОЛИ, і жоден
     * тест на боці Kotlin цього б не показав.
     */
    @Test
    fun `payload is exactly the string the bundle compares against`() {
        val scripts = File(repoRoot(), "app/src/main/assets/webmsgr")
            .walkTopDown().filter { it.isFile && it.extension == "js" }.toList()
        assertTrue("у бандлі нема скриптів — сторож нічого не прочитав", scripts.size > 1)
        val web = scripts.joinToString("\n") { it.readText() }
        assertTrue(
            "бандл більше не звіряє getTheme() з \"day\" — контракт змінився, перечитай обидві сторони",
            web.contains("""s.getTheme()==="day"?"day":"night"""") ||
                Regex("""getTheme\(\)\s*===\s*"day"""").containsMatchIn(web),
        )
        assertTrue(
            "рідний бік мусить віддавати саме day/night",
            host.readText().contains("""if (spec.isDark) "night" else "day""""),
        )
        assertEquals(
            "бандл не читає кольорів — не вигадуй їх у payload",
            0,
            Regex("""\.put\("(surface|ink|accent)"""").findAll(host.readText()).count(),
        )
    }

    @Test
    fun `bridge exposes getThemePhase next to getTheme`() {
        val src = bridge.readText()
        val at = src.indexOf("fun getThemePhase(")
        assertTrue("getThemePhase() немає у WebMessengerBridge", at > 0)
        assertTrue(
            "getThemePhase() без @JavascriptInterface",
            src.substring(0, at).trimEnd().endsWith("@JavascriptInterface"),
        )
        assertTrue(src.contains("container.webThemePhase.value"))
    }

    /**
     * Кожна палітра зводиться до найближчої з двох для старого бандла. З 02.10
     * живе сонце дає лише дві палітри: світанок — день, сутінки — ніч.
     */
    @Test
    fun `phase payload follows the two living palettes and polarity stays the nearest of two`() {
        val expected = mapOf(
            AmberNight to ("night" to "night"),
            DawnBloom to ("dawn" to "day"),
            DayClear to ("day" to "day"),
            DuskEmber to ("dusk" to "night"),
            SunriseWarm to ("day" to "day"),
            AmberNight.morphForNight(true) to ("night" to "night"),
        )
        for ((spec, pair) in expected) {
            assertEquals(spec.id, pair.first, webThemePhase(spec))
            assertEquals(spec.id, pair.second, webThemePayload(spec))
        }
        assertEquals(
            DayPhase.entries.map { it.web },
            DayPhase.entries.map { webThemePhase(resolveLivingTheme(it)) },
        )
        assertEquals("", webSunLook(DayClear))
        assertEquals("", webSunLook(AmberNight))
        assertEquals("golden:day:0.500", webSunLook(resolveLivingTheme(local.phantom.companion.core.design.theme.SunLook.at(6.0, rising = false))))
    }

    /** Поштовх несе обидва аргументи: day/night першим (старий бандл), фазу другим. */
    @Test
    fun `push sends polarity first and phase second`() {
        val src = host.readText()
        assertTrue(src.contains("container.webThemePhase.value = themePhase"))
        assertTrue(
            Regex("""__phantomTheme\(" \+\s*"\$\{org\.json\.JSONObject\.quote\(themePayload\)\}, \$\{org\.json\.JSONObject\.quote\(themePhase\)\}""")
                .containsMatchIn(src),
        )
    }

    /** Кадр сонця — той самий, що в оболонці: міст віддає його на запит і везе третім аргументом поштовху. */
    @Test
    fun `sun look rides the bridge and the push`() {
        val src = bridge.readText()
        val at = src.indexOf("fun getSunLook(")
        assertTrue("getSunLook() немає у WebMessengerBridge", at > 0)
        assertTrue(src.substring(0, at).trimEnd().endsWith("@JavascriptInterface"))
        assertTrue(src.contains("container.webSunLook.value"))
        val h = host.readText()
        assertTrue(h.contains("org.json.JSONObject.quote(sunLook)"))
        assertTrue(h.indexOf("container.webSunLook.value = sunLook") < h.indexOf("loadUrl(WebMessengerAssets.ENTRY)"))
    }

    /** Перший кадр: фаза лежить у контейнері раніше, ніж WebView почне вантажити сторінку. */
    @Test
    fun `theme is in the container before the page starts loading`() {
        val src = host.readText()
        val written = src.indexOf("container.webThemePhase.value = themePhase")
        val loaded = src.indexOf("loadUrl(WebMessengerAssets.ENTRY)")
        assertTrue("фазу не записано", written > 0)
        assertTrue("фаза записується ПІСЛЯ loadUrl — перший кадр буде з минулого", written < loaded)
    }

    /** Негативний контроль: сам тест мусить уміти почервоніти. */
    @Test
    fun `absent method is detected`() {
        val src = "class X { fun somethingElse() = 1 }"
        assertEquals(-1, src.indexOf("fun getTheme("))
    }
}
