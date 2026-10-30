package local.phantom.companion.core.design.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

/**
 * Whether the device can reach the network, published to the composition.
 *
 * Offline is the product's premise, and until now no card could tell: the
 * signal existed in `NetworkProbe` and stopped at the container, so a widget
 * could only infer it from a failed fetch. That is too late — by then it has
 * already drawn something.
 *
 * The honest use is a caption, not a hidden card. A cached answer stays on
 * screen and says what it is; a card must never present stale data as live,
 * and must never blame the operator's data for the absence of a network.
 *
 * Default `true`: a provider-less scope (previews, onboarding, the launcher
 * widget) should render the ordinary face rather than accuse a device that
 * is probably online.
 *
 * Not `static` — this one genuinely changes and its readers must recompose.
 */
val LocalOnline: ProvidableCompositionLocal<Boolean> = compositionLocalOf { true }
