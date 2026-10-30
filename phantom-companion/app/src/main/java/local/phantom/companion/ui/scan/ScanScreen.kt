package local.phantom.companion.ui.scan

import android.Manifest
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.ui.scan.LiveQrCamera
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen

/** Підпис замість переходу, коли код наш, але вести нема куди. */
internal data class ScanCard(@StringRes val title: Int)

internal fun notOursCard(text: String, route: CodeRoute?): ScanCard? =
    if (text.isNotBlank() && route == null) ScanCard(R.string.scan_card_not_ours) else null

@Composable
internal fun ScanScreen(
    onBack: () -> Unit,
    read: (String) -> CodeRoute?,
    onCode: (CodeRoute) -> ScanCard?,
    onEnterCode: () -> Unit = {},
    hasCameraOverride: Boolean? = null,
) {
    val c = LocalPhantomColors.current

    val cameraGate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.CAMERA),
        reason = PermissionReason(
            DesignR.drawable.lc_camera,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_title,
            local.phantom.companion.feature.stream.R.string.perm_camera_scan_body,
        ),
    )
    var hasCamera by remember(hasCameraOverride) { mutableStateOf(hasCameraOverride ?: cameraGate.granted()) }
    var generation by remember { mutableStateOf(0) }
    var lastText by remember { mutableStateOf<String?>(null) }
    var seen by remember { mutableStateOf<CodeRoute?>(null) }
    var card by remember { mutableStateOf<ScanCard?>(null) }

    LaunchedEffect(hasCameraOverride) {
        if (hasCameraOverride == null && !hasCamera) cameraGate.request { hasCamera = it }
    }

    LaunchedEffect(seen) {
        val s = seen ?: return@LaunchedEffect
        card = onCode(s)
    }

    SubScreen(stringResource(R.string.scan_title), onBack) {
        Column {
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
                        resetKey = generation,
                        onScan = { text ->
                            if (text == lastText) {
                                false
                            } else {
                                val route = read(text)?.let {
                                    lastText = text
                                    card = null
                                    seen = it
                                    it
                                }
                                notOursCard(text, route)?.let {
                                    lastText = text
                                    seen = null
                                    card = it
                                }
                                route?.let { true } ?: false
                            }
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

            Spacer(Modifier.height(PhantomSpace.s5))

            val currentCard = card
            if (currentCard != null) {
                RowGroup {
                    ActionRow(label = stringResource(currentCard.title))
                }
            }

            if (hasCamera && currentCard != null) {
                RowGroup {
                    ActionRow(
                        label = stringResource(R.string.look_pair_scan_again),
                        icon = DesignR.drawable.lc_camera,
                        onClick = {
                            card = null
                            seen = null
                            lastText = null
                            generation += 1
                        },
                    )
                }
            } else if (hasCamera) {
                Text(
                    text = stringResource(R.string.scan_hint),
                    style = PhantomText.callout,
                    color = c.text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().testTag("scan-hint"),
                )
                Spacer(Modifier.height(PhantomSpace.s5))
                RowGroup {
                    ActionRow(
                        label = stringResource(R.string.scan_enter_code),
                        onClick = onEnterCode,
                    )
                }
            } else {
                RowGroup {
                    ActionRow(
                        label = stringResource(R.string.look_pair_camera_allow),
                        icon = DesignR.drawable.lc_camera,
                        action = stringResource(R.string.look_grant),
                        onClick = { cameraGate.request { hasCamera = it } },
                    )
                    RowDivider()
                    ActionRow(
                        label = stringResource(R.string.scan_enter_code),
                        onClick = onEnterCode,
                    )
                }
            }
        }
    }
}
