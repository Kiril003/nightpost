package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The legibility ratchet. Every text/background pair the operator actually
 * reads is measured here in WCAG 2.x terms and pinned.
 *
 * It exists because a palette can be internally beautiful and still ship
 * unreadable: SunriseWarm's `userBubbleFg` was a near-white pinned against a
 * `userBubbleBg` that no renderer drew, so the pair audited at 11.8:1 while
 * the screen showed 1.20:1. A colour token is only true against the colour it
 * actually lands on — so every pair below composites its real backdrop first.
 *
 * Thresholds: AA body text is 4.5:1, AA large/secondary is 3.0:1. PHANTOM is
 * a field instrument read in daylight, so secondary ink is held to body AA
 * too; only the deliberately-receded disabled ink may sit at 3.0.
 */
class ContrastFirewallTest {

    private companion object {
        const val AA_BODY = 4.5
        const val AA_LARGE = 3.0
    }

    /** Every palette the product can resolve to. */
    private val palettes = listOf(
        "sunrise-warm" to SunriseWarm,
        "day-clear" to DayClear,
        "dawn-bloom" to DawnBloom,
        "amber-night" to AmberNight,
        "dusk-ember" to DuskEmber,
    )

    private fun contrast(fg: Color, bg: Color): Double {
        val f = fg.compositeOver(bg).luminance().toDouble()
        val b = bg.luminance().toDouble()
        val hi = maxOf(f, b)
        val lo = minOf(f, b)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertAtLeast(pair: String, fg: Color, bg: Color, min: Double) {
        val r = contrast(fg, bg)
        assertTrue(
            "$pair reads at %.2f:1 — WCAG needs %.1f:1. ".format(r, min) +
                "Darken/lighten the ink or the surface; do not lower this bar.",
            r >= min,
        )
    }

    /* ── the bug this file was born from ────────────────────────────── */

    @Test
    fun `the operator's own words clear AA on every palette`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: userBubbleFg on the seal", p.userBubbleFg, p.userBubbleBg, AA_BODY)
        }
    }

    /**
     * The numbers, pinned. A drift in `primary`, `surfaceDeep` or
     * `userBubbleFg` moves these — and moving them is a design decision that
     * has to be made on purpose, not noticed on hardware.
     *
     * 03.10, native theme bridge: every palette is a v1.2 token set now, so
     * there are two numbers, not five — `text` on the accent wash over
     * `surface`: «Ніч» 12.37 (was 13.48 on the brown night), «День» 16.92 (was
     * 13.77–14.27 on the creams, 12.42 at dawn). The aliases measure their
     * parent's number by construction.
     * 14.10 (6b7b5a0a): surface FFFFFF by day, 161412 by night — 14.68 and 12.18.
     * 17.10 look D: paper FCFAF5 + amber C27A16 by day, graphite 1C1D21 + E2A048 by night — 14.16 and 10.60.
     */
    @Test
    fun `operator seal ratios are the measured ones`() {
        val expected = mapOf(
            "sunrise-warm" to 14.16,
            "day-clear" to 14.16,
            "dawn-bloom" to 14.16,
            "amber-night" to 10.60,
            "dusk-ember" to 10.60,
        )
        palettes.forEach { (id, p) ->
            assertEquals(
                "$id operator-seal contrast moved",
                expected.getValue(id),
                contrast(p.userBubbleFg, p.userBubbleBg),
                0.01,
            )
        }
    }

    /* ── PHANTOM's own prose, and the quiet ink beside it ───────────── */

    @Test
    fun `assistant prose clears AA on its pane`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: ink on glassPanel", p.ink, p.glassPanel, AA_BODY)
            assertAtLeast("$id: ink on surfaceDeep", p.ink, p.surfaceDeep, AA_BODY)
        }
    }

    @Test
    fun `secondary ink clears AA everywhere it is used`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: inkSecondary on glassPanel", p.inkSecondary, p.glassPanel, AA_BODY)
            assertAtLeast("$id: inkSecondary on surfaceDeep", p.inkSecondary, p.surfaceDeep, AA_BODY)
            assertAtLeast("$id: inkSecondary on surfaceBase", p.inkSecondary, p.surfaceBase, AA_BODY)
        }
    }

    @Test
    fun `disabled ink stays readable rather than disappearing`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: inkDisabled on glassPanel", p.inkDisabled, p.glassPanel, AA_LARGE)
            assertAtLeast("$id: inkDisabled on surfaceDeep", p.inkDisabled, p.surfaceDeep, AA_LARGE)
        }
    }

    /* ── ink that lands on the accent ───────────────────────────────── */

    @Test
    fun `ink on a filled accent clears AA`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: onAccentInk on primary", p.primary.onAccentInk(), p.primary, AA_BODY)
            assertAtLeast(
                "$id: onAccentInk on accentSharp",
                p.accentSharp.onAccentInk(), p.accentSharp, AA_BODY,
            )
        }
    }

    /**
     * The other accent question, which nothing was asking. Above measures ink
     * drawn ON a filled accent; `accentSharp` is also read as WORDS at some
     * twenty-five call sites — section headings, kickers, focused field
     * labels, chart series names — and as the border of a focused control.
     * That is the pair an operator actually reads, and it went unmeasured
     * while the fill-side pair had a test.
     */
    @Test
    fun `the accent reads as ink on the page`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: accentSharp on surfaceDeep", p.accentSharp, p.surfaceDeep, AA_BODY)
            assertAtLeast("$id: accentSharp on glassPanel", p.accentSharp, p.glassPanel, AA_BODY)
            assertAtLeast("$id: accentSharp on surfaceBase", p.accentSharp, p.surfaceBase, AA_BODY)
        }
    }

    /**
     * `success` / `coral` are read as words ("ГОТОВО", "УВАГА"), not just
     * painted as dots, so they answer to the same bar as any other ink.
     */
    @Test
    fun `semantic hues clear AA as text on the page`() {
        palettes.forEach { (id, p) ->
            assertAtLeast("$id: success on surfaceDeep", p.success, p.surfaceDeep, AA_BODY)
            assertAtLeast("$id: success on glassPanel", p.success, p.glassPanel, AA_BODY)
            assertAtLeast("$id: coral on surfaceDeep", p.coral, p.surfaceDeep, AA_BODY)
            assertAtLeast("$id: coral on glassPanel", p.coral, p.glassPanel, AA_BODY)
        }
    }

    /* ── the night-comfort morph must not spend the margin ──────────── */

    @Test
    fun `deep-night softening keeps every pair legible`() {
        val night = AmberNight.morphForNight(true)
        assertAtLeast("deep night: ink on glassPanel", night.ink, night.glassPanel, AA_BODY)
        assertAtLeast("deep night: userBubbleFg on seal", night.userBubbleFg, night.userBubbleBg, AA_BODY)
        assertAtLeast("deep night: inkSecondary on surfaceDeep", night.inkSecondary, night.surfaceDeep, AA_BODY)
    }

    /**
     * A palette added to `PhantomTheme.kt` and forgotten here would ship
     * unmeasured — which is exactly how this bug shipped. Count the
     * declarations in the source and refuse to be out of date.
     */
    @Test
    fun `every declared palette is under measurement`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-design").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root not found") as Nothing
        val src = File(
            root,
            "core-design/src/main/java/local/phantom/companion/core/design/theme/PhantomTheme.kt",
        ).readText()
        val declared = Regex("""^val \w+ = (PhantomThemeSpec\(|\w+\.copy\()""", RegexOption.MULTILINE)
            .findAll(src).count()
        assertEquals(
            "PhantomTheme.kt declares $declared palettes, this test measures " +
                "${palettes.size}. Add the new palette to `palettes` — an " +
                "unmeasured palette is an unreadable palette waiting to happen.",
            declared,
            palettes.size,
        )
    }
}
