package local.phantom.companion.feature.stream.ui.people

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Власник 20.10: знімки екрана дозволені в усьому застосунку; блок лише на
 * секретному медіа й на словах гаманця.
 */
class ScreenshotsAllowedTest {

    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь не знайдено") as Nothing
    }

    private fun code(path: String): String {
        val f = File(root(), path)
        assertTrue("$path не знайдено", f.isFile)
        return f.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }.joinToString("\n")
    }

    private val app = "app/src/main/java/local/phantom/companion"
    private val people = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people"

    @Test
    fun `chats, people, scanner and app lock do not block screenshots`() {
        val blocking = listOf(
            "$app/MainActivity.kt",
            "$app/ui/home/HomeScreen.kt",
            "$app/navigation/PeerCardDestination.kt",
            "$people/PeerLock.kt",
            "$people/PeerScanSheet.kt",
        ).filter { code(it).contains("FLAG_SECURE") || code(it).contains("PeerSurface") }
        assertEquals(emptyList<String>(), blocking)
    }

    @Test
    fun `secret media and wallet words still block screenshots`() {
        assertTrue(code("$app/ui/messenger/preview/PreviewViewerActivity.kt").contains("if (locked) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)"))
        assertTrue(code("core-solana/src/main/java/local/phantom/companion/core/solana/WalletActivity.kt").contains("FLAG_SECURE"))
    }
}
