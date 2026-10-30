package local.phantom.companion.feature.stream.voice

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.permission.GateCard
import local.phantom.companion.core.design.permission.PermissionReason
import local.phantom.companion.core.design.permission.rememberPermissionGate
import local.phantom.companion.core.design.R as DesignR

/**
 * Мікрофон через спільну браму дозволів: причина, системне вікно, після
 * відмови — спокійна картка, а коли Android вікна не дасть — налаштування.
 * Дія, з якої почали, виконується сама, щойно дозвіл є.
 */
@Composable
fun rememberRecordAudioPermissionGate(): RecordAudioPermissionGate {
    val gate = rememberPermissionGate(
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        reason = MIC_REASON,
    )
    return remember(gate) { RecordAudioPermissionGate(requestThen = gate::requestThen) }
}

private val MIC_REASON = PermissionReason(DesignR.drawable.lc_mic, R.string.mic_look_title, R.string.mic_look_body)

/** Тіло питання мікрофона — `internal` заради кадру в тестах модуля. */
@Composable
internal fun MicAskDialog(onAllow: () -> Unit, onSkip: () -> Unit) {
    GateCard(
        icon = DesignR.drawable.lc_mic,
        title = stringResource(R.string.mic_look_title),
        body = stringResource(R.string.mic_look_body),
        primary = stringResource(R.string.mic_look_allow),
        secondary = stringResource(R.string.mic_look_skip),
        onPrimary = onAllow,
        onSecondary = onSkip,
    )
}

class RecordAudioPermissionGate internal constructor(
    val requestThen: (action: () -> Unit) -> Unit,
)
