package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Профіль › Вигляд: кожен вибір намальовано, записано в сховище особи і він доходить до теми й месенджера. */
class AppearanceReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String) = File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `Вигляд малює попередній показ, акцент, тло, тіні й щільність`() {
        val section = src("ui/home/ProfileSections.kt").substringAfter("private fun AppearanceSection").substringBefore("private fun LanguageSection")
        listOf(
            "PhantomApp.container().appearance",
            "LookPreview(store, look, dark)",
            "AccentSwatches(look.accent",
            "it.copy(accent = id)",
            "WallpaperChoices(store, look)",
            "ShadeControls(store, look, dark)",
            "DensityChoice(look.bubbles) { v -> save { it.copy(bubbles = v) } }",
            "DensityChoice(look.list) { v -> save { it.copy(list = v) } }",
        ).forEach { assertTrue("$it зник із Вигляду", it in section) }
        assertTrue("до Вигляду не веде рядок профілю", "ProfileSection.APPEARANCE ->" in src("ui/home/ProfileSections.kt"))
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home").listFiles().orEmpty().filter { it.name != "ProfileSections.kt" }
        assertTrue("дія «Вигляд» зникла з Профілю", home.any { "go(ProfileSection.APPEARANCE.route)" in it.readText() })
    }

    @Test
    fun `галерея через системний вибір, тіні пишуться окремо для Дня й Ночі`() {
        val look = src("ui/home/AppearanceLook.kt")
        assertTrue("PickVisualMedia" in look)
        assertTrue("AppearanceStore.Target.Everywhere" in look)
        assertTrue("it.dim.with(dark, v)" in look)
        assertTrue("it.blur.with(dark, v)" in look)
    }

    @Test
    fun `акцент іде в тему застосунку, а зміна будить месенджер`() {
        assertTrue("accent = look.accent" in src("MainActivity.kt"))
        assertTrue("window.__phantomAppearance" in src("ui/messenger/WebMessengerHost.kt"))
        assertTrue("fun getAppearance()" in src("ui/messenger/WebMessengerBridge.kt"))
    }
}
