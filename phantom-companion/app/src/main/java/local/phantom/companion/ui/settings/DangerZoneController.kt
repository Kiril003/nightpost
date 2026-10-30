package local.phantom.companion.ui.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * QH1 — pure state machine that gates destructive Settings actions
 * («Видалити все» et al.) behind a two-step confirmation with a 3-second
 * cooldown between the first and second confirm tap.
 *
 * Why a state machine instead of inline Composable state: the original
 * footer button fired delete on a single tap, so a stray finger swipe
 * wiped operator's downloaded models. A two-step confirm is the
 * obvious fix; the cooldown prevents muscle-memory double-taps from
 * blowing through both confirms in 200ms. Pulling this into a class
 * means the timing logic + state transitions are unit-testable
 * without Robolectric / Compose runtime.
 *
 * Phases:
 *   - [Phase.Idle]: button visible, nothing pending.
 *   - [Phase.FirstConfirm]: «так, видалити» dialog up; tapping confirm
 *     moves to [Phase.Cooldown].
 *   - [Phase.Cooldown]: «справді так?» dialog up but the second confirm
 *     button is disabled until [cooldownMs] has elapsed. After cooldown
 *     elapses, a tap on the second confirm fires the action and
 *     resets to [Phase.Idle].
 */
class DangerZoneController(
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    val cooldownMs: Long = 3_000L,
) {

    enum class Phase { Idle, FirstConfirm, Cooldown }

    // Потік, а не поле: доки фаза була приватним полем, екран не міг її
    // спостерігати й тримав власну копію цієї ж машини станів — тести
    // зеленіли на класі, якого на пристрої ніхто не викликав.
    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private var firstConfirmAt: Long = 0L

    fun currentPhase(): Phase = _phase.value

    /** Tap on the «Видалити все» trigger button — opens the first dialog. */
    fun openFirstConfirm() {
        _phase.value = Phase.FirstConfirm
    }

    /**
     * Tap «так, видалити» on the first dialog. Records the timestamp so
     * the second confirm can enforce the cooldown window.
     */
    fun acceptFirstConfirm() {
        check(_phase.value == Phase.FirstConfirm) {
            "acceptFirstConfirm called outside FirstConfirm phase (was ${_phase.value})"
        }
        firstConfirmAt = nowMs()
        _phase.value = Phase.Cooldown
    }

    /**
     * @return true if the second-confirm button is currently allowed
     * to fire. False during the [cooldownMs] window after
     * [acceptFirstConfirm] — UI shows a disabled button + countdown.
     */
    fun canCommit(): Boolean =
        _phase.value == Phase.Cooldown && (nowMs() - firstConfirmAt) >= cooldownMs

    /**
     * Whole seconds remaining until [canCommit] flips true. UI binds
     * this to the disabled button label («справді так? · 2», « · 1»,
     * «справді так?»).
     */
    fun remainingCooldownSeconds(): Int {
        if (_phase.value != Phase.Cooldown) return 0
        val elapsed = nowMs() - firstConfirmAt
        val remainingMs = (cooldownMs - elapsed).coerceAtLeast(0)
        return ((remainingMs + 999) / 1000).toInt()
    }

    /** Operator confirmed twice → caller fires the action; we reset. */
    fun commit(action: () -> Unit) {
        check(canCommit()) { "commit called before cooldown elapsed" }
        action()
        _phase.value = Phase.Idle
        firstConfirmAt = 0L
    }

    /** Dismiss either dialog (back press, scrim tap, «ні»). */
    fun cancel() {
        _phase.value = Phase.Idle
        firstConfirmAt = 0L
    }
}
