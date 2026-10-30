package local.phantom.companion.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * «Вигляд → Ніч» на A52 (02.10) не робив нічого: пункт навігації брав
 * PhantomViewModel голим `viewModel()`, тобто СВІЙ екземпляр у межах пункту,
 * а тему малює екземпляр MainActivity. Тема писалась у префи й чекала
 * перезапуску, сегмент казав «Ніч», а рядок «Вигляд» у Профілі — «за сонцем».
 *
 * Екрани Профілю мусять брати той самий екземпляр, що й активність.
 */
class ProfileThemeScopeTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("корінь дерева не знайдено")
    }

    private fun src(path: String) = File(repoRoot(), "app/src/main/java/local/phantom/companion/$path").readText()

    private val bare = Regex("""viewModel\(\s*factory\s*=\s*PhantomViewModel\.Factory\s*\)""")

    @Test
    fun `Профіль і його розділи беруть PhantomViewModel активності, а не пункту навігації`() {
        // 04.10: SettingsDestination розпущено в розділи Профілю — лишились двоє.
        for (file in listOf("ui/home/ProfileSettings.kt", "ui/home/ProfileSections.kt")) {
            val text = src(file)
            assertEquals("$file: голий viewModel(PhantomViewModel) — тема знову не дійде до активності", 0, bare.findAll(text).count())
            assertTrue("$file не бере activityPhantomVm()", text.contains("activityPhantomVm()"))
        }
        val kit = src("ui/home/ProfileKit.kt")
        assertTrue("activityPhantomVm не привʼязаний до активності", kit.contains("viewModelStoreOwner = owner"))
    }
}
