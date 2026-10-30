package local.phantom.companion.ui.screens

import android.Manifest
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.license.LocalEntitlements
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.feature.stream.ui.scan.LiveQrCamera
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.Explainer
import local.phantom.companion.ui.home.ProfileField
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen
import local.phantom.companion.vm.UiState

@Composable
fun PairScreen(
    state: UiState,
    onPair: () -> Unit,
    onClaimFromJson: (String) -> Unit,
    onCancel: (() -> Unit)? = null,
    scanned: String? = null,
    onPaired: () -> Unit = {},
) {
    val c = LocalPhantomColors.current
    val context = LocalContext.current
    val back: () -> Unit = onCancel ?: {}

    if (!LocalEntitlements.current.has(LicenseFeature.BridgePair)) {
        PairLocked(onBack = back)
        return
    }

    val cameraGate = local.phantom.companion.core.design.permission.rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        reason = local.phantom.companion.core.design.permission.PermissionReason(
            DesignR.drawable.lc_camera,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_title,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_body,
        ),
    )
    var hasCamera by remember { mutableStateOf(cameraGate.granted()) }

    var manualJson by remember { mutableStateOf("") }
    var scanStatus by remember { mutableStateOf<String?>(null) }
    var scanGeneration by remember { mutableStateOf(0) }

    // Resolved here, not inside the callbacks below: onScan/onClick run
    // outside composition, where stringResource() cannot be called.
    val scanStatusFoundText = stringResource(R.string.pair_scan_status)
    val scanStatusConnectingText = stringResource(R.string.pair_connecting_status)

    val pairedOnOpen = remember { state.paired }
    var pairedHandled by remember { mutableStateOf(false) }

    LaunchedEffect(state.paired) {
        if (state.paired != null && state.paired !== pairedOnOpen && !pairedHandled) {
            pairedHandled = true
            scanStatus = null
            onPaired()
        }
    }

    LaunchedEffect(scanned) {
        if (scanned != null) {
            scanStatus = scanStatusConnectingText
            onClaimFromJson(scanned)
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCamera) cameraGate.request { hasCamera = it }
    }

    SubScreen(stringResource(R.string.devices_pc_connect), back) {
        Column(Modifier.imePadding()) {
            Row(
                modifier = Modifier.padding(horizontal = PhantomSpace.s1, vertical = PhantomSpace.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = painterResource(DesignR.drawable.lc_devices),
                    contentDescription = null,
                    tint = c.text2,
                    modifier = Modifier.size(PhantomSize.iconL),
                )
                Spacer(Modifier.width(PhantomSpace.s3))
                Text(stringResource(R.string.look_pair_subtitle), style = PhantomText.callout, color = c.text2)
            }
            Spacer(Modifier.height(PhantomSpace.s3))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(PhantomRadius.sheet))
                    .background(c.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (hasCamera) {
                    LiveQrCamera(
                        modifier = Modifier.fillMaxSize(),
                        resetKey = scanGeneration,
                        onScan = { json ->
                            scanStatus = scanStatusFoundText
                            onClaimFromJson(json)
                            true
                        },
                    )
                } else {
                    Column(
                        modifier = Modifier.padding(PhantomSpace.s6),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painter = painterResource(DesignR.drawable.lc_camera),
                            contentDescription = null,
                            tint = c.text3,
                            modifier = Modifier.size(PhantomSpace.s10),
                        )
                        Spacer(Modifier.height(PhantomSpace.s3))
                        Text(
                            text = stringResource(R.string.pair_camera_denied),
                            style = PhantomText.callout,
                            color = c.text2,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            val line = state.pairError ?: scanStatus
            if (line != null) {
                Spacer(Modifier.height(PhantomSpace.s3))
                Text(
                    text = line,
                    style = PhantomText.footnote,
                    color = if (state.pairError != null) c.danger else c.text2,
                    modifier = Modifier.padding(horizontal = PhantomSpace.s4),
                )
            }
            Spacer(Modifier.height(PhantomSpace.s5))

            RowGroup {
                if (hasCamera) {
                    ActionRow(
                        label = stringResource(R.string.look_pair_scan_again),
                        icon = DesignR.drawable.lc_camera,
                        onClick = {
                            // beginClaim() only clears pairError; the actual rescan is
                            // the generation bump, which hands QrCameraSurface a
                            // fresh `fired` flag so the next decoded frame fires again.
                            scanStatus = null
                            scanGeneration += 1
                            onPair()
                        },
                    )
                } else {
                    ActionRow(
                        label = stringResource(R.string.look_pair_camera_allow),
                        icon = DesignR.drawable.lc_camera,
                        action = stringResource(R.string.look_grant),
                        onClick = { cameraGate.request { hasCamera = it } },
                    )
                }
            }

            // Zero-config is product law: the operator is never asked for a host,
            // a key or a config blob. The paste field asks for all three at once,
            // so it stays a development escape hatch.
            if (local.phantom.companion.BuildConfig.DEBUG) {
                RowGroup(caption = stringResource(R.string.pair_manual_label)) {
                    Box(Modifier.padding(PhantomSpace.s3)) {
                        ProfileField(
                            value = manualJson,
                            onValueChange = { manualJson = it },
                            hint = stringResource(R.string.pair_manual_placeholder),
                        )
                    }
                    RowDivider()
                    ActionRow(
                        label = stringResource(R.string.pair_claim),
                        onClick = if (manualJson.isBlank()) {
                            null
                        } else {
                            {
                                scanStatus = scanStatusConnectingText
                                onClaimFromJson(manualJson.trim())
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PairLocked(onBack: () -> Unit) {
    val entitlements = LocalEntitlements.current
    SubScreen(stringResource(R.string.devices_pc_connect), onBack) {
        RowGroup {
            Explainer(stringResource(R.string.pair_gate_body))
            ActionRow(
                label = stringResource(R.string.pair_gate_headline),
                icon = DesignR.drawable.lc_lock,
                action = stringResource(DesignR.string.license_gate_open),
                onClick = entitlements::openLicense,
            )
        }
    }
}
