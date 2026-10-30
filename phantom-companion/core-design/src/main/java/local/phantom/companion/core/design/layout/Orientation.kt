package local.phantom.companion.core.design.layout

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * True when the screen is wider than it is tall — a phone in a car mount.
 *
 * The activity already declares `configChanges="orientation|screenSize"`, so a
 * rotation never recreates it; nothing, however, ever read the resulting
 * configuration, and every surface stayed laid out for portrait.
 */
@Composable
@ReadOnlyComposable
fun isLandscape(): Boolean =
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

/**
 * How wide a chrome panel may be. Full width in portrait; in landscape it is a
 * left-hand rail, because the rest of the screen is the road.
 */
@Composable
@ReadOnlyComposable
fun chromeWidthFraction(): Float = if (isLandscape()) LANDSCAPE_CHROME_FRACTION else 1f

private const val LANDSCAPE_CHROME_FRACTION = 0.46f

/**
 * Side gutter that keeps a text column readable.
 *
 * Edge-to-edge is right on a portrait phone. Rotated, the same rule gives a
 * ~850dp line — far past the ~75-character measure the eye tracks without
 * losing its place — so the extra width becomes margin instead of line length.
 */
@Composable
@ReadOnlyComposable
fun readingGutter(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val widthDp = LocalConfiguration.current.screenWidthDp
    val slack = (widthDp - MAX_MEASURE_DP) / 2
    return if (slack > base.value) androidx.compose.ui.unit.Dp(slack.toFloat()) else base
}

private const val MAX_MEASURE_DP = 560
