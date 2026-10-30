package local.phantom.companion.ui.settings

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import local.phantom.companion.core.design.component.PhantomSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.annotation.StringRes
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.isFetchable
import local.phantom.companion.core.design.component.GhostButton
import local.phantom.companion.core.design.component.PrimaryButton
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSize
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.voice.SttRouteKind
import local.phantom.companion.core.voice.VoiceManager
import local.phantom.companion.core.voice.route.RoutedTts
import local.phantom.companion.core.voice.route.TtsRoute
import local.phantom.companion.core.voice.route.VoiceFallback
import local.phantom.companion.service.VoiceFallbackNotice
import local.phantom.companion.ui.home.PaneSlot
import local.phantom.companion.ui.home.RowDivider
import local.phantom.companion.ui.home.RowGroup
import local.phantom.companion.ui.home.SubScreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import local.phantom.companion.core.voice.supertonic.SupertonicAssets
import local.phantom.companion.core.voice.supertonic.VoiceTuning
// Псевдонім обовʼязковий: у цьому файлі вже живе `supertonic.VoiceTuning`
// (стилі рушія Supertonic), а це — просодія СИСТЕМНОГО TTS. Два різні типи з
// однаковим простим іменем; без псевдоніма файл не збереться.
import local.phantom.companion.core.voice.route.VoiceTuning as Prosody

/**
 * Профіль › Голос: що зараз говорить і слухає, висота й темп, тест слуху,
 * моделі на диску й характер живого голосу.
 *
 * Вигляд — той самий, що в решти Профілю ([SubScreen], [RowGroup]): групи
 * підписані тихим caption, моноширинного шрифту й бурштинових написів над
 * кожною карткою більше немає. Одна бурштинова дія на екрані — «перевірити
 * слух»; проба голосу й догрузка стилів — другорядні.
 */
