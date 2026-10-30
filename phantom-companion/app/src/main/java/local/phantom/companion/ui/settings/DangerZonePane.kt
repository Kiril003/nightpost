package local.phantom.companion.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import local.phantom.companion.R
import local.phantom.companion.core.design.R as DesignR
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.ProfileDialog

/**
 * «Видалити завантажені моделі» — те, чим була «небезпечна зона» старого
 * екрана, під своїм справжнім імʼям. Це НЕ витирання даних: зникають лише
 * звантажені розум, слух і голос; листування, мапи й сховище не чіпаються.
 * Тому рядок стоїть поруч із самими моделями, а не в «Даних».
 *
 * Закрито двічі: діалог, який називає, що зникне з диска, і другий, який три
 * секунди не дає себе підтвердити. Тайминг і переходи живуть у
 * [DangerZoneController] — його тут і крутимо, а не власну копію машини.
 */
@Composable
internal fun DeleteModelsEntry(
    anyModelPresent: Boolean,
    onDeleteAll: () -> Unit,
) {
    val controller = remember { DangerZoneController() }
    val phase by controller.phase.collectAsStateWithLifecycle()
    // Годинник контролера — стінний, тож без цього тику composable не дізнався
    // б, що відлік вийшов. Крутиться лише під час самого відліку.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(phase) {
        while (phase == DangerZoneController.Phase.Cooldown && !controller.canCommit()) {
            delay(250)
            tick++
        }
        tick++
    }
    val canCommit = remember(phase, tick) { controller.canCommit() }
    val remainingSec = remember(phase, tick) { controller.remainingCooldownSeconds() }

    ActionRow(
        label = stringResource(R.string.look_models_delete),
        icon = DesignR.drawable.lc_trash,
        body = if (anyModelPresent) null else stringResource(R.string.look_models_none),
        danger = anyModelPresent,
        onClick = if (anyModelPresent) { { controller.openFirstConfirm() } } else null,
    )

    if (phase == DangerZoneController.Phase.FirstConfirm) {
        ProfileDialog(
            title = stringResource(R.string.look_models_delete_title),
            body = stringResource(R.string.look_models_delete_body),
            confirm = stringResource(R.string.look_models_delete_yes),
            danger = true,
            onConfirm = { controller.acceptFirstConfirm() },
            onDismiss = { controller.cancel() },
        )
    }

    if (phase == DangerZoneController.Phase.Cooldown) {
        ProfileDialog(
            title = stringResource(R.string.look_models_sure),
            body = if (canCommit) {
                stringResource(R.string.look_models_tap_again)
            } else {
                stringResource(R.string.look_models_wait, remainingSec)
            },
            confirm = if (canCommit) {
                stringResource(R.string.look_models_commit)
            } else {
                stringResource(R.string.look_models_commit_wait, remainingSec)
            },
            danger = true,
            confirmEnabled = canCommit,
            onConfirm = { controller.commit(onDeleteAll) },
            onDismiss = { controller.cancel() },
        )
    }
}
