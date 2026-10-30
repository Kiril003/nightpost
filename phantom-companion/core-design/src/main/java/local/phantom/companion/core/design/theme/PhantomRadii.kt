package local.phantom.companion.core.design.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The corner scale — the third leg of the design system, next to
 * [PhantomSpacings] (the 8px grid) and `PhantomTextRoles` (the voice).
 *
 * Before this existed the app carried eighteen distinct corner radii —
 * 1, 2, 3, 4, 6, 8, 9, 10, 12, 14, 16, 17, 18, 20, 22, 24, 50%, 999 —
 * invented one call site at a time. Nothing in the theme said what a
 * corner *meant*, so `RoundedCornerShape(17)` was as defensible as any
 * other number, and a surface's radius no longer told the operator
 * anything about its role.
 *
 * Six steps, each a rung on the same 4/8 grid the spacings use. The rule
 * is **radius follows role, not taste**:
 *
 *  - [Tight]  4dp  — inner cuts: a chip inside a chip, a progress trough.
 *  - [Small]  8dp  — controls the finger presses: chips, small buttons.
 *  - [Medium] 12dp — inline glass: subtle panels, dense cards, tooltips.
 *  - [Large]  16dp — the default card. If unsure, this one.
 *  - [XLarge] 24dp — panels and sheets: surfaces that own a whole edge.
 *  - [Hero]   32dp — a surface that owns the whole screen's attention.
 *  - [Pill]        — fully round ends. A capsule is a *role* (the Orb, a
 *                    state pill), not a 999dp guess. Percent-based, so it
 *                    stays a true capsule at any height.
 *
 * The Continuum's depth optics scale strata down (`depthScale`), which
 * scales their corners with them — that is intentional and correct: a
 * receded surface really is further away.
 *
 * `ShapeDriftFirewallTest` pins the spine at zero raw `RoundedCornerShape(N)`
 * literals so the zoo cannot grow back.
 */
object PhantomRadii {

    val None: Dp = 0.dp
    val Tight: Dp = 4.dp
    val Small: Dp = 8.dp
    val Medium: Dp = 12.dp
    val Large: Dp = 16.dp
    val XLarge: Dp = 24.dp
    val Hero: Dp = 32.dp

    /* — Shapes ————————————————————————————————————————————————————————
     * Pre-allocated: a RoundedCornerShape built inside a composable is a
     * fresh allocation on every recomposition, and these are read on the
     * Continuum's per-frame camera path.
     */

    val TightShape = RoundedCornerShape(Tight)
    val SmallShape = RoundedCornerShape(Small)
    val MediumShape = RoundedCornerShape(Medium)
    val LargeShape = RoundedCornerShape(Large)
    val XLargeShape = RoundedCornerShape(XLarge)
    val HeroShape = RoundedCornerShape(Hero)

    /** A true capsule at any height — the Orb's body, state pills, chips. */
    val Pill = RoundedCornerShape(percent = 50)

    /** A sheet lifted from the bottom edge: round on top, square where it
     *  meets the edge it grew from. */
    val SheetTop = RoundedCornerShape(topStart = XLarge, topEnd = XLarge)
}
