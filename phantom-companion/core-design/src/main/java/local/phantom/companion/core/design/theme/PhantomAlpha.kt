package local.phantom.companion.core.design.theme

/**
 * The opacity ladder — the fourth token family, beside [PhantomSpacings],
 * [PhantomRadii] and the text roles.
 *
 * Measured 2026-08-15: **607 `copy(alpha = …)` literals across 47 distinct
 * values** in the UI roots. Forty-seven values is not a scale, it is a
 * per-call-site guess, and it shows: five different alphas on the same
 * `HorizontalDivider`, four on the same chip's border.
 *
 * Eight rungs, each named for the JOB rather than the number, and monotonic
 * so "further down the list" always means "more present":
 *
 *  - [Ghost]     0.09 — felt, not seen: a rule between rows of one card.
 *  - [Hairline]  0.19 — an edge at rest.
 *  - [Wash]      0.28 — a tint behind content: a selected chip's fill.
 *  - [Veil]      0.38 — an edge meant to be found; a scrim.
 *  - [Recessed]  0.48 — pushed back: an inactive control's edge.
 *  - [Secondary] 0.55 — supporting ink. Equals [DISABLED_INK_ALPHA].
 *  - [Emphasis]  0.72 — ink that leads without going opaque.
 *  - [Near]      0.88 — all but solid.
 *
 * THE BOTTOM FOUR MOVED, 2026-08-16. Measured on AmberNight — warm ink
 * #F0E6CF over the #04060B ground, which is where most of these land:
 *
 *   was  0.06 → 1.09:1   0.12 → 1.26:1   0.18 → 1.50:1   0.28 → 2.09:1
 *   now  0.09 → 1.16:1   0.19 → 1.54:1   0.28 → 2.09:1   0.38 → 3.00:1
 *
 * The old ladder's bottom half drew lines nobody could see. 1.26:1 is not an
 * "edge at rest", it is an edge that is not there — on a phone at low
 * brightness, at night, through a screen protector, an operator sees nothing
 * and the structure the design is made of (the rail, the card, the divider)
 * simply is not on the glass. [Veil] now lands on 3.00:1 exactly, the WCAG
 * 1.4.11 floor for a non-text object that carries meaning, so "an edge meant
 * to be found" is a promise the number keeps. Nothing above [Recessed] moved;
 * [Secondary] is load-bearing for contrast and is pinned to
 * [DISABLED_INK_ALPHA].
 *
 * The odd values (.09 / .19 / .38 / .48 rather than .1 / .2 / .4 / .5) are on
 * purpose: a rung must never collide with the sea of hand-guessed literals
 * still in the tree, or AlphaLadderFirewallTest starts reporting other
 * people's unrelated code as drift.
 *
 * The three alpha constants that predate this ladder ([USER_SEAL_ALPHA] 0.15,
 * [ACCENT_BORDER_ALPHA] 0.45, [DISABLED_INK_ALPHA] 0.55) are deliberately NOT
 * re-pointed here: two of them feed `GlassCard`, which has 108 call sites
 * across 59 files, and nudging 0.45 to 0.40 is a visual change on every
 * surface in the app — a separate wave with a device in hand, not a rename.
 */
object PhantomAlpha {
    const val Ghost: Float = 0.09f
    const val Hairline: Float = 0.19f
    const val Wash: Float = 0.28f
    const val Veil: Float = 0.38f
    const val Recessed: Float = 0.48f
    const val Secondary: Float = 0.55f
    const val Emphasis: Float = 0.72f
    const val Near: Float = 0.88f

    /** Ascending. `PhantomAlphaTest` pins the order and the count. */
    val ladder: List<Float> =
        listOf(Ghost, Hairline, Wash, Veil, Recessed, Secondary, Emphasis, Near)
}