@Composable
fun VoiceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val c = LocalPhantomColors.current
    val scope = rememberCoroutineScope()

    val states by container.modelManager.states.collectAsState()
    val ttsRouteFlow = remember(container) {
        (container.ttsEngine as? RoutedTts)?.currentRoute
            ?: kotlinx.coroutines.flow.MutableStateFlow(TtsRoute.None)
    }
    val ttsRoute by ttsRouteFlow.collectAsState()
    val voiceFallbackFlow = remember(container) {
        (container.ttsEngine as? RoutedTts)?.lastFallback
            ?: kotlinx.coroutines.flow.MutableStateFlow<VoiceFallback?>(null)
    }
    val voiceFallback by voiceFallbackFlow.collectAsStateWithLifecycle()
    val sttRouteFlow = remember(container) {
        (container.voiceManager as? VoiceManager)?.activeEngine
            ?: kotlinx.coroutines.flow.MutableStateFlow(SttRouteKind.None)
    }
    val sttRoute by sttRouteFlow.collectAsState()

    var pendingImport by remember { mutableStateOf<ModelType?>(null) }
    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            val target = pendingImport
            if (uri != null && target != null) {
                // Opening the stream is cheap and must happen while the
                // picker's URI grant is fresh; the multi-hundred-MB copy
                // then runs on the manager's own scope and closes it.
                runCatching { context.contentResolver.openInputStream(uri) }
                    .getOrNull()
                    ?.let { input -> container.modelManager.startImport(target, input) }
            }
            pendingImport = null
        },
    )

    SubScreen(title = stringResource(R.string.phantom_voice), onBack = onBack) {
        RowGroup(caption = stringResource(R.string.voice_engines)) {
            PaneSlot {
                Column {
                    SettingsRow(
                        title = stringResource(R.string.voice_now_voice),
                        value = stringResource(voiceRouteHumanRes(ttsRoute)),
                    )
                    voiceFallback?.let { miss ->
                        val at = remember(miss.atMs) {
                            SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(miss.atMs))
                        }
                        Text(
                            text = if (miss.reason == VoiceFallback.Reason.NO_VOICE) {
                                stringResource(R.string.voice_silent_line, at)
                            } else {
                                stringResource(
                                    R.string.voice_fallback_line,
                                    at,
                                    stringResource(VoiceFallbackNotice.reasonLabel(miss.reason)),
                                )
                            },
                            style = PhantomText.footnote,
                            color = c.danger,
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.voice_now_hearing),
                        value = stringResource(
                            if (sttRoute == SttRouteKind.None) R.string.voice_route_off else R.string.voice_route_on,
                        ),
                    )
                }
            }
        }

        // Просодія системного голосу: значення читаються рушієм НА КОЖНУ
        // фразу, тож зрушений повзунок чути з наступного речення.
        ProsodyCard()

        TestSttCard(scope = scope)

        RowGroup(caption = stringResource(R.string.voice_stt_models)) {
            listOf(ModelType.VOSK, ModelType.MOONSHINE_UK).forEachIndexed { i, type ->
                val state = states[type] ?: ModelState(type)
                if (i > 0) RowDivider()
                PaneSlot {
                    ModelRow(
                        state = state,
                        hasDownloadUrl = type.isFetchable,
                        onDownload = { container.modelManager.startDownload(type) },
                        onDelete = { container.modelManager.startDelete(type) },
                        onImport = {
                            pendingImport = type
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onInfo = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://" + type.infoUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                }
            }
        }

        // Живий голос вільний назавжди (вирок власника про системного робота:
        // «її взагалі не має бути»); замка перед завантаженням тут більше немає —
        // питати ліцензію про вільну функцію було б малюнком замка, не замком.
        val voiceModel = container.liveVoiceModel
        val supertonic = states[voiceModel] ?: ModelState(voiceModel)
        RowGroup(caption = stringResource(R.string.voice_clone_models)) {
            PaneSlot {
                Column {
                    ModelRow(
                        state = supertonic,
                        hasDownloadUrl = voiceModel.isFetchable,
                        // 05.09 privacy audit — this tap IS the consent LiveVoiceProvisioner
                        // now waits for; it does not call startDownload directly, so the
                        // same metered/disk gates apply whether the operator asks from here
                        // or from the CapabilityStrip prompt on the hub.
                        onDownload = { container.voicePrefs.setLiveVoiceRequested(true) },
                        onDelete = { container.modelManager.startDelete(voiceModel) },
                        onImport = {
                            pendingImport = voiceModel
                            importLauncher.launch(arrayOf("*/*"))
                        },
                        onInfo = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://" + ModelType.SUPERTONIC.infoUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        },
                    )
                    val liveVoiceFlow = remember { local.phantom.companion.data.LiveVoice.state }
                    val liveVoice by liveVoiceFlow.collectAsStateWithLifecycle()
                    if (liveVoice !is local.phantom.companion.data.LiveVoiceState.Ready) {
                        Spacer(modifier = Modifier.height(PhantomSpace.s2))
                        LiveVoiceRemedyRow(
                            state = liveVoice,
                            onRequest = { container.voicePrefs.setLiveVoiceRequested(true) },
                            onAllowMobile = { container.voicePrefs.setLiveVoiceOnMobile(true) },
                            onRetry = { local.phantom.companion.data.LiveVoice.retry() },
                        )
                    }
                }
            }
        }

        if (supertonic.isPresent) {
            VoiceCharacterCard(scope = scope)
        }
    }
}

/**
 * Character card: ten shipped styles and five pace stops. Every pick
 * answers with the same probe phrase — M1…F5 are opaque names, so the ear
 * is the only honest picker; a choice you cannot hear is not a choice.
 */
@Composable
private fun VoiceCharacterCard(
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val c = LocalPhantomColors.current
    val modelDir = remember { container.modelManager.getModelFile(container.liveVoiceModel) }
    // Одна фраза на всі проби: змінювати разом зі стилем і темпом означало б
    // порівнювати два голоси на різному тексті, тобто не порівнювати нічого.
    val previewPhrase = stringResource(R.string.voice_preview_phrase)

    var tuning by remember { mutableStateOf(VoiceTuning.current) }
    var stylesOnDisk by remember { mutableStateOf(setOf(VoiceTuning.DEFAULT_STYLE)) }
    var fetchingStyles by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            VoiceTuning.ensureLoaded(modelDir)
            stylesOnDisk = presentStyles(modelDir)
        }
        tuning = VoiceTuning.current
    }

    fun applyAndProve(next: VoiceTuning.Tuning) {
        tuning = next
        scope.launch {
            withContext(Dispatchers.IO) { VoiceTuning.update(modelDir, next) }
            container.ttsEngine.stop()
            container.ttsEngine.speak(previewPhrase)
        }
    }

    RowGroup(caption = stringResource(R.string.voice_character_title)) {
        PaneSlot {
            Column(modifier = Modifier.padding(vertical = PhantomSpace.s1)) {
                StyleRow(
                    label = stringResource(R.string.voice_styles_male),
                    prefix = "M",
                    selected = tuning.style,
                    onDisk = stylesOnDisk,
                    onPick = { applyAndProve(tuning.copy(style = it)) },
                )
                Spacer(modifier = Modifier.height(PhantomSpace.s3))
                StyleRow(
                    label = stringResource(R.string.voice_styles_female),
                    prefix = "F",
                    selected = tuning.style,
                    onDisk = stylesOnDisk,
                    onPick = { applyAndProve(tuning.copy(style = it)) },
                )

                if (stylesOnDisk.size < VoiceTuning.STYLES.size) {
                    Spacer(modifier = Modifier.height(PhantomSpace.s3))
                    Text(
                        text = stringResource(R.string.voice_styles_missing_hint),
                        style = PhantomText.footnote,
                        color = c.text3,
                    )
                    Spacer(modifier = Modifier.height(PhantomSpace.s2))
                    SecondaryButton(
                        label = stringResource(
                            if (fetchingStyles) R.string.voice_styles_fetching
                            else R.string.voice_styles_fetch,
                        ),
                        enabled = !fetchingStyles,
                        onClick = {
                            scope.launch {
                                fetchingStyles = true
                                runCatching {
                                    container.modelManager
                                        .startFetchExtras(container.liveVoiceModel)
                                        .join()
                                }
                                stylesOnDisk = withContext(Dispatchers.IO) { presentStyles(modelDir) }
                                fetchingStyles = false
                            }
                        },
                    )
                }

                Spacer(modifier = Modifier.height(PhantomSpace.s4))
                TempoControl(
                    durationScale = tuning.durationScale,
                    onChange = { applyAndProve(tuning.copy(durationScale = it)) },
                )

                Spacer(modifier = Modifier.height(PhantomSpace.s4))
                SecondaryButton(
                    label = stringResource(R.string.voice_preview_button),
                    enabled = true,
                    onClick = {
                        scope.launch {
                            container.ttsEngine.stop()
                            container.ttsEngine.speak(previewPhrase)
                        }
                    },
                )
            }
        }
    }
}

