package local.phantom.companion.ui.settings

import androidx.annotation.StringRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import local.phantom.companion.R
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.data.LIVE_VOICE_SIZE_MB
import local.phantom.companion.data.LiveVoiceState
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.component.PhantomDivider

internal enum class CapabilityTone { Good, Quiet, Bad }

/**
 * What the hub owes the operator when «слух» reads «моделі нема».
 *
 * The strip used to state the absence and stop there, so the only way to a
 * hearing model was a download control two screens down in the voice lab — or
 * a PTT press, which is the gesture an operator hunting for live dialogue
 * never makes. Kept pure so the decision is testable without a Compose host.
 */
internal sealed interface HearingAction {
    /** Nothing on disk and nothing running. [bytes] is 0 when not knowable. */
    data class Fetch(val bytes: Long) : HearingAction
    data class Fetching(val progress: Float) : HearingAction
    data class Failed(val message: String) : HearingAction
}

/** Скільки ОЗП бачить система. Нуль при відмові — і це навмисний бік:
 *  `hearingModelForRam` тоді дає меншу модель, яка йде всюди. */
internal fun deviceTotalRamBytes(context: android.content.Context): Long = runCatching {
    val am = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
        as android.app.ActivityManager
    android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem
}.getOrDefault(0L)

internal fun hearingAction(
    modelStates: Map<ModelType, ModelState>,
    ear: ModelType = ModelType.VOSK,
): HearingAction? {
    // Mirrors the `hearing` capability below: either engine's model is an ear.
    //
    // `wired` тут не косметика, а різниця між вухом і його відсутністю: файл
    // незібраного рушія на диску ПРИБИРАВ би ліки — єдину дорогу звантажити
    // справжнє вухо — повідомляючи водночас, що слух є.
    fun isEar(type: ModelType) = type.wired && modelStates[type]?.isPresent == true
    if (isEar(ModelType.VOSK)) return null
    if (isEar(ModelType.MOONSHINE_UK)) return null
    val state = modelStates[ear]
        ?: return HearingAction.Fetch(ear.downloadBytes)
    val failure = state.error
    return when {
        state.isDownloading -> HearingAction.Fetching(state.progress)
        failure != null -> HearingAction.Failed(failure)
        else -> HearingAction.Fetch(ear.downloadBytes)
    }
}

internal data class Capability(
    @StringRes val label: Int,
    @StringRes val value: Int,
    val tone: CapabilityTone,
)

/**
 * The first thing on the hub: what PHANTOM can do at this moment, read from
 * the same sources the features read. Each faculty answers in its own words
 * rather than a shared ✓/✗ — «мапа: нема» would be false, since the map still
 * works online with nothing saved.
 */
