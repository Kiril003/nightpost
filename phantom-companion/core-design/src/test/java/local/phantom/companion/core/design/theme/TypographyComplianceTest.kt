package local.phantom.companion.core.design.theme

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * W1.5-2 — pin the Typography spec from DESIGN_BRIEF §VII so a future
 * refactor that swings displayLarge back to Material's default
 * (57sp Regular) gets caught at CI time.
 *
 * Six representative styles cover what 95 % of surfaces (Stream,
 * Onboarding, SystemPulseHeader, ChatBubble, NoticeBar, Whisper line)
 * actually consume; the rest of the Material set inherits SansSerif
 * via the same builder so the audit doesn't need to enumerate every
 * style.
 */
class TypographyComplianceTest {

    private val typo = phantomTypography()

    @Test fun `displayLarge — 36sp ExtraBold letter-spacing -0_5`() {
        val s = typo.displayLarge
        assertEquals(36.sp, s.fontSize)
        assertEquals(FontWeight.ExtraBold, s.fontWeight)
        assertEquals((-0.5).sp, s.letterSpacing)
    }

    @Test fun `displayMedium — 32sp ExtraBold letter-spacing -0_4`() {
        val s = typo.displayMedium
        assertEquals(32.sp, s.fontSize)
        assertEquals(FontWeight.ExtraBold, s.fontWeight)
        assertEquals((-0.4).sp, s.letterSpacing)
    }

    @Test fun `headlineLarge — 20sp Bold (section heading top)`() {
        val s = typo.headlineLarge
        assertEquals(20.sp, s.fontSize)
        assertEquals(FontWeight.Bold, s.fontWeight)
    }

    @Test fun `headlineMedium — 18sp Bold (section heading mid)`() {
        val s = typo.headlineMedium
        assertEquals(18.sp, s.fontSize)
        assertEquals(FontWeight.Bold, s.fontWeight)
    }

    @Test fun `bodyLarge — 16sp Regular (chat body)`() {
        val s = typo.bodyLarge
        assertEquals(16.sp, s.fontSize)
        assertEquals(FontWeight.Normal, s.fontWeight)
    }

    @Test fun `bodyMedium — 14sp Regular (compact body)`() {
        val s = typo.bodyMedium
        assertEquals(14.sp, s.fontSize)
        assertEquals(FontWeight.Normal, s.fontWeight)
    }

    // 03.10, native theme bridge: the label slots are v1.2 roles (§9.3), not
    // JetBrains Mono at 4sp / 1.5sp of tracking — every stock M3 button read
    // «с к а с у в а т и».
    @Test fun `labelLarge — subhead 14sp Medium, no tracking (every M3 button)`() {
        val s = typo.labelLarge
        assertEquals(14.sp, s.fontSize)
        assertEquals(FontWeight.Medium, s.fontWeight)
        assertEquals(0.em, s.letterSpacing)
    }

    @Test fun `labelSmall — caption 12sp Medium, +0_01em`() {
        val s = typo.labelSmall
        assertEquals(12.sp, s.fontSize)
        assertEquals(FontWeight.Medium, s.fontWeight)
        assertEquals(0.01.em, s.letterSpacing)
    }

    /**
     * The Material half and the PhantomTextRoles half must speak the same
     * scale. They did not: body/label sat one point off each role, and
     * headlineMedium (18) landed on exactly the size prose now uses, so a
     * heading and the paragraph under it were the same size.
     */
    @Test fun `the Material roles agree with the Phantom roles`() {
        // caption 11 · data 12 · body 14 · prose 16 — see PhantomTextRoles.
        //
        // Вища шкала (12/13/15/18) належала нічному переписуванню, яке власник
        // відкинув словами «він і зараз нормальний, який в дупу шрифт». Вихідники
        // відкотили тоді ж, ці тести — ні; вони півдоби тримали гейт червоним,
        // закріплюючи числа, яких у продукті немає.
        assertEquals(12.sp, typo.labelSmall.fontSize) // caption — 12 is the floor (§3.2)
        assertEquals(14.sp, typo.labelLarge.fontSize) // subhead
        assertEquals(14.sp, typo.bodyMedium.fontSize)
        assertEquals(16.sp, typo.bodyLarge.fontSize)
    }
}
