package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Стиль D: глибина поверхонь над мапою — тон і волосина, без тіней, розмиття й
 * градієнтів. Картографія (символи, рельєф) малюється шейдерами android.graphics
 * і сюди не входить: сторож дивиться лише на Compose-хром.
 */
class MapChromeFlatFirewallTest {

    private fun mapDir(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val map = File(dir, "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
        assertTrue("ui/map not found: $map", map.isDirectory)
        return map
    }

    @Test fun mapChromeHasNoShadowBlurOrGradient() {
        val banned = Regex("""\.shadow\(|\.blur\(|shadowElevation|Brush\.""")
        val hits = mapDir().walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex().filter { (_, l) ->
                val t = l.trimStart()
                !t.startsWith("//") && !t.startsWith("*") && !t.startsWith("import ") && banned.containsMatchIn(l)
            }.map { (i, l) -> "${f.name}:${i + 1}: ${l.trim()}" }
        }.toList()
        assertTrue("map chrome effects:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test fun floatingSurfacesKeepAHairline() {
        for ((file, mark) in listOf(
            "MapControls.kt" to "MapChromeMetrics.Control",
            "MapHomeControls.kt" to "PhantomSize.input",
            "MapSearchSheetShell.kt" to "fillMaxHeight(fraction)",
        )) {
            val src = File(mapDir(), file).readText()
            val at = src.indexOf(mark)
            assertTrue("$mark not found in $file", at >= 0)
            assertTrue("$file: surface at $mark lost its hairline", src.substring(at, at + 400).contains(".border("))
        }
    }
}
