package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W1.5-1 — pin every PhantomTheme field to its source of truth so a drift
 * gets caught at CI time.
 *
 * The source of truth moved on 03.10 (native theme bridge): it is no longer
 * `PhantomPalette` / the `docs/night/PROTOTYPE_ETALON.html` brown night and
 * the `Phantom v3.dc.html` cream day, but the generated v1.2 tokens —
 * [NightColors] («Ніч») and [DayColors] («День»). A literal re-inlined into a
 * spec breaks this, which is the point: that is how the old ground, ink, card
 * and hairline drifted off the prototype they quoted the last time.
 * `PhantomThemeBridgeTest` holds the full 17-field table; this file keeps the
 * surface/ink/amber groups readable on their own.
 */
class PhantomThemeAuditTest {

    /* ---- «Ніч» ---- */

    @Test fun `amber-night resolves its whole surface from the night tokens`() {
        assertEquals(NightColors.canvas, AmberNight.surfaceBase)
        assertEquals(NightColors.surface, AmberNight.surfaceDeep)
        assertEquals(NightColors.raised, AmberNight.glassPanel)
        assertEquals(NightColors.line, AmberNight.glassBorder)
    }

    @Test fun `amber-night resolves its ink from the night tokens`() {
        assertEquals(NightColors.text, AmberNight.ink)
        assertEquals(NightColors.text, AmberNight.inkStrong)
        assertEquals(NightColors.text2, AmberNight.inkSecondary)
        assertEquals(NightColors.text, AmberNight.userBubbleFg)
    }

    @Test fun `amber-night resolves its amber from the night tokens`() {
        assertEquals(NightColors.accent, AmberNight.primary)
        assertEquals(NightColors.accentPress, AmberNight.primarySoft)
        assertEquals(NightColors.accentText, AmberNight.primaryDeep)
        assertEquals(NightColors.accentText, AmberNight.accentSharp)
    }

    @Test fun `amber-night semantic hues are the night tokens`() {
        assertEquals(NightColors.danger, AmberNight.coral)
        assertEquals(NightColors.danger, AmberNight.coralDeep)
        assertEquals(NightColors.success, AmberNight.success)
    }

    /** v1.2 is flat: no glow, no lit-from-above highlight, on any palette. */
    @Test fun `no palette carries a glow or a highlight`() {
        for (p in listOf(AmberNight, DuskEmber, DayClear, SunriseWarm, DawnBloom)) {
            assertEquals("${p.id} glowPrimary", Color.Transparent, p.glowPrimary)
            assertEquals("${p.id} glassHighlight", Color.Transparent, p.glassHighlight)
        }
    }

    /** Derived, not stored — the amber wash `.msg.me` actually paints. */
    @Test fun `amber-night user-bubble-bg is the seal the renderer draws`() =
        assertEquals(
            AmberNight.primary.copy(alpha = USER_SEAL_ALPHA).compositeOver(AmberNight.surfaceDeep),
            AmberNight.userBubbleBg,
        )

    /* ---- «День» ---- */

    /** The accent is one hex by day and by night; only its ink weight moves. */
    @Test fun `day takes the darker np amber that holds AA on paper`() {
        assertEquals(DayColors.accent, DayClear.primary)
        assertEquals(DayColors.accentText, DayClear.accentSharp)
    }

    @Test fun `day resolves its surface and ink from the day tokens`() {
        assertEquals(DayColors.canvas, DayClear.surfaceBase)
        assertEquals(DayColors.surface, DayClear.surfaceDeep)
        assertEquals(DayColors.raised, DayClear.glassPanel)
        assertEquals(DayColors.line, DayClear.glassBorder)
        assertEquals(DayColors.text, DayClear.ink)
        assertEquals(DayColors.text2, DayClear.inkSecondary)
        assertEquals(DayColors.danger, DayClear.coral)
        assertEquals(DayColors.success, DayClear.success)
    }

    /** The three day names are one palette; only the id (the phase) differs. */
    @Test fun `sunrise-warm and dawn-bloom are day-clear under their own ids`() {
        assertEquals(DayClear.copy(id = SunriseWarm.id), SunriseWarm)
        assertEquals(DayClear.copy(id = DawnBloom.id), DawnBloom)
        assertEquals(AmberNight.copy(id = DuskEmber.id), DuskEmber)
    }

    @Test fun `sunrise-warm user-bubble-bg is the seal the renderer draws`() =
        assertEquals(
            SunriseWarm.primary.copy(alpha = USER_SEAL_ALPHA).compositeOver(SunriseWarm.surfaceDeep),
            SunriseWarm.userBubbleBg,
        )
}
