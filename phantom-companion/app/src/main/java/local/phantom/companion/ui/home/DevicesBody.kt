package local.phantom.companion.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.data.PairedDevice
import local.phantom.companion.ui.settings.pairedComputerLabel

@Composable
internal fun DevicesBody(
    paired: PairedDevice?,
    canPair: Boolean,
    onUnpair: () -> Unit,
    onPair: () -> Unit,
    onOpenLicense: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }

    RowGroup {
        Box(Modifier.fillMaxWidth().testTag("dev-this-phone")) {
            ActionRow(label = stringResource(R.string.devices_this_phone))
        }
        when {
            paired != null -> {
                RowDivider()
                val label = pairedComputerLabel(paired, stringResource(R.string.pair_computer_fallback))
                Box(Modifier.fillMaxWidth().testTag("dev-pc")) {
                    ActionRow(
                        label = label,
                        action = stringResource(R.string.devices_disconnect),
                        onClick = { confirming = true },
                    )
                }
                if (!paired.dropReady) Explainer(stringResource(R.string.look_pair_drop_stale))
            }
            canPair -> {
                RowDivider()
                NavRow(
                    label = stringResource(R.string.devices_pc_connect),
                    icon = DesignR.drawable.lc_devices,
                    onClick = onPair,
                )
            }
            else -> {
                RowDivider()
                Explainer(stringResource(R.string.pair_gate_body))
                ActionRow(
                    label = stringResource(R.string.pair_gate_headline),
                    icon = DesignR.drawable.lc_lock,
                    action = stringResource(DesignR.string.license_gate_open),
                    onClick = onOpenLicense,
                )
            }
        }
    }

    if (confirming) {
        ProfileDialog(
            title = stringResource(R.string.look_unpair_title),
            body = stringResource(R.string.look_unpair_body),
            confirm = stringResource(R.string.look_unpair),
            danger = true,
            onConfirm = {
                confirming = false
                onUnpair()
            },
            onDismiss = { confirming = false },
        )
    }
}
