package local.phantom.companion.feature.ambient.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import local.phantom.companion.core.design.component.WhisperLineDefaultMessages
import local.phantom.companion.core.design.component.WhisperLineRibbon
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.feature.ambient.denylist.SensitiveAppDenylist
import local.phantom.companion.feature.ambient.whisper.ForegroundAppMonitor
import local.phantom.companion.feature.ambient.whisper.WhisperEventBus
import local.phantom.companion.feature.ambient.whisper.WhisperLineMessageMapper
import local.phantom.companion.feature.ambient.whisper.WhisperQueueState
import local.phantom.companion.feature.ambient.whisper.WhisperTuning
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * Phase 3-E1 — Whisper Line presence overlay.
 *
 * Attaches a ComposeView via WindowManager: a 1.5dp always-on stripe
 * at the very top of the screen carrying ambient amber-faint text that
 * drifts past every screen.
 *
 * Phase 3-E1 ships the scaffolding only — the ComposeView contains a
 * placeholder transparent stripe that Phase 3-E2 will fill with the
 * real [WhisperLineRibbon] (already shipped for the preview surface
 * in Phase 1.5-C; that composable will move to `:core-design` so this
 * module can consume it without depending on `:feature-stream`).
 *
 * Lifecycle and View-Tree wiring is the stock overlay recipe; the
 * foreground notification + START_STICKY are what keep it alive. The
 * ribbon must survive process kill / restore
 * because its «I'm always here» promise breaks if the operator sees it
 * disappear and reappear; START_STICKY tells Android to recreate the
 * service automatically when memory pressure passes.
 */
