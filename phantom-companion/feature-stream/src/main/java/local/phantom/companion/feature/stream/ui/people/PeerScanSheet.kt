package local.phantom.companion.feature.stream.ui.people

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import local.phantom.companion.core.design.component.fillDialogRoom
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerSafety
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.scan.LiveQrCamera

/**
 * Adding a person by pointing the camera at their screen.
 *
 * The other way — copy 200 characters, send them through a messenger, have the
 * other person paste them — works and will stay, because two people are not
 * always in the same room. But when they ARE in the same room, which is the
 * common case for the people this is meant for, a camera is the whole
 * interaction, and asking them to route their identity through a third party's
 * server instead is both slower and worse.
 */
// LIVE CAMERA ONLY. [onCode] is the one path that records
// PeerIdentity.KeySource.CAMERA, and that tier is worth something only because
// photons went from their screen into this lens with nobody in between. A QR
// decoded from a saved or forwarded image travelled through whatever app
// carried it, so it is the paste path wearing the strong label. Never add a
// gallery picker, a file import or a bitmap decoder to this file, and never
// hand onScanInvite to anything but this composable — PeerProvenanceFirewallTest
// fails the build if either happens.
@Composable
fun PeerScanSheet(
    onCode: (String) -> PeerInvite.Decoded,
    onClose: () -> Unit,
    /**
     * A safety number off the other screen, for somebody already in the
     * roster; null on the surfaces that add people. Both answers close the
     * viewfinder — the card speaks next, and it has room for either.
     */
    onVerifyCode: ((String) -> PeerSafety.Outcome)? = null,
) {
    // Its own window, above the sheet — deliberately.
    //
    // The viewfinder cannot live inside the handshake PhantomSheet: that sheet
    // owns drag-to-dismiss, so the small downward nudge you make to line the
    // camera up on someone's screen would throw the whole handshake away. A
    // Dialog also paints over the navigation island, so nothing covers the
    // frame or the way out. Paste and share stay on the sheet behind this, so
    // a camera that never renders is a detour, not a dead end.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        ScanSurface(onCode = onCode, onClose = onClose, onVerifyCode = onVerifyCode)
    }
}

@Composable
private fun ScanSurface(
    onCode: (String) -> PeerInvite.Decoded,
    onClose: () -> Unit,
    onVerifyCode: ((String) -> PeerSafety.Outcome)?,
) {
    val c = LocalPhantomColors.current
    val cameraGate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        reason = PermissionReason(DesignR.drawable.lc_camera, R.string.perm_camera_scan_title, R.string.perm_camera_scan_body),
    )
    var granted by remember { mutableStateOf(cameraGate.granted()) }
    var rejected by remember { mutableStateOf<PeerInvite.Decoded?>(null) }

    LaunchedEffect(Unit) { if (!granted) cameraGate.request { granted = it } }

    Column(
        modifier = Modifier
            .fillDialogRoom()
            .background(c.canvas)
            .padding(PhantomSpace.s4),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(FrameShape)
                .background(Color.Black)
                .border(
                    width = 1.dp,
                    color = if (granted) c.accent else c.line,
                    shape = FrameShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (granted) {
                LiveQrCamera(
                    modifier = Modifier.fillMaxSize(),
                    // A code that is not an invite must not close the surface:
                    // the operator is still pointing at something, and the
                    // scanner has to keep looking rather than fail silently.
                    onScan = { text ->
                        // A number is an answer, not an invite: it never reaches onCode.
                        val verdict = if (onVerifyCode != null && PeerSafety.read(text) != null) {
                            onVerifyCode(text)
                        } else {
                            null
                        }
                        if (verdict != null && verdict != PeerSafety.Outcome.NOT_A_NUMBER) {
                            true
                        } else {
                            val decoded = onCode(text)
                            if (decoded is PeerInvite.Decoded.Ph2) {
                                true
                            } else {
                                rejected = decoded
                                false
                            }
                        }
                    },
                )
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.people_scan_needs_camera),
                        style = PhantomText.callout,
                        color = c.text2,
                        modifier = Modifier.padding(PhantomSpace.s4),
                    )
                    Text(
                        text = stringResource(R.string.perm_scan_allow),
                        style = PhantomText.headline,
                        color = c.accentText,
                        modifier = Modifier
                            .clip(RoundedCornerShape(PhantomRadius.control))
                            .clickable(role = Role.Button) { cameraGate.request { granted = it } }
                            .heightIn(min = 48.dp)
                            .padding(horizontal = PhantomSpace.s4)
                            .wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        // Старе запрошення лікується оновленням застосунку, а не іншим кутом
        // камери — сказати «шукаю далі» тут означало б лишити двох людей
        // наводити телефони одне на одного, доки не здадуться.
        val hint = when (rejected) {
            PeerInvite.Decoded.LegacyPh1 -> R.string.people_scan_legacy
            PeerInvite.Decoded.NotInvite -> R.string.people_scan_not_invite
            else -> if (onVerifyCode != null) R.string.people_scan_safety_hint else R.string.people_scan_hint
        }
        Text(
            text = stringResource(hint),
            style = PhantomText.footnote,
            color = if (rejected != null) c.danger else c.text2,
        )
        Spacer(Modifier.height(PhantomSpace.s2))
        Text(
            text = stringResource(R.string.people_scan_close).sentence(),
            style = PhantomText.headline,
            color = c.accentText,
            modifier = Modifier
                .clip(RoundedCornerShape(PhantomRadius.control))
                .clickable(role = Role.Button, onClick = onClose)
                .heightIn(min = 48.dp)
                .padding(horizontal = PhantomSpace.s4)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

private val FrameShape = RoundedCornerShape(PhantomRadius.sheet)
