package local.phantom.companion.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Обличчя профілю не видає звань, станів і технічних ідентифікаторів:
 * «ПОВНА ДОВІРА · ГОЛОВНИЙ ОПЕРАТОР» (06.09), «Nightpost · не налаштовано»,
 * помаранчеве «Додайте імʼя» і відбиток ключа на першому екрані (10.10).
 * Ключ лишається досяжним за рядком «Налаштування».
 */
class ProfileHeaderIsHonestTest {

    private val root = File("../").canonicalFile

    private fun read(rel: String): String {
        val f = File(root, rel)
        assertTrue("немає файла $rel", f.isFile)
        return f.readText()
    }

    private val home = "app/src/main/java/local/phantom/companion/ui/home"

    @Test
    fun `обличчя не показує техніки`() {
        val tab = read("$home/ProfileTab.kt")
        listOf(
            "prof_root_trust", "prof_operator_title", "profile_not_set_up", "profile_add_name",
            "shortId(", "fingerprint", "meId.take(", "PeerRoster",
        ).forEach { assertFalse("на обличчі знову «$it»", it in tab) }
        assertFalse("порожнє імʼя знову кричить акцентом", "if (me.name.isBlank()) c.accentText" in tab)
    }

    @Test
    fun `ключ досяжний за налаштуваннями`() {
        val settings = read("$home/ProfileSettings.kt")
        assertTrue("ключа немає в налаштуваннях", "R.string.profile_key_short" in settings && "PeerRoster.shortId(meId)" in settings)
        assertTrue("налаштування не відкриваються з обличчя", "SETTINGS -> ProfileSection.SETTINGS.route" in read("$home/ProfileTab.kt"))
        assertTrue("розділ налаштувань ні до чого не веде", "ProfileSection.SETTINGS -> SettingsSection(" in read("$home/ProfileSections.kt"))
    }
}
