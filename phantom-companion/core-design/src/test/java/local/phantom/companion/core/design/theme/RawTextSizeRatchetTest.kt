package local.phantom.companion.core.design.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The type scale, held shut.
 *
 * 521 call sites still write `fontSize = 13.sp` by hand. That is not laziness:
 * the four original roles left a hole between the 11sp caption and the 16sp
 * prose, which is exactly where a row label and a card line live, so every
 * screen invented its own number. [PhantomBody] fills the hole; this test
 * makes sure the debt only ever shrinks while the screens migrate.
 *
 * Lower is always fine — drop the ceiling in the same commit. Raising it is a
 * design-review decision, and the reason belongs in the commit message.
 */
class RawTextSizeRatchetTest {

    // 2026-08-15 (+3 → 524): mount mode's windscreen readout (MountGlanceScreen's
    // DistanceFact/BigStatus — 84sp/22sp/30sp) — no role reaches within 5x of
    // these; they exist specifically to be read at a glance at driving speed,
    // which none of the five roles was sized for. Everything else that landed
    // in this wave (Provenance sheet/popover, the mount hint, the register
    // chips) migrated onto PhantomBody/PhantomCaption/PhantomProse/PhantomDataText.
    private val ceiling = 524

    private val roots = listOf(
        "app/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-precision/src/main",
        "feature-onboarding/src/main",
        "feature-ambient/src/main",
    )

    private val rawSize = Regex("""fontSize\s*=\s*[0-9.]+\.sp""")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    @Test
    fun `hand-rolled text sizes never grow`() {
        val root = repoRoot()
        val total = roots
            .map { File(root, it) }
            .filter { it.isDirectory }
            .sumOf { dir ->
                dir.walkTopDown()
                    .filter { it.isFile && it.extension == "kt" }
                    .sumOf { rawSize.findAll(it.readText()).count() }
            }
        assertTrue(
            "hand-rolled fontSize is $total, ceiling says $ceiling.\n" +
                "  ↑ rose: use a text role (PhantomKicker / PhantomCaption / " +
                "PhantomBody / PhantomProse / PhantomDataText) instead of a number.\n" +
                "  ↓ fell: good. Set ceiling = $total to keep the ground.",
            total <= ceiling,
        )
    }

    @Test
    fun `the scale has no hole a screen would route around`() {
        // The gap between caption and prose is what produced the 587. If a
        // future edit removes PhantomBody, the drift starts again.
        val root = repoRoot()
        val roles = File(
            root,
            "core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTextRoles.kt",
        ).readText()
        assertTrue("PhantomBody is the workhorse role; without it screens improvise",
            "fun PhantomBody(" in roles)
    }
}
