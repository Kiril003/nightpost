package local.phantom.companion.core.design.theme

import java.io.File
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [PhantomPalette] is the fixed, composition-free half of the theme — the hue a
 * notification or a map layer paints. Since the native theme bridge (03.10)
 * each name is its nearest v1.2 token, so a notification and the screen it
 * opens are the same amber; before it, they were `docs/night/PROTOTYPE_ETALON.html`'s
 * #F4AF25 and #00E676, which nothing on a v1.2 screen draws.
 *
 * Then the ratchet: a token nobody reads is furniture, and this object used to
 * carry fourteen of them once AmberNight stopped reading it.
 */
class PhantomPaletteTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root not found") as Nothing
    }

    @Test
    fun `every fixed hue is its v1_2 token`() {
        // Amber/Coral/Green left at the 04.10 integration: nothing read them.
        assertEquals("Violet → actMute", NightColors.actMute, PhantomPalette.Violet)
    }

    /**
     * Amber on an edge is only a signal while it stays a fraction of itself.
     * The etalon never draws it stronger than `.45`, and only on a chip; the
     * operator's seal is `.28` and its wash runs `.2 → .1`. These constants are
     * the app's whole amber-edge vocabulary, so they answer to those numbers.
     */
    @Test
    fun `the amber edge stays inside the prototype's own weights`() {
        val html = File(repoRoot(), "docs/night/PROTOTYPE_ETALON.html").readText()
        val borderAlphas = Regex("""border(?:-color)?\s*:[^;]*rgba\(244,175,37,\s*([\d.]+)\)""")
            .findAll(html).map { it.groupValues[1].toFloat() }.toList()
        assertTrue("the etalon draws no amber borders any more", borderAlphas.isNotEmpty())

        fun declared(a: Float) = borderAlphas.any { kotlin.math.abs(it - a) < 1e-4f }

        // Above .45 the etalon is only ever drawing the orb's rings — circles
        // a few dozen pixels across, not a surface with content in it.
        assertTrue(
            "ACCENT_BORDER_ALPHA ($ACCENT_BORDER_ALPHA) is not a weight the " +
                "etalon uses, or is stronger than anything it puts on a surface",
            declared(ACCENT_BORDER_ALPHA) && ACCENT_BORDER_ALPHA <= 0.45f,
        )
        assertTrue(
            "USER_SEAL_BORDER_ALPHA ($USER_SEAL_BORDER_ALPHA) must be one of the " +
                "etalon's own border weights",
            borderAlphas.any { kotlin.math.abs(it - USER_SEAL_BORDER_ALPHA) < 1e-4f },
        )
        assertTrue(
            "the seal's fill must sit between the etalon's two gradient stops",
            USER_SEAL_ALPHA in 0.1f..0.2f,
        )
    }

    /**
     * The ratchet. A token nobody reads is not a design system, it is a list of
     * colours — which is exactly what this object was.
     */
    @Test
    fun `no token sits here unread`() {
        val src = File(
            repoRoot(),
            "core-design/src/main/java/local/phantom/companion/core/design",
        )
        val declared = Regex("""^\s{4}val (\w+) = """, RegexOption.MULTILINE)
            .findAll(File(src, "theme/PhantomPalette.kt").readText())
            .map { it.groupValues[1] }
            .toList()
        assertTrue("PhantomPalette declares no tokens at all", declared.isNotEmpty())

        // Readers live in :app and the features now (notifications, the vault,
        // the peer-route layer), so the whole tree's main sources are asked.
        val readers = repoRoot().listFiles().orEmpty()
            .map { File(it, "src/main") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { it.name != "PhantomPalette.kt" }
            .joinToString("\n") { it.readText() }
        assertTrue("no main sources found — the ratchet would be green about nothing", readers.isNotEmpty())

        val unread = declared.filterNot { readers.contains("PhantomPalette.$it") }
        assertTrue(
            "unread tokens: $unread. Wire them to something or delete them — a " +
                "dead token that looks like a design system is worse than none.",
            unread.isEmpty(),
        )
    }
}
