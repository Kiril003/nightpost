package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * `theme.primary` is a FILL. Set as a text colour it measures 1.6:1 on the
 * light palettes — unreadable — because it was tuned to be painted on, not
 * read. `accentSharp` is the ink version and every palette tunes it to
 * survive its own ground.
 *
 * A sweep fixed 39 sites; without a ratchet the next screen puts one back.
 * Only `Text(...)` is counted: an icon tint, a stroke, a sparkline or a
 * waveform is a graphic, and the fill token is right for those.
 */
class AccentAsInkFirewallTest {

    // Zero, and it may never rise. The last survivor was Model3DCardWidget's
    // `3D · LIVE` chip, which left with the hardcoded model it was labelling.
    private val accentTextCeiling = 0

    private val uiRoots = listOf(
        "core-design/src/main", "app/src/main", "feature-stream/src/main",
        "feature-godmode/src/main", "feature-ambient/src/main",
        "feature-onboarding/src/main", "feature-precision/src/main",
        "feature-car/src/main",
    )

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    /** Balanced-paren scan: a Text( call can nest lambdas and other calls. */
    private fun textCallBodies(src: String): List<String> {
        val bodies = mutableListOf<String>()
        Regex("""\bText\s*\(""").findAll(src).forEach { m ->
            var depth = 1
            var j = m.range.last + 1
            while (j < src.length && depth > 0) {
                when (src[j]) { '(' -> depth++; ')' -> depth-- }
                j++
            }
            bodies += src.substring(m.range.last + 1, j)
        }
        return bodies
    }

    private val accentAsColor = Regex("""color\s*=\s*theme\.primary\b(?!\s*Soft|\s*Deep)""")

    @Test
    fun `the accent fill is never read as body text`() {
        val offenders = mutableListOf<String>()
        for (root in uiRoots) {
            val dir = File(repoRoot(), root)
            // Передумова: тека мусить існувати. `continue` тут робив сторожа
            // зеленим ні про що, щойно пакет перенесуть.
            if (!dir.isDirectory) fail("сторож дивиться в порожнє: не існує $root")
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                val src = f.readText()
                textCallBodies(src).forEach { body ->
                    if (accentAsColor.containsMatchIn(body)) offenders += f.name
                }
            }
        }
        assertEquals(
            "Text() coloured with theme.primary is ${offenders.size}, ceiling says " +
                "$accentTextCeiling.\n  ↑ rose: use theme.accentSharp — primary is a fill and " +
                "measures 1.6:1 as ink on the light palettes.\n  ↓ fell: good, lower the " +
                "ceiling.\n  sites: ${offenders.joinToString()}",
            accentTextCeiling,
            offenders.size,
        )
    }
}
