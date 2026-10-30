package local.phantom.companion.ui.home

import java.io.File
import local.phantom.companion.core.net.peer.PeerName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** L6 logic audit 18.10: items 18, 19, 20, 30, 31. */
class ProfileL6LogicTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun read(rel: String): String = File(root, rel).readText()

    private fun string(dir: String, file: String, name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""").find(read("app/src/main/res/$dir/$file"))?.groupValues?.get(1)

    @Test
    fun `item 18 - blank name reads as Add your name in c text2 and is clickable`() {
        assertEquals("Add your name", string("values", "strings_home.xml", "profile_name_missing"))
        assertEquals("Додати імʼя", string("values-uk", "strings_home.xml", "profile_name_missing"))
        assertEquals("Přidat jméno", string("values-cs", "strings_home.xml", "profile_name_missing"))
        val tab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        val block = tab.substringAfter("R.string.profile_name_missing").substringBefore("handle?.let")
        assertTrue("blank name must use c.text2", "if (me.name.isBlank()) c.text2 else c.text" in block)
        assertTrue("blank name must be clickable with onName", "onClick = onName" in block)
    }

    @Test
    fun `item 19 - highlight tiles are clickable and route to existing moment door`() {
        val tab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        val highlights = tab.substringAfter("private fun Highlights(").substringBefore("private fun MyCodeDialog(")
        assertTrue("highlight tile must use HighlightSize", "Modifier\n                    .testTag(\"you-moment-tile\")\n                    .size(HighlightSize)" in highlights || ".size(HighlightSize)" in highlights)
        assertTrue("highlight tile must be clickable", ".clickable(" in highlights && "onOpen(h)" in highlights)
        assertTrue("MomentsCard opens that moment", "onOpen = { openMoment(it.id) }" in tab)
        assertTrue("ProfileTab wires the moment door", "WebNav.Door.OpenMoment(id)" in tab)
        val nav = read("app/src/main/java/local/phantom/companion/ui/messenger/WebNav.kt")
        assertTrue("door calls the web opener", "window.__phantomOpenMoment" in nav)
    }

    @Test
    fun `item 20 - MyCodeDialog has a failure state with retry calling refreshInvite`() {
        assertEquals("Couldn\\'t make a code", string("values", "strings_profile_face.xml", "profile_code_failed"))
        assertEquals("Не вдалося створити код", string("values-uk", "strings_profile_face.xml", "profile_code_failed"))
        assertEquals("Kód se nepodařilo vytvořit", string("values-cs", "strings_profile_face.xml", "profile_code_failed"))
        val tab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        val dialog = tab.substringAfter("private fun MyCodeDialog(").substringBefore("private fun qrBitmap(")
        assertTrue("MyCodeDialog must bound loading with a timeout", "withTimeoutOrNull(" in dialog)
        assertTrue("MyCodeDialog must show profile_code_failed on failure", "R.string.profile_code_failed" in dialog)
        assertTrue("MyCodeDialog must show a retry action", "R.string.delete_everything_retry" in dialog)
        assertTrue("retry must call refreshInvite()", "container.mesh.refreshInvite()" in dialog)
        assertTrue("Send stays disabled until a code exists", "confirmEnabled = qr != null" in dialog || "confirmEnabled = inv != null" in dialog)
    }

    @Test
    fun `item 30 - onNameDone keeps naming true when save fails and shows error in all locales`() {
        assertEquals("Not saved", string("values", "strings_profile_face.xml", "profile_name_save_failed"))
        assertEquals("Не збережено", string("values-uk", "strings_profile_face.xml", "profile_name_save_failed"))
        assertEquals("Neuloženo", string("values-cs", "strings_profile_face.xml", "profile_name_save_failed"))
        val tab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        val onNameDone = tab.substringAfter("onNameDone = { draft ->").substringBefore("onNameCancel =")
        assertTrue("onNameDone must check saved != null", "if (saved != null)" in onNameDone)
        val successBranch = onNameDone.substringAfter("if (saved != null) {").substringBefore("} else")
        val elseBranch = onNameDone.substringAfter("} else {").substringBefore("}")
        assertTrue("naming = false must be in success branch", "naming = false" in successBranch)
        assertFalse("naming = false must not be in failure branch", "naming = false" in elseBranch)
        assertEquals("naming = false must appear only once in onNameDone", 1, onNameDone.split("naming = false").size - 1)
        assertTrue("failure branch sets nameSaveFailed = true", "nameSaveFailed = true" in elseBranch)
        val editor = tab.substringAfter("private fun NameEditor(").substringBefore("private fun MomentsCard(")
        assertTrue("NameEditor shows profile_name_save_failed", "R.string.profile_name_save_failed" in editor)
    }

    @Test
    fun `item 31 - NameStep and ProfileTab share CAP_QR_NAME == 32 from a single source`() {
        assertEquals(32, PeerName.CAP_QR_NAME)
        val nameStep = read("feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/NameStep.kt")
        val profileTab = read("app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt")
        assertTrue("NameStep imports CAP_QR_NAME from PeerName", "import local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME" in nameStep)
        assertTrue("ProfileTab imports CAP_QR_NAME from PeerName", "import local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME" in profileTab)
        assertFalse("ProfileTab must not declare its own const val CAP_QR_NAME", "const val CAP_QR_NAME" in profileTab)
        assertFalse("NameStep must not use MAX_DISPLAY_NAME_LENGTH", "MAX_DISPLAY_NAME_LENGTH" in nameStep)
    }
}