/**
 * Пʼять стилів одного роду — сегменти однієї доріжки, як «Мова» в Профілі:
 * обраний виступає поверхнею, а не бурштином. Стиль, якого немає на диску,
 * лишається на місці третім рівнем тексту й не відгукується.
 */
@Composable
private fun StyleRow(
    label: String,
    prefix: String,
    selected: String,
    onDisk: Set<String>,
    onPick: (String) -> Unit,
) {
    val c = LocalPhantomColors.current
    Column {
        Text(text = label, style = PhantomText.footnote, color = c.text3)
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(PhantomRadius.control))
                .background(c.overlay)
                .padding(PhantomSpace.s1)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
        ) {
            (1..5).forEach { n ->
                val name = "$prefix$n"
                val available = name in onDisk
                val on = name == selected
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = PhantomSpace.touchMinAndroid)
                        .clip(RoundedCornerShape(PhantomRadius.control - PhantomSpace.s1))
                        .background(if (on) c.surface else c.overlay)
                        .selectable(
                            selected = on,
                            enabled = available && !on,
                            role = Role.RadioButton,
                            onClick = { onPick(name) },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$n",
                        style = PhantomText.subhead,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                        color = when {
                            on -> c.text
                            available -> c.text2
                            else -> c.text3
                        },
                    )
                }
            }
        }
    }
}

