package local.phantom.companion.core.design.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import local.phantom.companion.core.design.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors

/**
 * The way back out of a screen — ONE of them.
 *
 * Counted 2026-08-15: the same nineteen lines of back pill, byte-identical,
 * in eight settings screens (`DataLicensesScreen`, `DiagnosticsScreen`,
 * `LicenseScreen`, `MapSettingsScreen`, `MicPrivacyScreen`,
 * `SymbioteSettingsScreen`, `VoiceSettingsScreen`, `WhisperSettingsScreen`).
 * Copying it eight times copied three defects eight times:
 *
 *  1. **It could not be hit.** `PhantomCaption` is 15sp of line box and the
 *     pill added 7dp of padding above and below — ~29dp tall, against a 48dp
 *     floor. `TouchTargetFirewallTest` never saw it: that scan reads `.size()`
 *     on a clickable chain, and this control has no `.size()` at all, only
 *     padding. Eight sub-floor back buttons sat inside a green gate.
 *  2. **It had no accessible name.** The label leads with `←` (U+2190), which
 *     TalkBack speaks as the glyph, so the button announced as an arrow and a
 *     noun — indistinguishable from a control that navigates *into* settings.
 *     [contentDescription] names the action instead, and defaults to «Назад».
 *  3. **One of the eight had drifted.** `WhisperSettingsScreen` carried a
 *     hardcoded «← повернутись» instead of the shared string, so seven screens
 *     said one thing and the eighth said another, and neither the English
 *     locale nor the translation-parity gate covered it.
 *
 * The drawn pill IS the touch target here — 48dp of glass, not 29dp of glass
 * inside an invisible 48dp halo. An operator in gloves aims at what he can
 * see; a target he cannot see is a target he does not trust.
 */
@Composable
fun PhantomBackPill(
    label: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = stringResource(R.string.back_pill_action),
) {
    val c = LocalPhantomColors.current
    val shape = PhantomRadii.Pill
    val spokenName = contentDescription

    // v1.2: an opaque `raised` capsule, no edge — the same surface as every
    // other control, not a translucent pane with a hairline round it.
    Row(
        modifier = modifier
            .defaultMinSize(minWidth = PhantomTouchFloor, minHeight = PhantomTouchFloor)
            .clip(shape)
            .background(c.raised)
            .clickable(role = Role.Button, onClick = onBack)
            .padding(horizontal = PhantomSpacings.Base)
            // Merged so the `←` in [label] is spoken as the action rather than
            // as a glyph; without the merge TalkBack reads both.
            .semantics(mergeDescendants = true) { this.contentDescription = spokenName },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = PhantomText.subhead,
            color = c.text2,
            maxLines = 1,
        )
    }
}
