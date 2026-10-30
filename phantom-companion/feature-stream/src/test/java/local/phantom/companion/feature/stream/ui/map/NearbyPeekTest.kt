package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPeekTest {

    private fun src(name: String) = File("src/main/java/local/phantom/companion/feature/stream/ui/map/$name").readText()

    @Test
    fun `drag down folds the panel to its peek, drag up opens it`() {
        assertEquals(true, NearbyPeek.afterDrag(collapsed = false, dragDp = 60f))
        assertEquals(false, NearbyPeek.afterDrag(collapsed = true, dragDp = -60f))
    }

    @Test
    fun `a twitch does not flip the panel`() {
        assertEquals(false, NearbyPeek.afterDrag(collapsed = false, dragDp = 6f))
        assertEquals(true, NearbyPeek.afterDrag(collapsed = true, dragDp = -6f))
    }

    @Test
    fun `the panel folds its lists and remembers the choice`() {
        val bar = src("MapHomeControls.kt").substringAfter("internal fun MapHomePeekBar(").substringBefore("/** Labeled map actions")
        assertTrue(bar.contains("if (!collapsed)"))
        assertTrue(bar.contains("NearbyPeek.afterDrag("))
        assertTrue(bar.contains("onCollapsedChange(!collapsed)"))
        val chrome = src("MapChrome.kt")
        assertTrue(chrome.contains("NearbyPeek.read(chromeContext)"))
        assertTrue(chrome.contains("NearbyPeek.write(chromeContext, it)"))
        assertTrue(chrome.contains("collapsed = peekCollapsed"))
    }

    @Test
    fun `the handle has words in every language`() {
        listOf("values", "values-uk", "values-cs").forEach { dir ->
            val xml = File("src/main/res/$dir/strings_map_around.xml").readText()
            assertTrue(dir, xml.contains("name=\"map_peek_collapse\""))
            assertTrue(dir, xml.contains("name=\"map_peek_expand\""))
        }
    }
}