class WhisperLineService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val tag = "WhisperLineService"

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var composeView: ComposeView? = null

    /**
     * Phase 3-E3 — owned by the service so the Composable can observe
     * via [collectAsState] and the bus subscriber can mutate via
     * [WhisperQueueState.enqueue]. Cleared on onDestroy.
     */
    private val queue: WhisperQueueState = WhisperQueueState()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        WhisperLineNotification.registerChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Surface the foreground notification first so we satisfy the
        // Android 12+ requirement to show within ~5s of starting; only
        // then check the overlay permission.
        // Знак Nightpost для рядка стану (app: ic_stat_nightpost — мікрокрій,
        // білий на прозорому), як у решти сповіщень. Модуль не бачить R
        // застосунку, тож шукаємо за назвою; якщо ресурсу нема (інша збірка) —
        // іконка застосунку, як було.
        @Suppress("DiscouragedApi")
        val smallIcon = resources.getIdentifier("ic_stat_nightpost", "drawable", packageName)
            .takeIf { it != 0 } ?: applicationInfo.icon
        startForeground(WhisperLineNotification.NOTIF_ID, WhisperLineNotification.build(this, smallIcon))

        if (!Settings.canDrawOverlays(this)) {
            Log.w(tag, "SYSTEM_ALERT_WINDOW permission not granted; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        if (composeView != null) return START_STICKY

        attachRibbonOverlay()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        return START_STICKY
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        queue.clear()
        runCatching {
            composeView?.let { windowManager.removeView(it) }
        }.onFailure { Log.w(tag, "removeView failed: ${it.message}") }
        composeView = null
        // Stop foreground; pre-T removeNotification flag is true so the
        // status-bar entry disappears immediately on stop.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        super.onDestroy()
    }

    private fun attachRibbonOverlay() {
        val density = resources.displayMetrics.density
        val lp = WhisperLineLayoutSpec.layoutParams(density)

        val view = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setViewTreeLifecycleOwner(this@WhisperLineService)
            setViewTreeSavedStateRegistryOwner(this@WhisperLineService)
            // Ignore touches at the View level too (defense in depth on
            // top of FLAG_NOT_TOUCHABLE — some OEMs ignore the flag for
            // overlays attached via TYPE_APPLICATION_OVERLAY).
            isClickable = false
            isFocusable = false
            visibility = View.VISIBLE
            setContent {
                RibbonScaffold()
            }
        }

        runCatching { windowManager.addView(view, lp) }
            .onFailure { Log.w(tag, "addView failed: ${it.message}") }
        composeView = view
    }

    /**
     * Phase 3-E2 / Phase 3-E3 / Phase 3-E4 — the real Whisper Line ribbon.
     *
     * Visibility gate (Phase 3-E4): observes [ForegroundAppMonitor]
     * and renders nothing when the foreground app is on
     * [SensitiveAppDenylist]. Hides at the Composable layer rather
     * than touching ComposeView.visibility — the empty fallback also
     * lets the queue keep ticking through messages so the operator
     * doesn't see a dropped backlog when they return from the
     * banking app.
     *
     * When no proactive event is in flight, the ribbon shows
     * [WhisperLineDefaultMessages] (the quiet «phantom тут» fallback).
     * When events arrive, they cycle through the queue: each message
     * drifts for one full cycle + 2s breathing pause, then the queue
     * advances.
     */
    @Composable
    private fun RibbonScaffold() {
        val freq by WhisperTuning.freq.collectAsState()
        val cycleMs = freq.cycleMs
        val soundEnabled by WhisperTuning.soundEnabled.collectAsState()

        // Subscribe to the event bus exactly once and feed the queue.
        LaunchedEffect(queue) {
            WhisperEventBus.events.collect { event ->
                WhisperLineMessageMapper.mapEvent(event)?.let { queue.enqueue(it) }
            }
        }

        val current by queue.current.collectAsState()

        // «крихкий тон при появі нового рядка» — обіцянка перемикача «звук».
        // Тон лише на справжньому повідомленні: тиха стрічка «phantom тут»
        // тримає current == null і мовчить.
        val toneGen = remember {
            runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 40) }.getOrNull()
        }
        DisposableEffect(toneGen) {
            onDispose { runCatching { toneGen?.release() } }
        }
        LaunchedEffect(current) {
            if (current != null && soundEnabled) {
                runCatching { toneGen?.startTone(ToneGenerator.TONE_PROP_BEEP, 120) }
            }
        }

        // Стрічка в барвах v1.2: загроза — danger, решта — бурштин на 0.85.
        val ribbonColor = remember(current) {
            when {
                current?.contains("чую:") == true -> NightColors.danger
                else -> NightColors.accent.copy(alpha = 0.85f)
            }
        }

        // Phase 3-E4 — observe the foreground-app monitor; suppress
        // the ribbon when the operator is on a sensitive app
        // (banking, password manager, OTP, identity).
        val foregroundPackage by ForegroundAppMonitor.currentPackage.collectAsState()
        val sensitive = remember(foregroundPackage) {
            SensitiveAppDenylist.isSensitive(foregroundPackage)
        }

        // Advance the queue after the message has fully drifted past
        // (cycleMs) plus the prompt's required 2s breathing pause. The
        // LaunchedEffect re-keys on `current` so each new message gets a
        // fresh timer, and re-keys on `queue` so onDestroy.clear()
        // cancels the in-flight wait. Keeps ticking even when sensitive
        // so the queue drains rather than backing up while suppressed.
        LaunchedEffect(queue, current) {
            if (current != null) {
                delay((cycleMs + ADVANCE_PAUSE_MS).toLong())
                queue.advance()
            }
        }

        if (sensitive) return // Render-nothing branch — overlay invisible.

        val displayMessages = remember(current) {
            current?.let { listOf(it) } ?: WhisperLineDefaultMessages
        }

        WhisperLineRibbon(
            messages = displayMessages,
            cycleDurationMs = cycleMs,
            accentColor = ribbonColor,
            stripeHeight = 1.5.dp,
            // Спокій — це відсутність руху, а не рух за замовчуванням. Сервіс
            // живе весь час роботи телефона, тож дрейф «phantom тут» без жодної
            // події гнав кадри поверх кожного чужого застосунку назавжди.
            animate = current != null,
        )
    }

    companion object {
        /** Phase 3-E3 — pause between consecutive whisper messages. */
        const val ADVANCE_PAUSE_MS: Int = 2_000
    }
}