@Composable
internal fun CapabilityStrip(
    capabilities: List<Capability>,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    hearing: HearingAction? = null,
    onFetchHearing: () -> Unit = {},
    liveVoice: LiveVoiceState? = null,
    onRequestVoice: () -> Unit = {},
    onAllowVoiceOnMobile: () -> Unit = {},
    onRetryVoice: () -> Unit = {},
) {
    val theme = LocalPhantomTheme.current
    SettingsSectionCard(modifier = modifier) {
        SettingsKicker(label = stringResource(R.string.settings_caps_title))
        Spacer(modifier = Modifier.height(4.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            capabilities.forEach { capability ->
                SettingsRow(
                    title = stringResource(capability.label),
                    value = stringResource(capability.value),
                    valueTone = when (capability.tone) {
                        CapabilityTone.Good -> theme.success
                        CapabilityTone.Quiet -> theme.inkSecondary
                        CapabilityTone.Bad -> theme.coral
                    },
                )
            }
        }
        hearing?.let { action ->
            PhantomDivider()
            HearingRemedyRow(action = action, onFetch = onFetchHearing)
        }
        if (liveVoice != null && liveVoice !is LiveVoiceState.Ready) {
            PhantomDivider()
            LiveVoiceRemedyRow(
                state = liveVoice,
                onRequest = onRequestVoice,
                onAllowMobile = onAllowVoiceOnMobile,
                onRetry = onRetryVoice,
            )
        }
        PhantomDivider()
        SettingsRow(
            title = stringResource(R.string.settings_caps_open),
            onClick = onOpenDiagnostics,
        )
    }
}

/**
 * The size is stated before the tap, not after it: 137 МіБ over a field
 * connection is a decision, and an operator who is not told is not deciding.
 */
@Composable
private fun HearingRemedyRow(action: HearingAction, onFetch: () -> Unit) {
    val theme = LocalPhantomTheme.current
    when (action) {
        is HearingAction.Fetch -> SettingsRow(
            title = stringResource(R.string.settings_hearing_fetch),
            value = action.bytes
                .takeIf { it > 0 }
                ?.let { stringResource(R.string.settings_hearing_size, it / (1024 * 1024)) },
            onClick = onFetch,
        )
        is HearingAction.Fetching -> Column(modifier = Modifier.fillMaxWidth()) {
            SettingsRow(
                title = stringResource(R.string.settings_hearing_fetching),
                value = stringResource(
                    R.string.settings_hearing_percent,
                    (action.progress * 100).roundToInt(),
                ),
            )
            val animated by animateFloatAsState(
                targetValue = action.progress,
                animationSpec = PhantomMotion.meter,
                label = "hearing-progress",
            )
            LinearProgressIndicator(
                progress = { animated },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = theme.accentSharp,
                trackColor = theme.glassPanel,
            )
            Spacer(modifier = Modifier.height(8.dp))
        }
        is HearingAction.Failed -> SettingsRow(
            title = stringResource(R.string.settings_hearing_failed),
            value = stringResource(R.string.settings_hearing_retry),
            valueTone = theme.coral,
            onClick = onFetch,
        )
    }
}

/** The size and the money are said before the tap; a download nobody agreed to is theft with a progress bar. */
@Composable
internal fun LiveVoiceRemedyRow(
    state: LiveVoiceState,
    onRequest: () -> Unit,
    onAllowMobile: () -> Unit,
    onRetry: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    when (state) {
        // 05.09 privacy audit — the resting state on every fresh install. The
        // row IS the consent: tapping it is the one thing that may ever start
        // this transfer unattended-since-boot. Size stated before the tap,
        // same rule HearingRemedyRow already keeps for the 137 MiB ear.
        LiveVoiceState.NotRequested -> SettingsRow(
            title = stringResource(R.string.settings_voice_fetch),
            value = stringResource(R.string.settings_voice_size, LIVE_VOICE_SIZE_MB),
            onClick = onRequest,
        )
        is LiveVoiceState.Fetching, LiveVoiceState.Fetch -> {
            val progress = (state as? LiveVoiceState.Fetching)?.progress ?: 0f
            Column(modifier = Modifier.fillMaxWidth()) {
                SettingsRow(
                    title = stringResource(R.string.settings_voice_fetching),
                    value = stringResource(R.string.settings_hearing_percent, (progress * 100).roundToInt()),
                )
                val animated by animateFloatAsState(
                    targetValue = progress,
                    animationSpec = PhantomMotion.meter,
                    label = "live-voice-progress",
                )
                LinearProgressIndicator(
                    progress = { animated },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = theme.accentSharp,
                    trackColor = theme.glassPanel,
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        is LiveVoiceState.WaitingWifi -> SettingsRow(
            title = stringResource(R.string.settings_voice_allow_mobile),
            value = stringResource(R.string.settings_voice_allow),
            onClick = onAllowMobile,
        )
        is LiveVoiceState.NoSpace -> SettingsRow(
            title = stringResource(R.string.settings_voice_nospace),
            value = stringResource(R.string.settings_voice_size, state.needMb),
            valueTone = theme.coral,
        )
        is LiveVoiceState.Stalled -> SettingsRow(
            title = stringResource(R.string.settings_voice_stalled),
            value = stringResource(R.string.settings_hearing_retry),
            valueTone = theme.coral,
            onClick = onRetry,
        )
        LiveVoiceState.Offline -> SettingsRow(
            title = stringResource(R.string.settings_voice_offline),
        )
        LiveVoiceState.Ready -> Unit
    }
}

internal fun capabilities(
    modelStates: Map<ModelType, ModelState>,
    keysStored: Boolean,
    ttsEnabled: Boolean,
    offlineRegions: Int?,
    paired: Boolean,
    liveVoice: LiveVoiceState? = null,
): List<Capability> {
    // Файл на диску ще не означає рушій у збірці.
    fun present(type: ModelType) = type.wired && modelStates[type]?.isPresent == true

    val mind = when {
        paired -> Capability(
            R.string.settings_cap_mind, R.string.settings_cap_mind_pc, CapabilityTone.Good,
        )
        keysStored -> Capability(
            R.string.settings_cap_mind, R.string.settings_cap_mind_cloud, CapabilityTone.Quiet,
        )
        else -> Capability(
            R.string.settings_cap_mind, R.string.settings_cap_mind_none, CapabilityTone.Bad,
        )
    }
    val hearing = if (present(ModelType.VOSK) || present(ModelType.MOONSHINE_UK)) {
        Capability(R.string.settings_cap_hearing, R.string.settings_cap_hearing_on, CapabilityTone.Good)
    } else {
        Capability(R.string.settings_cap_hearing, R.string.settings_cap_hearing_none, CapabilityTone.Bad)
    }
    val voice = when (liveVoice) {
        LiveVoiceState.NotRequested ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_not_requested, CapabilityTone.Quiet)
        is LiveVoiceState.Fetching, LiveVoiceState.Fetch ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_fetching, CapabilityTone.Quiet)
        LiveVoiceState.Offline ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_offline, CapabilityTone.Bad)
        is LiveVoiceState.WaitingWifi ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_wifi, CapabilityTone.Quiet)
        is LiveVoiceState.NoSpace ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_nospace, CapabilityTone.Bad)
        is LiveVoiceState.Stalled ->
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_stalled, CapabilityTone.Bad)
        LiveVoiceState.Ready, null -> if (ttsEnabled) {
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_on, CapabilityTone.Good)
        } else {
            Capability(R.string.settings_cap_voice, R.string.settings_cap_voice_off, CapabilityTone.Quiet)
        }
    }
    val map = when {
        offlineRegions == null -> Capability(
            R.string.settings_cap_map, R.string.settings_value_reading, CapabilityTone.Quiet,
        )
        offlineRegions > 0 -> Capability(
            R.string.settings_cap_map, R.string.settings_cap_map_offline, CapabilityTone.Good,
        )
        else -> Capability(
            R.string.settings_cap_map, R.string.settings_cap_map_online, CapabilityTone.Quiet,
        )
    }
    val link = if (paired) {
        Capability(R.string.settings_cap_link, R.string.settings_cap_link_paired, CapabilityTone.Good)
    } else {
        Capability(R.string.settings_cap_link, R.string.settings_cap_link_solo, CapabilityTone.Quiet)
    }
    return listOf(mind, hearing, voice, map, link)
}
