package local.phantom.companion.core.design.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The depth ladder — how far forward a surface sits.
 *
 * Nothing in the theme said this before, so depth was argued one call site at
 * a time in `drawBehind` blocks and never argued the same way twice. Five
 * rungs, ordered; [Flat] is genuinely flat, not "a small shadow".
 *
 *  - [Flat]     — in the page's own plane. An empty state belongs here:
 *                 absence should not float.
 *  - [Raised]   — a control lifted off its row: a filled, primary chip.
 *  - [Lifted]   — a card over the ground.
 *  - [Floating] — an island, a snackbar, a FAB.
 *  - [Overlay]  — a sheet over everything.
 *
 * `GlassLevel` is NOT re-expressed in terms of this. It encodes fill and
 * corner, is read by 108 call sites, and giving all of them a shadow is a
 * change to every surface in the app — a wave with a device in hand.
 */
enum class PhantomElevation(val z: Dp) {
    Flat(0.dp),
    Raised(2.dp),
    Lifted(6.dp),
    Floating(12.dp),
    Overlay(20.dp),
}

/** A true no-op at [PhantomElevation.Flat] — no layer, no render node. */
fun Modifier.phantomElevation(level: PhantomElevation, shape: Shape): Modifier =
    if (level == PhantomElevation.Flat) this else shadow(level.z, shape, clip = false)
