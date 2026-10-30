package local.phantom.companion.core.design.theme

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * VISUAL DAY 2 — how much bottom chrome floats over the content.
 *
 * The navigation island (app/ui/PhantomIsland) is an overlay painted over the
 * whole NavHost, not a Scaffold bottom bar, so the surfaces underneath it have
 * no built-in way to know it is there. Rather than leak the island's height
 * into feature modules, the shell PUBLISHES the reserve here and any bottom-
 * anchored content (the chat composer) UNIONS it into its own bottom inset —
 * `max(ime, navBar + reserve)`, never additive, so it clears the rail when the
 * keyboard is down and sits flush above the keyboard (island hidden behind it)
 * when it is up.
 *
 * Default 0.dp keeps previews and providerless scopes (onboarding, launcher,
 * the hidden chambers with no island) safe — they inset nothing.
 * `staticCompositionLocalOf`: the shell provides it once as a constant, so it
 * never invalidates.
 */
val LocalBottomChromeInset: ProvidableCompositionLocal<Dp> =
    staticCompositionLocalOf { 0.dp }
