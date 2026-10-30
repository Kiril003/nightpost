package local.phantom.companion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.core.design.license.LocalEntitlements
import local.phantom.companion.core.license.LicenseFeature
import local.phantom.companion.data.PairedDevice
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.Explainer
import local.phantom.companion.ui.home.NavRow
import local.phantom.companion.ui.home.ProfileDialog
import local.phantom.companion.ui.home.RowDivider

/**
 * Компʼютер у «Пристроях»: три стани — спарено, можна спарити, під замком.
 *
 * Переїхав зі старого екрана налаштувань (SettingsRealm) разом із воротами
 * [LicenseFeature.BridgePair]: звʼязка з ПК — платне право, і питати про нього
 * треба саме тут, на дверях, а не лише на екрані сканера.
 */
@Composable
internal fun PairEntry(
    pairedDevice: PairedDevice?,
    onUnpair: () -> Unit,
    onRequestPair: () -> Unit,
) {
    var confirming by remember { mutableStateOf(false) }
    val entitlements = LocalEntitlements.current

    when {
        pairedDevice != null -> {
            InfoRowPaired(pairedDevice)
            // Запис без сітьового ключа вузла — це не «щось не так із мережею»:
            // адресу скриньки у сховку з нього не скласти взагалі, і мовчати
            // про це означало б лишити людину чекати листа, який нікуди не їде.
            if (!pairedDevice.dropReady) Explainer(stringResource(R.string.look_pair_drop_stale))
            RowDivider()
            ActionRow(
                label = stringResource(R.string.look_unpair),
                body = stringResource(R.string.look_unpair_body),
                danger = true,
                onClick = { confirming = true },
            )
        }
        entitlements.has(LicenseFeature.BridgePair) -> {
            Explainer(stringResource(R.string.devices_pc_none))
            NavRow(
                label = stringResource(R.string.devices_pc_connect),
                icon = DesignR.drawable.lc_devices,
                onClick = onRequestPair,
            )
        }
        else -> {
            Explainer(stringResource(R.string.pair_gate_body))
            ActionRow(
                label = stringResource(R.string.pair_gate_headline),
                icon = DesignR.drawable.lc_lock,
                action = stringResource(DesignR.string.license_gate_open),
                onClick = entitlements::openLicense,
            )
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

internal fun pairedComputerLabel(device: PairedDevice, fallback: String): String =
    device.computerName.trim().ifBlank { fallback }

@Composable
private fun InfoRowPaired(device: PairedDevice) {
    val label = pairedComputerLabel(device, stringResource(R.string.pair_computer_fallback))
    ActionRow(
        label = stringResource(R.string.look_pair_paired, label),
        icon = DesignR.drawable.lc_devices,
    )
}
