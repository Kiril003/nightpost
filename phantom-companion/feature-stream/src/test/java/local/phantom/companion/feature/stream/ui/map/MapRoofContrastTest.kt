package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class MapRoofContrastTest {

    private fun lum(rgb: Int): Double {
        fun ch(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * ch(rgb shr 16 and 0xFF) + 0.7152 * ch(rgb shr 8 and 0xFF) + 0.0722 * ch(rgb and 0xFF)
    }

    private fun contrast(a: Int, b: Int): Double {
        val la = lum(a); val lb = lum(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    @Test
    fun `a building volume stands off the ground it rises from, Day and Night`() {
        listOf(false, true).forEach { isDark ->
            val roof = MapTokens.roof(isDark).removePrefix("#").toInt(16)
            val p = groundPalette(isDark)
            mapOf("земля" to p.land, "забудова" to p.residential).forEach { (name, g) ->
                val c = contrast(roof, g and 0xFFFFFF)
                assertTrue("дах на «$name» isDark=$isDark: ${"%.2f".format(c)}:1", c >= 1.25)
            }
        }
    }
}
