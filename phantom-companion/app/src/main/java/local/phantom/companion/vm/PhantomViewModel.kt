package local.phantom.companion.vm

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.PhantomApp
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.PairedDevice
import local.phantom.companion.net.LinkStatus
import local.phantom.companion.net.PhantomApi
import local.phantom.companion.core.context.SystemState
import local.phantom.companion.core.ai.ModelType
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.voice.moonshine.MoonshineSttEngine
import local.phantom.companion.feature.stream.domain.StreamItem
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.THEME_MODE_PREF_KEY
import local.phantom.companion.core.design.theme.THEME_PREFS_FILE
import local.phantom.companion.core.design.theme.FONT_SCALE_PREF_KEY
import local.phantom.companion.core.design.theme.ThemeMode
import local.phantom.companion.core.design.theme.startupThemeMode
import local.phantom.companion.core.design.theme.resolveTheme
import local.phantom.companion.core.net.crypto.bytesToB64
import java.util.Locale

/**
 * Single root ViewModel — the alternative was three or four small VMs that all
 * needed to read each other's state. With the Tier 1 surface (paired-or-not,
 * link status, vitals snapshot, ptt mode) one holder is the simplest thing
 * that works and stays under 200 lines.
 */
class PhantomViewModel(
    private val container: AppContainer,
    private val context: Context,
) : ViewModel() {

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private val _state = MutableStateFlow(
        UiState(paired = container.store.load()),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    // ── Channel flows ────────────────────────────────────────────────────────
    // Each priority channel from COMPANION_CONTRACT.md §3 gets its own flow so
    // UI screens can subscribe independently without touching PhantomLink or
    // the channel dispatch loop.  SharedFlows use replay=0 (event semantics);
    // StateFlows hold the latest snapshot value.  Flows are exposed even before
    // UI consumers exist so the wiring is in place for future screens.

    private val _contextState = MutableStateFlow<ContextSnapshot?>(null)
    val contextState: StateFlow<ContextSnapshot?> = _contextState.asStateFlow()

    private val _familiarState = MutableStateFlow<FamiliarSnapshot?>(null)
    val familiarState: StateFlow<FamiliarSnapshot?> = _familiarState.asStateFlow()

    private val _pairEvents = MutableSharedFlow<PairEvent>(replay = 0, extraBufferCapacity = 8)
    val pairEvents: SharedFlow<PairEvent> = _pairEvents.asSharedFlow()

    private val _agentEvents = MutableSharedFlow<AgentEvent>(replay = 0, extraBufferCapacity = 16)
    val agentEvents: SharedFlow<AgentEvent> = _agentEvents.asSharedFlow()

    private val _chatEvents = MutableSharedFlow<ChatEvent>(replay = 0, extraBufferCapacity = 16)
    val chatEvents: SharedFlow<ChatEvent> = _chatEvents.asSharedFlow()

    private val _mapEvents = MutableSharedFlow<MapEvent>(replay = 0, extraBufferCapacity = 16)
    val mapEvents: SharedFlow<MapEvent> = _mapEvents.asSharedFlow()

    private val _messengerEvents =
        MutableSharedFlow<MessengerEvent>(replay = 0, extraBufferCapacity = 32)
    val messengerEvents: SharedFlow<MessengerEvent> = _messengerEvents.asSharedFlow()

    private val _controlVerbs = MutableSharedFlow<ControlVerb>(replay = 0, extraBufferCapacity = 8)
    val controlVerbs: SharedFlow<ControlVerb> = _controlVerbs.asSharedFlow()

    private val _handoffEvents = MutableSharedFlow<HandoffEvent>(replay = 0, extraBufferCapacity = 8)
    val handoffEvents: SharedFlow<HandoffEvent> = _handoffEvents.asSharedFlow()

    private val _miscEvents = MutableSharedFlow<MiscEvent>(replay = 0, extraBufferCapacity = 32)
    val miscEvents: SharedFlow<MiscEvent> = _miscEvents.asSharedFlow()

    /** One handle to the theme store, opened once. The file name used to be a
     *  bare literal repeated at three call sites — see [THEME_PREFS_FILE]. */
    private val themePrefs: android.content.SharedPreferences by lazy {
        context.getSharedPreferences(THEME_PREFS_FILE, Context.MODE_PRIVATE)
    }

    init {
        // QH2 — load theme preference. New key is `ui_theme_mode` and
        // stores the [ThemeMode] enum name. Legacy key was `ui_theme`
        // and stored the spec id ("sunrise-warm" / "amber-night");
        // [startupThemeMode] handles both shapes so an upgrade
        // doesn't surprise-revert operators to System mode.
        //
        // A Living→Dark rewrite used to run here. It is gone with the default
        // it served: installs that never reached it keep their Living, and a
        // stored `Dark` is left alone because the rewrite wrote the same four
        // bytes the settings toggle writes — a deliberate Dark and a rewritten
        // one are indistinguishable on disk. Its marker
        // `ui_theme_living_migrated_v1` is already true in the wild, so a
        // future migration must take a NEW key or it silently no-ops.
        val mode = startupThemeMode(
            themePrefs.getString(THEME_MODE_PREF_KEY, null),
            themePrefs.getString("ui_theme", null),
        )
        // Initial spec uses systemDark=false + the sun over the time zone's
        // centre; MainActivity overrides by recomputing from themeMode +
        // isSystemInDarkTheme + the sun where the operator stands.
        val storedScale = themePrefs.getFloat(FONT_SCALE_PREF_KEY, local.phantom.companion.core.design.theme.FONT_SCALE_DEFAULT)
        _state.update {
            it.copy(
                themeMode = mode,
                theme = resolveTheme(mode, systemDark = false, look = local.phantom.companion.ui.theme.LivingSun.lookAt(System.currentTimeMillis(), local.phantom.companion.ui.theme.SunPlaces.fallbackNow())),
                fontScale = storedScale.coerceIn(
                    local.phantom.companion.core.design.theme.FONT_SCALE_MIN,
                    local.phantom.companion.core.design.theme.FONT_SCALE_MAX,
                ),
            )
        }

        if (state.value.paired != null) {
            container.link.start()
            container.symbiote.start()
        }
        viewModelScope.launch {
            container.link.connected.collect { ls ->
                val old = _state.value.linkStatus
                _state.update { it.copy(linkStatus = ls) }
                if (old != ls) {
                    voiceLinkStatusChange(ls)
                }
            }
        }
        viewModelScope.launch {
            container.modelManager.states.collect { ms ->
                _state.update { it.copy(modelStates = ms) }
                
                // If any STT model just became present, refresh VoiceManager
                val voskPresent = ms[ModelType.VOSK]?.isPresent == true
                val moonshinePresent = ms[ModelType.MOONSHINE_UK]?.isPresent == true

                if (voskPresent || moonshinePresent) {
                    // Тут стояв getModelFile(VOSK) — ЗОВНІШНЯ тека
                    // files/models/vosk, тоді як org.vosk.Model() потребує
                    // вкладену, де лежать am/conf/graph. AppContainer і
                    // ContainerBoot беруть її через resolveVoskModelDir(),
                    // а цей обробник — ні, і спрацьовував ПІСЛЯ них: Vosk
                    // піднімався на старті, а потім Model(outer) кидав
                    // виняток, initStt мовчки ставив FakeSttEngine, і той
                    // сипав у стрічку «слухаю… (10)», «(20)», «(30)».
                    val voskDir = container.modelManager.resolveVoskModelDir()?.absolutePath
                    val moonshineDir = container.modelManager
                        .getModelFile(ModelType.MOONSHINE_UK)
                        .takeIf { MoonshineSttEngine.allPresent(it) }
                    // Ямнет передається СЮДИ теж, хоч мова про слух: refreshModels
                    // перебудовує і класифікатор, а null означає «звільнити його».
                    // Без цього рядка приземлення моделі слуху глушило розпізнавання
                    // звуків, яке підняв AppContainer.
                    val yamnetPath = container.modelManager
                        .getModelFile(ModelType.YAMNET)
                        .takeIf { it.exists() && it.length() > 0 }
                        ?.absolutePath
                    container.voiceManager.refreshModels(voskDir, yamnetPath, moonshineDir)
                }
            }
        }
        viewModelScope.launch {
            container.voiceManager.partialResults.collect { partial ->
                _state.update { it.copy(partialText = partial) }
            }
        }
        viewModelScope.launch {
            container.voiceManager.sttResults.collect {
                // STT settled — clear the live partial indicator. AI dispatch is StreamViewModel's job.
                _state.update { it.copy(partialText = null) }
            }
        }
        viewModelScope.launch {
            container.voiceManager.audioLevel.collect { level ->
                if (_state.value.ptt.state == local.phantom.companion.core.design.component.PttState.Capturing) {
                    _state.update { it.copy(ptt = it.ptt.copy(audioLevel = level)) }
                }
            }
        }
        viewModelScope.launch {
            container.link.events.collect { event ->
                val data = runCatching { event.raw["data"] as? JsonObject }.getOrNull()
                when (event.channel) {
                    // ── existing handlers (unchanged) ────────────────────────
                    "state" -> {
                        val name = data?.get("state")?.jsonPrimitive?.contentOrNull()
                        if (name != null) {
                            _state.update { it.copy(systemState = parseState(name)) }
                        }
                    }
                    "sensor" -> {
                        val body = runCatching { data?.get("body") as? JsonObject }.getOrNull()
                        val bpm = body?.get("bpm")?.jsonPrimitive?.contentOrNull()?.toFloatOrNull()?.toInt()
                        if (bpm != null) {
                            _state.update { it.copy(bpm = bpm) }
                        }
                    }

                    // ── context / preferences_changed ────────────────────────
                    // payload: {changes:{...}, preferences:{...}}
                    "context" -> runCatching {
                        val changes = data?.get("changes") as? JsonObject
                        val prefs = data?.get("preferences") as? JsonObject
                        _contextState.value = ContextSnapshot(
                            changes = changes,
                            preferences = prefs,
                            raw = event.raw,
                        )
                    }.getOrNull()

                    // ── familiar / manifestation ─────────────────────────────
                    // payload: varies — no fixed schema; surface raw + type verb
                    "familiar" -> runCatching {
                        _familiarState.value = FamiliarSnapshot(
                            type = event.type,
                            raw = event.raw,
                        )
                    }.getOrNull()

                    // ── pair / claimed | revoked ─────────────────────────────
                    // claimed: {device_id, device_name, device_model, platform,
                    //           user_id, profile_id, profile_display_name, paired_at}
                    // revoked: {device_id, reason}
                    "pair" -> runCatching {
                        _pairEvents.emit(
                            PairEvent(
                                kind = event.type,
                                deviceId = data?.get("device_id")?.jsonPrimitive?.contentOrNull(),
                                deviceName = data?.get("device_name")?.jsonPrimitive?.contentOrNull(),
                                userId = data?.get("user_id")?.jsonPrimitive?.contentOrNull(),
                                reason = data?.get("reason")?.jsonPrimitive?.contentOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── agent.stream / proactive | emotion | monologue ───────
                    // types: proactive.cycle | proactive.pending_action |
                    //        proactive.action_fired | emotion.updated |
                    //        task.report_acknowledged | inner-monologue events
                    "agent.stream" -> runCatching {
                        _agentEvents.emit(
                            AgentEvent(
                                kind = event.type,
                                text = data?.get("text")?.jsonPrimitive?.contentOrNull(),
                                emotion = data?.get("emotion")?.jsonPrimitive?.contentOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── chat / message | stream | message.proactive ──────────
                    // message:  {message, session_id}
                    // stream:   {message_id, delta, done, message?}
                    "chat" -> runCatching {
                        _chatEvents.emit(
                            ChatEvent(
                                kind = event.type,
                                sessionId = data?.get("session_id")?.jsonPrimitive?.contentOrNull(),
                                messageId = data?.get("message_id")?.jsonPrimitive?.contentOrNull(),
                                delta = data?.get("delta")?.jsonPrimitive?.contentOrNull(),
                                done = data?.get("done")?.jsonPrimitive?.contentOrNull()?.toBooleanStrictOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── messenger / message:new ─────────────────────────────
                    // data: MessageOut — стрічка вузла оживає без опитування.
                    "messenger" -> runCatching {
                        _messengerEvents.emit(
                            MessengerEvent(
                                kind = event.type,
                                conversationId = data?.get("conversation_id")
                                    ?.jsonPrimitive?.contentOrNull(),
                                messageId = data?.get("id")?.jsonPrimitive?.contentOrNull(),
                                seq = data?.get("seq")?.jsonPrimitive?.contentOrNull()
                                    ?.toIntOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── map / <mutation op> ──────────────────────────────────
                    // ops: plan_route, add_layer, add_marker, flyto, set_view,
                    //      enable_layer, disable_layer, isochrone, wardriving_update, …
                    "map" -> runCatching {
                        _mapEvents.emit(
                            MapEvent(
                                op = event.type,
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── companion_control / <verb> ───────────────────────────
                    // payload: {verb, payload, target_device_id?}
                    "companion_control" -> runCatching {
                        val innerPayload = runCatching { data?.get("payload") as? JsonObject }.getOrNull()
                        _controlVerbs.emit(
                            ControlVerb(
                                verb = data?.get("verb")?.jsonPrimitive?.contentOrNull() ?: event.type,
                                payload = innerPayload,
                                targetDeviceId = data?.get("target_device_id")?.jsonPrimitive?.contentOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── handoff / created | accepted | rejected | cancelled ──
                    // payload: HandoffOut — {id, kind, status, ...}
                    "handoff" -> runCatching {
                        _handoffEvents.emit(
                            HandoffEvent(
                                kind = event.type,
                                handoffId = data?.get("id")?.jsonPrimitive?.contentOrNull(),
                                handoffKind = data?.get("kind")?.jsonPrimitive?.contentOrNull(),
                                status = data?.get("status")?.jsonPrimitive?.contentOrNull(),
                                raw = data ?: JsonObject(emptyMap()),
                            )
                        )
                    }.getOrNull()

                    // ── catch-all: drive, settings, oled, background_events, ─
                    //    standing_orders, _meta, profile_sync (preference_changed,
                    //    profile_imported, fact_upserted, fact_deleted,
                    //    behavior_updated, profile_renamed)
                    //    Never drop — route to miscEvents keyed by channel name.
                    //    profile_sync currently lands here; once a Profile UI
                    //    surface needs typed access, lift it to its own flow
                    //    (COMPANION_CONTRACT.md §10.11).
                    else -> runCatching {
                        _miscEvents.emit(
                            MiscEvent(
                                channel = event.channel,
                                type = event.type,
                                raw = event.raw,
                            )
                        )
                    }.getOrNull()
                }
            }
        }
    }

    private fun parseState(name: String): SystemState = runCatching {
        SystemState.valueOf(name.uppercase())
    }.getOrDefault(SystemState.FOCUS)

    fun beginClaim() {
        _state.update { it.copy(pairError = null) }
    }

    fun claimFromQrJson(qrJson: String) {
        viewModelScope.launch {
            _state.update { it.copy(pairError = null) }
            val digits = local.phantom.companion.data.PcPairing.parse(qrJson) is
                local.phantom.companion.data.PcPairing.Input.Digits
            when (val out = local.phantom.companion.data.PcPairing(container).claim(qrJson)) {
                is local.phantom.companion.data.PcPairing.Outcome.Paired ->
                    _state.update { it.copy(paired = out.device, pairError = null) }
                is local.phantom.companion.data.PcPairing.Outcome.Failed -> _state.update {
                    it.copy(
                        pairError = local.phantom.companion.ui.screens.pairFailureText(
                            context = context,
                            why = out.why,
                            detail = out.detail,
                            digits = digits,
                        ),
                    )
                }
            }
        }
    }

    fun unpair() {
        viewModelScope.launch {
            val gone = container.store.load()
            container.link.stop()
            container.api.unpair()
            // Unpairing removes THAT node, not the roster: a second device or
            // a person keeps their proved key. Обидва імені: записи до 03.09
            // лежать у реєстрі під `deviceId`, нові — під сітьовим `nodePeerId`.
            gone?.deviceId?.let { container.peers.remove(it) }
            gone?.nodePeerId?.takeIf { it.isNotBlank() }?.let { container.peers.remove(it) }
            _state.update {
                UiState(paired = null)
            }
        }
    }

    fun dispatchCommand(command: StreamItem.Command) {
        when (command.action) {
            "OPEN_SETTINGS" -> {
                // Settings dialog is galled by local state in StreamNav
            }
        }
    }

    /**
     * QH2 — operator selects a [ThemeMode] preference; the actual
     * resolution to [PhantomThemeSpec] happens in MainActivity using
     * `isSystemInDarkTheme()`. We refresh `state.theme` here with a
     * `systemDark = false` placeholder so non-Composable readers
     * (e.g. tests) see *some* spec; MainActivity then re-resolves
     * with the real system flag on every recomposition, which is
     * what actually drives the visible UI.
     */
    fun setThemeMode(mode: ThemeMode) {
        _state.update {
            it.copy(themeMode = mode, theme = resolveTheme(mode, systemDark = false, look = local.phantom.companion.ui.theme.LivingSun.lookAt(System.currentTimeMillis(), local.phantom.companion.ui.theme.SunPlaces.fallbackNow())))
        }
        themePrefs.edit().putString(THEME_MODE_PREF_KEY, mode.name).apply()
    }

    /**
     * ТВІД T4 — persist and apply the operator's text-size dial. Clamped
     * to the legible band; MainActivity's [PhantomTheme] re-reads
     * `state.fontScale` on the next recomposition, so the whole UI
     * resizes live with no restart.
     */
    fun setFontScale(scale: Float) {
        val clamped = scale.coerceIn(
            local.phantom.companion.core.design.theme.FONT_SCALE_MIN,
            local.phantom.companion.core.design.theme.FONT_SCALE_MAX,
        )
        _state.update { it.copy(fontScale = clamped) }
        themePrefs.edit().putFloat(FONT_SCALE_PREF_KEY, clamped).apply()
    }

    fun overrideSystemState(state: SystemState) {
        val old = _state.value.systemState
        _state.update { it.copy(systemState = state) }
        if (old != state) {
            voiceStateChange(state)
        }
    }

    /**
     * Mode changes are NOT spoken either. The context engine re-decides on a
     * timer, and «Режим фокусу активовано» out of a pocket is noise the
     * operator never asked for.
     */
    private fun voiceStateChange(state: SystemState) = Unit

    /**
     * Link transitions are NOT spoken. The PC link reconnects on its own
     * schedule — every Wi-Fi hiccup, every backoff cycle — so announcing it
     * turned a background detail into a voice talking at the operator all day.
     * The status is on screen; that is where a status belongs.
     */
    private fun voiceLinkStatusChange(status: local.phantom.companion.net.LinkStatus) = Unit

    private fun voiceIfEnabled(text: String) {
        if (text.isBlank()) return
        val tts = container.ttsEngine
        // DND check: don't speak state changes if in FOCUS or DREAM unless it's the 
        // transition OUT of those states or a critical alert.
        val current = _state.value.systemState
        val isDnd = current == SystemState.FOCUS || current == SystemState.DREAM
        
        // Simplified gate: if DND, only speak if it's a state change TO something else
        // or if explicitly allowed. For now, just allow all state changes to be voiced.
        
        container.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { 
                val normalized = local.phantom.companion.core.voice.UaTtsNormalizer.normalize(text)
                tts.speak(normalized) 
            }
        }
    }

    // Not viewModelScope: a model transfer outlives this ViewModel. The
    // manager owns the work; we only pull the trigger.
    fun downloadModel(type: ModelType) {
        container.modelManager.startDownload(type)
    }

    fun deleteModel(type: ModelType) {
        container.modelManager.startDelete(type)
    }

    fun setPtt(state: PttRuntime) {
        val old = _state.value.ptt.state
        val next = state.state

        // MIC-2 — master mute hard-stop. When the operator has flipped
        // the Settings → Privacy → Микрофон toggle, no surface may
        // open the recorder. Snap the requested Capturing transition
        // back to Idle and surface a one-line Notice via errorBus so
        // the operator sees a Stream tile explaining why the press
        // didn't work — instead of the orb pulsing silently into a
        // recording that never starts.
        if (next == local.phantom.companion.core.design.component.PttState.Capturing &&
            container.voicePrefs.micMuted.value
        ) {
            _state.update {
                it.copy(ptt = state.copy(state = local.phantom.companion.core.design.component.PttState.Idle))
            }
            container.errorBus.emit(
                source = "Microphone",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Info,
                message = "мікрофон вимкнено · Налаштування → Приватність → Микрофон",
            )
            return
        }

        // Phase 2-A5 — `sttEnabled` used to have no writer anywhere and no
        // gate here, so the switch that now lives in MicPrivacyScreen would
        // have been cosmetic. Same hard-stop shape as the mic-mute check
        // above: snap Capturing back to Idle instead of opening the recorder.
        if (next == local.phantom.companion.core.design.component.PttState.Capturing &&
            !container.voicePrefs.sttEnabled.value
        ) {
            _state.update {
                it.copy(ptt = state.copy(state = local.phantom.companion.core.design.component.PttState.Idle))
            }
            container.errorBus.emit(
                source = "Microphone",
                severity = local.phantom.companion.core.data.errors.PhantomError.Severity.Info,
                message = "розпізнавання мовлення вимкнено · Налаштування → Приватність → Микрофон",
            )
            return
        }

        _state.update { it.copy(ptt = state) }

        if (old != next) {
            when (next) {
                local.phantom.companion.core.design.component.PttState.Capturing -> {
                    container.voiceManager.startCapturing()
                }
                local.phantom.companion.core.design.component.PttState.Idle -> {
                    if (old == local.phantom.companion.core.design.component.PttState.Capturing) {
                        viewModelScope.launch {
                            container.voiceManager.stopCapturing()
                        }
                    }
                }
                else -> Unit
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as PhantomApp
                PhantomViewModel(PhantomApp.container(), app)
            }
        }
    }
}

private fun kotlinx.serialization.json.JsonPrimitive.contentOrNull(): String? = runCatching { content }.getOrNull()

data class PttRuntime(val state: local.phantom.companion.core.design.component.PttState, val audioLevel: Float = 0f)

data class UiState(
    val paired: PairedDevice? = null,
    val pairError: String? = null,
    val linkStatus: LinkStatus = LinkStatus.Disconnected,
    val systemState: SystemState = SystemState.FOCUS,
    /**
     * QH2 + R0.4 — operator preference (Living/Light/Dark/System);
     * Living is the default. [theme] is the resolved spec for that
     * preference; MainActivity recomputes it with the live
     * `isSystemInDarkTheme()` flag and the clock hour.
     *
     * 15.09 — тут стояв `Dark`, і це суперечило рядку вище: коментар казав
     * «Living is the default», код казав інше, і код вигравав. Перше вікно
     * застосунку (до того, як прочитаються налаштування) блимало ніччю навіть
     * там, де людина мала побачити день. Тепер обидва кажуть одне.
     */
    val themeMode: ThemeMode = ThemeMode.Living,
    val theme: PhantomThemeSpec = SunriseWarm,
    /** ТВІД T4 — operator's global text-size dial (Settings → APPEARANCE);
     *  applied by MainActivity's [PhantomTheme]. */
    val fontScale: Float = local.phantom.companion.core.design.theme.FONT_SCALE_DEFAULT,
    val bpm: Int? = null,
    val breath: Int? = null,
    val stress: Float? = null,
    val ptt: PttRuntime = PttRuntime(local.phantom.companion.core.design.component.PttState.Idle),
    val partialText: String? = null,
    val modelStates: Map<ModelType, ModelState> = emptyMap(),
)
