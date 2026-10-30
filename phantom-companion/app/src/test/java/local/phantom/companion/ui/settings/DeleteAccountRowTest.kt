package local.phantom.companion.ui.settings

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeleteAccountRowTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("root not found")
    }

    @Test
    fun `debug build without the toggle has no row`() {
        org.junit.Assert.assertFalse(
            local.phantom.companion.ui.home.deleteEverythingVisible(isDebug = true, unlocked = false),
        )
        assertTrue(
            local.phantom.companion.ui.home.deleteEverythingVisible(isDebug = true, unlocked = true),
        )
        assertTrue(
            local.phantom.companion.ui.home.deleteEverythingVisible(isDebug = false, unlocked = false),
        )
        assertTrue(
            local.phantom.companion.ui.home.deleteEverythingVisible(isDebug = false, unlocked = true),
        )
        val sections = File(
            tree,
            "app/src/main/java/local/phantom/companion/ui/home/ProfileSections.kt",
        ).readText()
        val dataSection = sections.substringAfter("private fun DataSection(").substringBefore("@Composable")
        assertTrue(dataSection.contains("deleteEverythingVisible(BuildConfig.DEBUG,"))
    }

    @Test
    fun `delete account opens the site page`() {
        val intent = DeleteAccount.intent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://phantom-os.dev/delete-account", intent.dataString)
        assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
    }

    @Test
    fun `licence screen no longer mounts the row`() {
        val screen = File(
            tree,
            "app/src/main/java/local/phantom/companion/ui/settings/LicenseScreen.kt",
        ).readText()
        org.junit.Assert.assertFalse(screen.contains("DeleteAccountRow()"))
    }

    @Test
    fun `strings exist in en uk cs`() {
        val expected = setOf(
            "delete_everything",
            "delete_everything_after",
            "delete_everything_left_backup",
            "delete_everything_left_photos",
            "delete_everything_left_letters",
            "delete_everything_pc",
            "delete_everything_retry",
            "delete_everything_phone_only",
            "delete_everything_protected",
            "delete_everything_allow",
            "delete_pending_on",
            "delete_pending_cancel",
            "delete_everything_stays",
            "delete_everything_cancel",
            "delete_everything_phone_only_note",
            "delete_pending_note",
            "delete_pending_offline",
            "delete_pending_failed",
            "delete_pending_wallet",
        )
        listOf("values", "values-uk", "values-cs").forEach { dir ->
            val file = File(tree, "app/src/main/res/$dir/strings_delete_account.xml")
            assertTrue("missing ${file.path}", file.isFile)
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
            val nodes = doc.getElementsByTagName("string")
            val names = (0 until nodes.length).map { nodes.item(it).attributes.getNamedItem("name").nodeValue }.toSet()
            assertEquals("keys in $dir", expected, names)
            for (i in 0 until nodes.length) {
                val text = nodes.item(i).textContent
                org.junit.Assert.assertFalse("forbidden word in $dir: $text", text.contains("Phantom", ignoreCase = true))
                org.junit.Assert.assertFalse("forbidden word in $dir: $text", text.contains("Circles", ignoreCase = true))
            }
        }
    }

    private fun rowSource(): String = File(
        tree,
        "app/src/main/java/local/phantom/companion/ui/settings/DeleteEverythingRow.kt",
    ).readText()

    @Test
    fun `confirm names what stays and can be cancelled`() {
        val confirm = rowSource().substringAfter("DangerZoneController.Phase.FirstConfirm,")
            .substringBefore("when (outcome)")
        val caption = confirm.indexOf("R.string.delete_everything_stays")
        assertTrue("no caption above the rows that stay", caption >= 0)
        assertTrue(caption < confirm.indexOf("R.string.delete_everything_left_backup"))
        assertTrue("no Cancel on FirstConfirm/Cooldown", confirm.contains("controller.cancel()"))
    }

    @Test
    fun `this phone only says what it erases first`() {
        val failed = rowSource().substringAfter("is EraseEverything.Outcome.Failed ->")
            .substringBefore("EraseEverything.Outcome.Protected ->")
        val note = failed.indexOf("R.string.delete_everything_phone_only_note")
        assertTrue("no consequence line", note >= 0)
        assertTrue(note < failed.indexOf("R.string.delete_everything_phone_only)"))
    }

    @Test
    fun `pending screen explains the state and retries a failed cancel`() {
        val pending = rowSource().substringAfter("internal fun PendingDeletionScreen(")
        assertTrue(pending.contains("R.string.delete_pending_note"))
        assertTrue(pending.contains("failure = cancelFailureText(res)"))
        assertTrue(pending.contains("R.string.delete_everything_retry"))
        assertTrue(pending.contains("WalletActivity::class.java"))
        val main = File(tree, "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue(main.contains("PendingDeletionScreen("))
    }
}
