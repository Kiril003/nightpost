package local.phantom.companion.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.ACCENT_BORDER_ALPHA
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius

/**
 * How much of a surface a card is. Only the corner differs now, and it comes
 * from the v1.2 radius scale; the per-level translucency (`bgAlpha` 0.78…0.97)
 * went with the glass — v1.2 surfaces are opaque.
 */
enum class GlassLevel(val corner: Dp) {
    Subtle(corner = PhantomRadius.control),
    Card(corner = PhantomRadius.card),
    Panel(corner = PhantomRadius.sheet),
    Elevated(corner = PhantomRadius.sheet),
    Hero(corner = PhantomRadius.sheet),
}

/**
 * The card — named for the frosted glass it used to be, drawn as what v1.2
 * says a card is (PHANTOM_DESIGN_SYSTEM.md §9.3.4): an opaque `raised` fill on
 * the radius scale, no border, no glow, no gradient.
 *
 * What went, and why it is not coming back:
 *  - the translucent fill — the ground showed through at 3–22%, so a card
 *    changed colour with whatever was under it;
 *  - the 2.5px «lit-from-above» white line along the top edge and the inner
 *    bottom shadow — a skeuomorphic bevel on every surface in the app;
 *  - the ambient amber halo ([glow]) — the parameter is kept so its callers
 *    compile, and it draws nothing.
 *
 * 37 files call this. They change without being edited, which is the point:
 * a screen nobody has migrated yet still sits on the same card as one that has.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    level: GlassLevel = GlassLevel.Panel,
    /**
     * Marks the card with the accent edge — 1dp of `accentLine` at
     * [ACCENT_BORDER_ALPHA]. Amber at the edge of a full-width surface is a
     * signal only while it stays a fraction of itself (the web draws the same
     * card edge at `accent-line/30`); a dozen callers pass `true`
     * unconditionally, and at full strength nothing on the screen could
     * out-shout them.
     */
    accentBorder: Boolean = false,
    /** Kept for its callers; v1.2 has no glow, so it draws nothing. */
    @Suppress("UNUSED_PARAMETER") glow: Boolean = false,
    /**
     * Μ1d — how present the pane is: 1 = full, 0 = dissolved to nothing. A
     * plain alpha on the pane's fill and edge; the content is not faded, so a
     * surface can breathe out of existence without its content moving a pixel.
     */
    presence: Float = 1f,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(level.corner)
    val p = presence.coerceIn(0f, 1f)
    val edge = if (accentBorder) {
        Modifier.border(1.dp, c.accentLine.copy(alpha = ACCENT_BORDER_ALPHA * p), shape)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(c.raised.copy(alpha = c.raised.alpha * p))
            .then(edge),
        content = content,
    )
}
