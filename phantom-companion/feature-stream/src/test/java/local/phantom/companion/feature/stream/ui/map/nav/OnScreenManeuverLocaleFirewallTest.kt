package local.phantom.companion.feature.stream.ui.map.nav

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The banner, the step list and the mount glance are read in the app language.
 * The Ukrainian-only builders stay for speech; on screen they leaked Ukrainian
 * into the English app (EN is the default language).
 */
class OnScreenManeuverLocaleFirewallTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val base = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map"

    @Test
    fun `on-screen maneuver text follows the configuration locale`() {
        listOf("nav/GuidanceChrome.kt", "nav/ManeuverListSheet.kt", "mount/MountGlanceScreen.kt").forEach { rel ->
            val src = File(repoRoot(), "$base/$rel").readText()
            assertFalse("$rel still calls a Ukrainian-only builder", Regex("""maneuver(Action|Short)Uk\(""").containsMatchIn(src))
            assertTrue("$rel lost its maneuver text", src.contains("maneuverLines("))
            assertTrue("$rel does not read the app language", src.contains("LocalConfiguration.current.locales[0]"))
        }
    }
}
