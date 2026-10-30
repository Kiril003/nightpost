package local.phantom.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.voice.ClassificationResult
import local.phantom.companion.feature.ambient.whisper.WhisperEventBus

/**
 * OMNIPRESENT VOICE — the process-lifetime host of the full-duplex channel.
 *
 * Lifecycle owned by AppContainer: when [VoicePreferences.liveDialogEnabled]
 * flips true (and the master mic mute is off), the AppContainer starts
 * this service. When it flips false, the service is stopped.
 *
 * ## What this service is now
 *
 * It used to *be* the pipeline: it opened the recorder, ran its own
 * [EnergyVad] over the amplitude flow, and restarted capture on every
 * SpeechEnd. That pipeline had a hole its own comments admitted — "TTS
 * ducking (don't transcribe own chime) — SV-3" — which in practice meant
 * the mic transcribed PHANTOM's own voice back into the conversation, and
 * nothing could interrupt him.
 *
 * That state machine now lives in
 * [local.phantom.companion.core.voice.duplex.DuplexVoiceEngine], at
 * application scope, with echo-aware barge-in. This service's remaining job
 * is the one thing only a Service can do: **hold the process alive and keep
 * the microphone legal while the app is backgrounded**, and show the
 * operator an honest foreground notification about it.
 *
 * The engine is owned by the AppContainer, not by this class, precisely so
 * the conversation survives this service being killed and restarted by the
 * OS — and so it keeps running while the operator is in the app but the
 * service is not needed at all.
 *
 * Master mic mute (MIC-2) is checked at start: a muted state refuses to
 * boot the service and stops it immediately if mute flips on while running.
 */
class LiveDialogService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var muteWatchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        local.phantom.companion.data.BootProbe.mark("service:LiveDialogService")
        // Підйом — ПЕРШИМ: дедлайн 5 с система рахує від startForegroundService,
        // а перший дотик до контейнера будує весь лінивий граф і його не встигає.
        // Відмова теж мусить бути ПІСЛЯ підйому: службу запустили як передню,
        // і зупинка без startForeground — те саме порушення, що й запізнення.
        StatusShade.attach(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )

        val container = (application as PhantomApp).container

        // Master mute hard-stop. Refusing here keeps the indicator off.
        if (container.voicePrefs.micMuted.value) {
            Log.i(TAG, "Live Dialog refused: mic master mute is on")
            stopSelf()
            return START_NOT_STICKY
        }

        // Open the duplex channel. Everything about turn-taking, echo and
        // interruption is the engine's problem, not this service's.
        container.duplexVoice.start()

        // Ambient awareness (Phase 2-B) — unrelated to turn-taking, still ours.
        scope.launch {
            container.voiceManager.classificationResults.collect { result ->
                if (result.label in THREAT_SOUNDS && result.score > 0.6f) {
                    triggerThreatAwareness(container, result)
                }
            }
        }

        // Master mute can flip while we're alive — auto-pause when it does.
        muteWatchJob = scope.launch {
            container.voicePrefs.micMuted.collectLatest { muted ->
                if (muted) {
                    Log.i(TAG, "Mic master mute flipped on — stopping Live Dialog")
                    stopSelf()
                }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        StatusShade.detach(this)
        runCatching {
            (application as PhantomApp).container.duplexVoice.stop()
        }
        muteWatchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private val THREAT_SOUNDS = setOf(
        "Siren", "Emergency vehicle", "Police car (siren)", "Ambulance (siren)",
        "Fire engine (siren)", "Civil defense siren", "Crying", "Baby cry", "Screaming"
    )

    private fun triggerThreatAwareness(container: local.phantom.companion.data.AppContainer, result: ClassificationResult) {
        val uaLabel = when {
            result.label.contains("Siren", ignoreCase = true) -> "сирена"
            result.label.contains("Emergency", ignoreCase = true) -> "спецавто"
            result.label.contains("Crying", ignoreCase = true) -> "плач"
            result.label.contains("Baby", ignoreCase = true) -> "плач дитини"
            result.label.contains("Screaming", ignoreCase = true) -> "крик"
            else -> result.label
        }

        // 1. Notify via WhisperLine ribbon
        scope.launch {
            WhisperEventBus.push(
                local.phantom.companion.feature.ambient.whisper.WhisperEvent(
                    kind = "ambient.threat",
                    text = "чую: $uaLabel"
                )
            )
        }

        // 2. Transition system state to SENTINEL (if supported via local override)
        // Note: For now we just log it as actual backend state change requires
        // a command via PhantomLink which is a Phase 3-E expansion.
        Log.i(TAG, "Threat detected: $uaLabel (${result.score}) — transitioning to SENTINEL")
    }

    companion object {
        private const val TAG = "LiveDialogService"
    }
}
