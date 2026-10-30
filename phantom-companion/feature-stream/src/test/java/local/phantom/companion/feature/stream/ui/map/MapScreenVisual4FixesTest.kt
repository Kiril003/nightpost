package local.phantom.companion.feature.stream.ui.map

import androidx.compose.ui.graphics.Color
import java.io.File
import local.phantom.companion.core.design.theme.PhantomAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapScreenVisual4FixesTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private val src: String by lazy {
        File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/MapScreen.kt").readText()
    }

    private fun scrimBlock(): String {
        val at = src.indexOf("statusBarScrimColor(look.canvas)")
        assertTrue("MapScreen paints no status-bar scrim", at >= 0)
        return src.substring(src.lastIndexOf("Box(", at), src.indexOf("\n        )", at))
    }

    @Test
    fun `statusBarScrimColor applies PhantomAlpha scrim to canvas color`() {
        val canvas = Color(0xFF101418)
        val scrim = statusBarScrimColor(canvas)
        assertEquals(canvas.red, scrim.red, 1e-4f)
        assertEquals(canvas.green, scrim.green, 1e-4f)
        assertEquals(canvas.blue, scrim.blue, 1e-4f)
        assertEquals(PhantomAlpha.Near, scrim.alpha, 0.01f)
    }

    @Test
    fun `the scrim lies over the map and under the chrome`() {
        val canvas = src.indexOf("MapCanvas(")
        val scrim = src.indexOf("statusBarScrimColor(look.canvas)")
        val chrome = src.indexOf("MapChrome(")
        assertTrue("canvas $canvas, scrim $scrim, chrome $chrome", canvas in 0 until scrim && scrim < chrome)
    }

    @Test
    fun `the scrim is one flat strip the height of the status bar`() {
        val block = scrimBlock()
        assertTrue(block, block.contains(".height(WindowInsets.statusBars.asPaddingValues().calculateTopPadding())"))
        assertFalse("scrim is not flat: $block", block.contains("Brush") || block.contains("radient"))
    }
}
