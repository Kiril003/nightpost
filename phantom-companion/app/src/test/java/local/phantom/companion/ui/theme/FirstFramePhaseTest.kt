package local.phantom.companion.ui.theme

import android.app.UiModeManager
import java.io.File
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.ui.messenger.nativeThemeModeOf
import local.phantom.companion.ui.messenger.webThemeModeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Перший кадр холодного старту — у фазі «Як сонце», яку застосунок справді
 * малює (збірка 16, рішення власника: Living лишається типовою).
 *
 * Виміряно на A52 (debug cac345a6, світла тема телефона, Living увечері):
 * 3,2 с кадрів яскравістю 153–225 — кремова системна заставка, — а тоді
 * чорний застосунок. Три двері, які це тримали відчиненими:
 *  1) заставку система малює за темою телефона, не за фазою Living;
 *  2) перший кадр рахував сонце над центром поясу, а не там, де людина;
 *  3) «Я › Тема» у вебі мала свій вибір, окремий від рідного.
 *
 * GATE LAW: частину перевіряє читанням вихідників; ганяти з `--rerun-tasks`.
 */
class FirstFramePhaseTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(rel: String) = File(root, "app/src/main/java/local/phantom/companion/$rel").readText()

    @Test
    fun `заставка наступного старту — у розвʼязаній полярності, System — як телефон`() {
        assertEquals(UiModeManager.MODE_NIGHT_YES, SplashNight.modeFor(ThemeMode.Living, resolvedDark = true))
        assertEquals(UiModeManager.MODE_NIGHT_NO, SplashNight.modeFor(ThemeMode.Living, resolvedDark = false))
        assertEquals(UiModeManager.MODE_NIGHT_YES, SplashNight.modeFor(ThemeMode.Dark, resolvedDark = true))
        assertEquals(UiModeManager.MODE_NIGHT_NO, SplashNight.modeFor(ThemeMode.Light, resolvedDark = false))
        // System читає тему телефона через isSystemInDarkTheme — власний режим
        // застосунку її б підмінив.
        assertEquals(UiModeManager.MODE_NIGHT_AUTO, SplashNight.modeFor(ThemeMode.System, resolvedDark = true))
    }

    @Test
    fun `MainActivity тримає режим заставки за палітрою і не перестворюється від нього`() {
        val main = src("MainActivity.kt")
        assertTrue("SplashNight ніхто не кличе", "SplashNight.sync(applicationContext, state.themeMode, resolvedSpec.isDark)" in main)
        val manifest = File(root, "app/src/main/AndroidManifest.xml").readText()
        val activity = manifest.substringAfter("android:name=\".MainActivity\"").substringBefore(">")
        assertTrue(
            "без uiMode у configChanges кожна зміна фази перестворювала б активність разом із WebView",
            Regex("""android:configChanges="[^"]*\buiMode\b""").containsMatchIn(activity),
        )
    }

    @Test
    fun `перше сонце — там, де людина була востаннє, а не над центром поясу`() {
        val main = src("MainActivity.kt")
        val daylight = main.substringAfter("private fun rememberDaylight(").substringBefore("LaunchedEffect(")
        assertTrue("перший кадр знову рахує сонце над центром поясу", "SunPlaces.firstFrame(context)" in daylight)
        val sun = src("ui/theme/LivingSun.kt")
        assertTrue("тікер не памʼятає місце для наступного першого кадру", "lastKnown(context)?.also { keep(context, it) }" in sun)
    }

    @Test
    fun `вибір теми з вебу — той самий, що в Профілі`() {
        assertEquals("auto", webThemeModeOf(ThemeMode.Living))
        assertEquals("auto", webThemeModeOf(ThemeMode.System))
        assertEquals("night", webThemeModeOf(ThemeMode.Dark))
        assertEquals("day", webThemeModeOf(ThemeMode.Light))
        assertEquals(ThemeMode.Living, nativeThemeModeOf("auto"))
        assertEquals(ThemeMode.Dark, nativeThemeModeOf("night"))
        assertEquals(ThemeMode.Light, nativeThemeModeOf("day"))
        assertNull(nativeThemeModeOf("sepia"))
        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        assertTrue("@JavascriptInterface\n    fun getThemeMode()" in bridge)
        assertTrue("@JavascriptInterface\n    fun setThemeMode(mode: String)" in bridge)
        val main = src("MainActivity.kt")
        assertTrue("вибір із вебу ніхто не забирає", "container.themeModeRequest.collect" in main)
        assertTrue("вибір із вебу йде повз VM", "vm.setThemeMode(mode)" in main)
    }
}
