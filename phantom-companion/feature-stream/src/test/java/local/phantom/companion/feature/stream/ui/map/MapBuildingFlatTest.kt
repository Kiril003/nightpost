package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapBuildingFlatTest {

    private val src = File("src/main/java/local/phantom/companion/feature/stream/ui/map/MapTerrainLayers.kt").readText()

    private fun body(name: String): String {
        val start = src.indexOf("internal fun $name(")
        assertTrue("$name зник", start >= 0)
        return src.substring(start, src.indexOf("\n}\n", start))
    }

    @Test
    fun `buildings are flat shaded - no vertical gradient`() {
        assertFalse(src.contains("fillExtrusionVerticalGradient(true)"))
        assertTrue(body("installBuildingExtrusions").contains("fillExtrusionVerticalGradient(false)"))
    }

    @Test
    fun `the volume takes the D roof token, lit from a fixed north-west`() {
        val theme = body("applyBuildingTheme")
        assertTrue(theme.contains("fillExtrusionColor(MapTokens.roof(isDark))"))
        assertFalse(Regex("\"#[0-9A-Fa-f]{6}\"").containsMatchIn(theme))
        assertTrue(theme.contains("ANCHOR_MAP"))
        assertEquals("#D4C9B8", MapTokens.roof(isDark = false))
        assertEquals("#3A3E47", MapTokens.roof(isDark = true))
    }
}
