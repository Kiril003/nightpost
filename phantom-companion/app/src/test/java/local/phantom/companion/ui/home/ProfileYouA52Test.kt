package local.phantom.companion.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Вади вкладки «Профіль», побачені на A52 10.10 (r19 d096ed87). */
class ProfileYouA52Test {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun read(rel: String) = File(root, rel).readText()
    private val tab get() = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
    private val settings get() = read("app/src/main/java/local/phantom/companion/ui/home/ProfileSettings.kt")

    @Test
    fun `імʼя для пошуку не просять двічі`() {
        val t = tab
        assertFalse("посилання «Додайте імʼя для пошуку» під іменем повернулось", "profile_handle_missing" in t)
        assertFalse("плитка «Імʼя для пошуку» повернулась на обличчя", "devices_handle" in t)
        assertFalse(ProfileAction.entries.any { it.name == "HANDLE" })
        assertTrue("імʼя для пошуку більше не досяжне з налаштувань", "go(ProfileSection.DEVICES.route)" in settings)
    }

    @Test
    fun `дії шапки тихі, фокус — людина`() {
        val t = tab
        assertFalse("плитки повернулись", "ActionTile(" in t)
        val bar = t.substringAfter("private fun YouTopBar(").substringBefore("\n}\n")
        assertTrue("кнопки шапки — 48dp", "touchMinAndroid" in bar)
        assertFalse("значок дії знову кричить акцентом", "c.accentText" in bar || "c.accent" in bar)
    }

    @Test
    fun `банер 210dp і лише коли встановлений, моменти, кола і профілі — секціями`() {
        val t = tab
        assertTrue("висота банера 210.dp", "BannerHeight = 210.dp" in read("app/src/main/java/local/phantom/companion/ui/home/ProfileBanner.kt"))
        assertTrue("аватар 88dp (PhantomSize.avatarXl)", "PhotoSize = PhantomSize.avatarXl" in t)
        assertTrue("без банера його місця не тримаємо", "AnimatedVisibility(visibleState = bannerState" in t && "if (!bannerShown) YouTopBar(onAction)" in t)
        assertFalse(" заголовки Кола/Моменти більше не висять окремо на полотні", "FaceCaption(" in t)
        assertTrue("Моменти, кола і профілі — секції із заголовком", listOf("MomentsCard(", "CirclesCard(", "ProfilesCard(", "SectionTitle(").all { it in t })
    }

    @Test
    fun `рядок мап не починається з «офлайн»`() {
        listOf("values-uk", "values").forEach { dir ->
            val xml = read("app/src/main/res/$dir/strings_home.xml")
            val v = Regex("""<string name="profile_offline_maps">([^<]*)</string>""").find(xml)!!.groupValues[1]
            assertFalse("$dir: «$v»", v.contains("offline", ignoreCase = true) || v.contains("офлайн", ignoreCase = true))
        }
        assertTrue("мапи не досяжні з налаштувань", "R.string.profile_offline_maps" in settings)
    }

    @Test
    fun `усе це досяжне з вкладки`() {
        assertEquals(ProfileSection.SETTINGS.route, ProfileAction.SETTINGS.route)
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home").listFiles()!!.joinToString("\n") { it.readText() }
        assertTrue("вкладка не малює ProfileTab", "ProfileTab(navController)" in home)
    }
}