/**
 * Five stops, not a raw slider (the house rule from the font control):
 * each stop is a named pace, and the probe phrase — not the number — is
 * the real feedback.
 */
@Composable
private fun TempoControl(
    durationScale: Float,
    onChange: (Float) -> Unit,
) {
    val c = LocalPhantomColors.current
    val stopIndex = VoiceSettingsCopy.TEMPO_STOPS.indices
        .minByOrNull { kotlin.math.abs(VoiceSettingsCopy.TEMPO_STOPS[it] - durationScale) } ?: 2
    Column {
        Text(text = stringResource(R.string.voice_tempo_label), style = PhantomText.footnote, color = c.text3)
        Spacer(modifier = Modifier.height(PhantomSpace.s2))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            TempoStepGlyph(
                label = "−",
                description = stringResource(R.string.voice_tempo_slower_a11y),
                enabled = stopIndex > 0,
                onClick = { onChange(VoiceSettingsCopy.TEMPO_STOPS[stopIndex - 1]) },
            )
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = PhantomSpace.touchMinAndroid)
                    .clip(RoundedCornerShape(PhantomRadius.control))
                    .background(c.overlay)
                    .padding(horizontal = PhantomSpace.s3),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(tempoStopLabel(stopIndex)),
                    style = PhantomText.subhead,
                    fontWeight = FontWeight.SemiBold,
                    color = c.text,
                )
                Spacer(modifier = Modifier.width(PhantomSpace.s2))
                Text(
                    text = "×%.2f".format(1f / VoiceSettingsCopy.TEMPO_STOPS[stopIndex]),
                    style = PhantomText.callout,
                    color = c.text3,
                )
            }
            TempoStepGlyph(
                label = "+",
                description = stringResource(R.string.voice_tempo_faster_a11y),
                enabled = stopIndex < VoiceSettingsCopy.TEMPO_STOPS.lastIndex,
                onClick = { onChange(VoiceSettingsCopy.TEMPO_STOPS[stopIndex + 1]) },
            )
        }
    }
}

@Composable
private fun TempoStepGlyph(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Box(
        modifier = Modifier
            .size(PhantomSpace.touchMinAndroid)
            .clip(CircleShape)
            .background(c.overlay)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = PhantomText.headline,
            color = if (enabled) c.text else c.text3,
        )
    }
}

/** Другорядна дія: волосина й текст, без заливки — бурштин на екрані один. */
@Composable
private fun SecondaryButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.control)
    Box(
        modifier = Modifier
            .heightIn(min = PhantomSize.buttonL)
            .clip(shape)
            .border(1.dp, c.lineStrong, shape)
            .clickable(enabled = enabled, role = Role.Button) { onClick() }
            .padding(horizontal = PhantomSpace.s5),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = PhantomText.subhead, color = if (enabled) c.text else c.text3)
    }
}

@StringRes
private fun tempoStopLabel(index: Int): Int = when (index) {
    0 -> R.string.voice_tempo_slow
    1 -> R.string.voice_tempo_calm
    2 -> R.string.voice_tempo_default
    3 -> R.string.voice_tempo_fast
    else -> R.string.voice_tempo_max
}

private fun presentStyles(modelDir: File): Set<String> =
    VoiceTuning.STYLES.filterTo(mutableSetOf()) { name ->
        name == VoiceTuning.DEFAULT_STYLE ||
            File(modelDir, "${SupertonicAssets.STYLES_DIR}/$name.json").length() > 0
    }

