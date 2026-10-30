package local.phantom.companion.feature.stream.reflection

/**
 * P4-3-8 (V1 — signal-light) — decides when PHANTOM should drop an
 * unsolicited markdown reflection into the diary.
 *
 * V1 has zero dependencies on biosignals / FSM. It looks only at:
 *  - gap since the last operator submit (return-after-absence trigger),
 *  - cumulative submits since the last reflection (milestone trigger),
 *  - cooldown so we never spam more than 1 reflection per 2 hours.
 *
 * Future steps (P4-3-8 V2) will fold in stress spikes, state
 * transitions, and new-place visits once those flows are wired into
 * the VM.
 */
object ReflectionTrigger {

    const val ABSENCE_GAP_MS: Long = 4L * 60L * 60L * 1000L      // 4h
    const val MILESTONE_TURNS: Int = 25
    const val MIN_GAP_BETWEEN_REFLECTIONS_MS: Long = 2L * 60L * 60L * 1000L

    sealed interface Decision {
        data class Emit(val reason: Reason) : Decision
        data object Skip : Decision
    }

    enum class Reason { RETURN_AFTER_ABSENCE, TURN_MILESTONE }

    fun evaluate(
        nowMs: Long,
        lastUserSubmitMs: Long?,
        turnsSinceLastReflection: Int,
        lastReflectionMs: Long?,
    ): Decision {
        if (lastReflectionMs != null &&
            (nowMs - lastReflectionMs) < MIN_GAP_BETWEEN_REFLECTIONS_MS
        ) return Decision.Skip

        if (lastUserSubmitMs != null && (nowMs - lastUserSubmitMs) >= ABSENCE_GAP_MS) {
            return Decision.Emit(Reason.RETURN_AFTER_ABSENCE)
        }
        if (turnsSinceLastReflection >= MILESTONE_TURNS) {
            return Decision.Emit(Reason.TURN_MILESTONE)
        }
        return Decision.Skip
    }

    fun reasonPromptUk(reason: Reason): String = when (reason) {
        Reason.RETURN_AFTER_ABSENCE ->
            "Оператор повертається після тривалої відсутності. Скажи одне-два спокійних речення «що ти помітив» за час паузи: загальні розпорядки, погода, що в чаті незакрите. Без переліку — просто коротка присутність."
        Reason.TURN_MILESTONE ->
            "Останні 25 повідомлень підказують напрямок думок оператора. Зроби одне-два речення-резюме: на чому він зосереджений останнім часом, що відкрите. Тон — спокійний, як співрозмовник."
    }
}
