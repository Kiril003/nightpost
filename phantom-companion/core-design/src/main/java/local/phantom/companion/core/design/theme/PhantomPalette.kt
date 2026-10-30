package local.phantom.companion.core.design.theme

import androidx.compose.ui.graphics.Color
import local.phantom.companion.core.design.tokens.DayColors
import local.phantom.companion.core.design.tokens.NightColors

/**
 * The fixed, theme-independent hues — for places that have no composition to
 * ask: a notification's `setColor`, a map layer's paint, an emotion tint.
 *
 * Until the native theme bridge (03.10) this was `docs/night/PROTOTYPE_ETALON.html`
 * `:root` transcribed — the warm-brown night (#110E0B ground, #F4AF25 amber,
 * #00E676 green) that v1.2 replaced. Every name now resolves to its nearest
 * v1.2 token, so a notification and the screen it opens are the same amber.
 * The surface, ink and amber-ramp names went with AmberNight's move onto
 * [NightColors]: nothing read them any more, and `PhantomPaletteTest` refuses
 * an unread token. Amber, Coral and Green followed at the 04.10 integration
 * (cloud/sweep): notifications paint through `NotificationLook` and the
 * emotion tint is gone, so the peer-route violet is the last fixed hue read.
 *
 * Nothing here is a large-surface fill.
 */
object PhantomPalette {

    /** `actMute` — the one violet v1.2 keeps: a peer's route, the vault's hush. */
    val Violet = NightColors.actMute

    val MapSwatchDarkGround = Color(0xFF0A0C10)
    val MapSwatchDarkLine = Color(0xFF4A4D53)
    val MapSwatchLightGround = Color(0xFFF8F4EE)
    val MapSwatchLightLine = Color(0xFFC8BBAA)
    val MapSwatchMinimalLine = Color(0xFFE6DED3)
    val MapSwatchPin = Color(0xFFF2A93B)
    val LoudInk = Color.White

    val PeerSignalNight = Color(0xFFA78BFA)
    val PeerSignalDay = Color(0xFF5B21B6)
    val PeerPlate = Color(0xFF6D28D9)
    val WorldAmber = Color(0xFFF4AF25)

    val PersonNight = NightColors.person

    val PersonDay = DayColors.person
}