/**
 * SV-1 — five-second listen card. Captures via `voiceManager.startCapturing()`,
 * waits, finalises through `stopCapturing()`, displays the transcript inline.
 *
 * Honest about preconditions: muted mic and disabled STT short-circuit
 * to a clear status string instead of starting a phantom recording.
 * Master mute (MIC-2) is checked here too — Live Dialog will share the
 * same gate.
 */
@Composable
private fun TestSttCard(
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val c = LocalPhantomColors.current

    val muted by container.voicePrefs.micMuted.collectAsState()
    val sttEnabled by container.voicePrefs.sttEnabled.collectAsState()

    var transcript by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableIntStateOf(R.string.voice_test_idle) }
    var capturing by remember { mutableStateOf(false) }
    var remainingSec by remember { mutableIntStateOf(0) }

    val canRun = !muted && sttEnabled && !capturing

    RowGroup(caption = stringResource(R.string.voice_test_title)) {
        PaneSlot {
            Column(modifier = Modifier.padding(vertical = PhantomSpace.s1)) {
                Text(
                    text = when {
                        capturing -> stringResource(R.string.voice_test_listening, remainingSec)
                        muted -> stringResource(R.string.voice_test_muted)
                        !sttEnabled -> stringResource(R.string.voice_test_stt_off)
                        else -> stringResource(status)
                    },
                    style = PhantomText.body,
                    color = if (muted || !sttEnabled) c.danger else c.text,
                )

                Spacer(modifier = Modifier.height(PhantomSpace.s3))

                PrimaryButton(
                    label = stringResource(
                        if (capturing) R.string.voice_test_button_live else R.string.voice_test_button,
                    ),
                    enabled = canRun,
                    onClick = {
                        scope.launch {
                            transcript = null
                            capturing = true
                            container.voiceManager.startCapturing()
                            for (i in 5 downTo 1) {
                                remainingSec = i
                                delay(1_000L)
                            }
                            val result = runCatching { container.voiceManager.stopCapturing() }
                                .getOrNull()
                            capturing = false
                            remainingSec = 0
                            transcript = result?.takeIf { it.isNotBlank() }
                            status = if (transcript == null) {
                                R.string.voice_test_nothing
                            } else {
                                R.string.voice_test_heard
                            }
                        }
                    },
                )

                if (capturing) {
                    Spacer(modifier = Modifier.height(PhantomSpace.s3))
                    LinearProgressIndicator(
                        progress = { remainingSec / 5f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PhantomSpace.s1)
                            .clip(CircleShape),
                        color = c.accent,
                        trackColor = c.overlay,
                        strokeCap = StrokeCap.Round,
                    )
                }

                transcript?.let { t ->
                    Spacer(modifier = Modifier.height(PhantomSpace.s3))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(PhantomRadius.control))
                            .background(c.overlay)
                            .padding(PhantomSpace.s3),
                    ) {
                        Text(text = "«$t»", style = PhantomText.body, color = c.text)
                    }
                }
            }
        }
    }
}

/**
 * SV-1 — pure copy + helpers. Pinned by VoiceSettingsScreenTest so a
 * silent rewrite of the operator-visible labels breaks CI.
 */
internal object VoiceSettingsCopy {
    /** Drift here misleads the operator about how much LTE quota a fetch costs. */
    const val SUPERTONIC_SIZE_MB: Int = 400

    /**
     * Темп мови: пʼять зупинок замість повзунка.
     *
     * Величина — `durationScale` синтезу, тож МЕНШЕ означає ШВИДШЕ; список
     * спадає, і індекс росте разом зі швидкістю. Тому «−» рухає до початку.
     *
     * Зупинки, а не безперервний повзунок, бо різницю в сотих частках вухо
     * не чує, а знайти нею попереднє звучання неможливо. Індекс 2 — 0.85f,
     * тобто те, що було зашито константою й що чув власник досі.
     *
     * Межі збігаються з [VoiceTuning.MIN_DURATION_SCALE] і
     * [VoiceTuning.MAX_DURATION_SCALE]: екран не пропонує того, що синтез
     * потім мовчки обріже.
     */
    val TEMPO_STOPS: FloatArray = floatArrayOf(1.25f, 1.05f, 0.85f, 0.72f, 0.60f)
}

