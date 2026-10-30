package local.phantom.companion.ui.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.voice.AudioLevelHub
import local.phantom.companion.core.voice.duplex.VoicePhase
import local.phantom.companion.core.voice.duplex.VoicePhaseHub
import local.phantom.companion.feature.stream.voice.rememberRecordAudioPermissionGate
import local.phantom.companion.ui.home.ActionRow
import local.phantom.companion.ui.home.InfoRow
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen
import local.phantom.companion.ui.home.SwitchRow

@Composable
fun MicPrivacyScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }

    val muted by container.voicePrefs.micMuted.collectAsState()
    val sttEnabled by container.voicePrefs.sttEnabled.collectAsState()
    val liveEnabled by container.voicePrefs.liveDialogEnabled.collectAsStateWithLifecycle()
    val liveLevel by AudioLevelHub.level.collectAsState()
    val voicePhase by VoicePhaseHub.phase.collectAsStateWithLifecycle()
    val voiceGate = rememberRecordAudioPermissionGate()
    val state = resolveMicPrivacyState(
        muted = muted,
        sttEnabled = sttEnabled,
        liveEnabled = liveEnabled,
        liveLevel = liveLevel,
        duplexOpen = voicePhase != VoicePhase.Dormant,
    )

    SubScreen(title = stringResource(R.string.privacy_mic), onBack = onBack) {
        RowGroup {
            InfoRow(
                stringResource(R.string.privacy_mic),
                state.activePercent?.let { stringResource(state.topStateRes, it) }
                    ?: stringResource(state.topStateRes),
            )
            RowDivider()
            SwitchRow(
                stringResource(R.string.settings_mic_title),
                checked = muted,
            ) { m -> onMicMutedSwitch(m, liveEnabled, voiceGate.requestThen, container.voicePrefs::setMicMuted) }
            RowDivider()
            SwitchRow(
                stringResource(R.string.mic_stt_title),
                checked = sttEnabled,
            ) { container.voicePrefs.setSttEnabled(it) }
            RowDivider()
            if (muted) {
                ActionRow(
                    label = stringResource(R.string.settings_live_title),
                    action = stringResource(R.string.look_live_unmute),
                    onClick = { onMicMutedSwitch(false, liveEnabled, voiceGate.requestThen, container.voicePrefs::setMicMuted) },
                )
            } else {
                SwitchRow(
                    stringResource(R.string.settings_live_title),
                    checked = liveEnabled,
                ) { on -> onLiveDialogSwitch(on, voiceGate.requestThen, container.voicePrefs::setLiveDialogEnabled) }
            }
        }

        RowGroup(caption = stringResource(R.string.mic_owners_kicker)) {
            InfoRow(
                stringResource(R.string.mic_owner_ptt),
                stringResource(state.pttStatus.labelRes),
            )
            RowDivider()
            InfoRow(
                stringResource(R.string.mic_owner_live),
                stringResource(state.liveStatus.labelRes),
            )
        }
    }
}

internal data class MicPrivacyUiState(
    @StringRes val topStateRes: Int,
    val activePercent: Int?,
    val pttStatus: MicOwnerStatus,
    val liveStatus: MicOwnerStatus,
)

/**
 * [duplexOpen] — the live-dialogue ear holds the mic (only DuplexVoiceEngine
 * leaves [VoicePhase.Dormant]); otherwise a level belongs to push-to-talk or a call.
 */
internal fun resolveMicPrivacyState(
    muted: Boolean,
    sttEnabled: Boolean,
    liveEnabled: Boolean,
    liveLevel: Float,
    duplexOpen: Boolean,
): MicPrivacyUiState {
    val live = liveLevel > 0f && !muted
    val pttStatus = when {
        muted -> MicOwnerStatus.Muted
        !sttEnabled -> MicOwnerStatus.Disabled
        live && !duplexOpen -> MicOwnerStatus.Active
        else -> MicOwnerStatus.Idle
    }
    val liveStatus = when {
        muted -> MicOwnerStatus.Muted
        !liveEnabled -> MicOwnerStatus.Disabled
        live && duplexOpen -> MicOwnerStatus.Active
        else -> MicOwnerStatus.Idle
    }
    val topStateRes = when {
        muted -> R.string.mic_state_muted
        live -> R.string.mic_state_active
        else -> R.string.mic_state_idle
    }
    return MicPrivacyUiState(
        topStateRes = topStateRes,
        activePercent = if (live) (liveLevel * 100).toInt() else null,
        pttStatus = pttStatus,
        liveStatus = liveStatus,
    )
}

/** Live dialogue starts a microphone service: on, it waits for RECORD_AUDIO; off, it never asks. */
internal fun onLiveDialogSwitch(
    on: Boolean,
    requestMic: (() -> Unit) -> Unit,
    setEnabled: (Boolean) -> Unit,
) {
    if (on) requestMic { setEnabled(true) } else setEnabled(false)
}

/** Unmuting with live dialogue on starts its microphone service, so it waits for RECORD_AUDIO too. */
internal fun onMicMutedSwitch(
    muted: Boolean,
    liveEnabled: Boolean,
    requestMic: (() -> Unit) -> Unit,
    setMuted: (Boolean) -> Unit,
) {
    if (!muted && liveEnabled) requestMic { setMuted(false) } else setMuted(muted)
}

enum class MicOwnerStatus(@StringRes val labelRes: Int) {
    Idle(R.string.mic_status_idle),
    Active(R.string.mic_status_active),
    Muted(R.string.mic_status_muted),
    Disabled(R.string.mic_status_disabled),
}
