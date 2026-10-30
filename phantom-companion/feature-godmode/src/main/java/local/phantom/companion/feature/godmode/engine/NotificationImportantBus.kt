package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Phase 3 W4-5 — process-wide bus that carries Important
 * notifications from `NotificationReceiverService` to the Stream UI
 * for immediate Notice surfacing. Routine notifications take a
 * different path — they queue into [NotificationDigestEntity] for
 * the daily 9 am digest. Spam is logged-only, never on the bus.
 *
 * The payload is the already-redacted form so the operator-visible
 * Notice in Stream never carries banking codes / OTP / 2FA tokens
 * even when the source app neglected to mark its channel sensitive.
 * That covers the title too: a replay buffer keeps the last pushes
 * alive for the whole process, so a raw one outlives the notification
 * it came from. The life log already names its sender the same way.
 *
 * Mirrors the [GodModeEventBus] / [ScreenContextStore] / [AppActionTraceBus]
 * pattern: the `NotificationListenerService` is instantiated by
 * Android, not under our DI control, so the bus has to be a
 * process-global. Replay buffer of 8 keeps the most recent pushes
 * around long enough for the StreamViewModel collector that
 * subscribes after a fresh app launch (cold-start race window).
 */
data class NotificationImportant(
    val packageName: String,
    val redactedTitle: String,
    val redactedText: String,
    val deliveredAtMs: Long,
)

object NotificationImportantBus {
    private val _events = MutableSharedFlow<NotificationImportant>(
        replay = REPLAY_BUFFER,
        extraBufferCapacity = EXTRA_BUFFER,
    )

    val events: SharedFlow<NotificationImportant> = _events.asSharedFlow()

    fun push(event: NotificationImportant) {
        _events.tryEmit(event)
    }

    /** Test-only — drop replay so a fresh test starts from a clean slate. */
    internal fun resetForTest() {
        _events.resetReplayCache()
    }

    private const val REPLAY_BUFFER: Int = 8
    private const val EXTRA_BUFFER: Int = 32
}