/** Що людина чує, а не яким рушієм: назви рушіїв лишились діагностиці. */
@StringRes
internal fun voiceRouteHumanRes(route: TtsRoute): Int = when (route) {
    TtsRoute.Baked -> R.string.voice_route_nav
    TtsRoute.Premium -> R.string.model_label_voice
    TtsRoute.System -> R.string.voice_route_system
    TtsRoute.None -> R.string.voice_route_off
}

@StringRes
internal fun ttsRouteLabel(route: TtsRoute): Int = when (route) {
    TtsRoute.Baked -> R.string.tts_route_baked
    TtsRoute.Premium -> R.string.tts_route_premium
    TtsRoute.System -> R.string.tts_route_system
    TtsRoute.None -> R.string.route_off
}

@StringRes
internal fun sttRouteLabel(route: SttRouteKind): Int = when (route) {
    SttRouteKind.Hybrid -> R.string.stt_route_hybrid
    SttRouteKind.MoonshineOnly -> R.string.stt_route_moonshine
    SttRouteKind.VoskOnly -> R.string.stt_route_vosk
    SttRouteKind.None -> R.string.route_off
}

internal fun isOnMeteredNetwork(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        ?: return false
    val active = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(active) ?: return false
    val isUnmetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    return !isUnmetered
}

/**
 * Висота й темп СИСТЕМНОГО голосу — окремо від картки характеру, бо та
 * крутить рушій Supertonic, а ці два повзунки його не чіпають узагалі.
 */
@Composable
private fun ProsodyCard() {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as PhantomApp).container }
    val c = LocalPhantomColors.current
    val pitch by container.voicePrefs.pitch.collectAsStateWithLifecycle()
    val rate by container.voicePrefs.rate.collectAsStateWithLifecycle()
    val touched by container.voicePrefs.prosody.collectAsStateWithLifecycle()

    RowGroup(caption = stringResource(R.string.voice_prosody)) {
        PaneSlot {
            Column {
                ProsodyRow(
                    label = stringResource(R.string.voice_pitch),
                    value = pitch,
                    onChange = { container.voicePrefs.setPitch(it) },
                )
                ProsodyRow(
                    label = stringResource(R.string.voice_rate),
                    value = rate,
                    onChange = { container.voicePrefs.setRate(it) },
                )
                // Кнопка живе лише поки є що повертати: доки людина не торкалась
                // повзунків, PHANTOM не має думки про темп, і «повернути як було»
                // не мало б що робити — крім як створити ту саму думку.
                if (touched != null) {
                    GhostButton(
                        label = stringResource(R.string.voice_prosody_reset),
                        onClick = { container.voicePrefs.resetProsody() },
                        modifier = Modifier.offset(x = -PhantomSpace.s4),
                    )
                } else {
                    Text(
                        text = stringResource(R.string.voice_prosody_untouched),
                        style = PhantomText.footnote,
                        color = c.text3,
                        modifier = Modifier.padding(vertical = PhantomSpace.s2),
                    )
                }
            }
        }
    }
}

/**
 * Один ряд просодії: підпис, поточне значення числом, повзунок.
 *
 * Число показане навмисно. Повзунок без числа лишає людину гадати, наскільки
 * саме вона зрушила голос, — а тут кожен крок чути лише на НАСТУПНІЙ фразі,
 * тож зворотний звʼязок мусить бути видимим одразу.
 */
@Composable
private fun ProsodyRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
) {
    val c = LocalPhantomColors.current
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = PhantomSpace.s2),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = label, style = PhantomText.body, color = c.text)
            Text(text = String.format(Locale.US, "%.2f×", value), style = PhantomText.callout, color = c.text3)
        }
        PhantomSlider(value = value, onValueChange = onChange, valueRange = Prosody.MIN..Prosody.MAX)
    }
}
