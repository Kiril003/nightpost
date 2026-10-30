package local.phantom.companion.service

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TalkTileWiringTest {

    private fun appFile(relPath: String): File {
        val candidates = listOf(
            File("src/main/$relPath"),
            File("app/src/main/$relPath"),
            File("phantom-companion/app/src/main/$relPath"),
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Cannot locate $relPath from ${File(".").absolutePath}")
    }

    @Test
    fun `manifest declares TalkTile with BIND_QUICK_SETTINGS_TILE and QS_TILE`() {
        val manifest = appFile("AndroidManifest.xml").readText()
        val block = Regex(
            """<service\s+[^>]*android:name="\.service\.TalkTile"[\s\S]*?</service>""",
        ).find(manifest)?.value.orEmpty()
        assertTrue(block.isNotEmpty())
        assertTrue(block.contains("android.permission.BIND_QUICK_SETTINGS_TILE"))
        assertTrue(block.contains("android.service.quicksettings.action.QS_TILE"))
        assertTrue(block.contains("""android:exported="true""""))
        assertTrue(block.contains("""android:icon="@drawable/lc_phantom_mark""""))
        assertTrue(block.contains("""android:label="@string/call_phantom_title""""))
    }

    @Test
    fun `MainActivity routeIntent delegates to TalkEntry route`() {
        val mainActivity = appFile("java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue(mainActivity.contains("TalkEntry.route("))
    }

    @Test
    fun `TalkTile opens TalkEntry intent`() {
        val tile = appFile("java/local/phantom/companion/service/TalkTile.kt").readText()
        assertTrue(tile.contains("TalkEntry.intent("))
    }
}
