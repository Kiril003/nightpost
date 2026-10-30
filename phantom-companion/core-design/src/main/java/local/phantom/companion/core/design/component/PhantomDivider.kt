package local.phantom.companion.core.design.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * The rule between things.
 *
 * Counted 2026-08-15: 19 `HorizontalDivider` calls carrying **five different
 * alphas** — 0.06, 0.15, 0.18, 0.35, 0.40 — over three different hues
 * (`glassBorder`, `ink`, `inkSecondary`). Nobody decided that; it accumulated.
 *
 * The 0.35/0.40 group hides a second bug. `glassBorder` already carries the
 * palette's hairline alpha (0.10 on AmberNight, 0.12 on the light specs), and
 * `Color.copy(alpha = 0.35f)` REPLACES that alpha rather than scaling it — so
 * every settings divider was drawn three and a half times heavier than the
 * hairline it was named after.
 *
 * Two weights, because there are two jobs:
 *  - [DividerWeight.Inner]   — between rows of ONE card. Barely there.
 *  - [DividerWeight.Section] — between sections. The palette's own hairline.
 *
 * [inset] indents the line so it starts under the text rather than under the
 * icon — the one geometric variation the call sites genuinely had.
 */
@Composable
fun PhantomDivider(
    modifier: Modifier = Modifier,
    weight: DividerWeight = DividerWeight.Inner,
    inset: Dp = 0.dp,
) {
    val c = LocalPhantomColors.current
    HorizontalDivider(
        modifier = if (inset > 0.dp) modifier.padding(start = inset) else modifier,
        thickness = 1.dp,
        color = when (weight) {
            DividerWeight.Inner -> c.line
            DividerWeight.Section -> c.lineStrong
        },
    )
}

/**
 * v1.2's two line tokens are already opaque and already tuned to their ground,
 * so the weight picks a TOKEN now
 * rather than an alpha over the old translucent hairline — an alpha on an
 * opaque line would only have dissolved it.
 */
enum class DividerWeight { Inner, Section }
