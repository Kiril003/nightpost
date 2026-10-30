package local.phantom.companion.feature.godmode.engine

import kotlinx.coroutines.delay

/**
 * Phase 3 W4-4 — runs an [AppAction] sequence on the device through
 * an injected [AccessibilityActionController]. The dispatcher itself
 * is pure-Kotlin (suspending) so the authorize-then-execute logic can
 * be unit-tested with a fake controller — no Robolectric, no
 * AccessibilityNodeInfo mocks beyond what the controller layer needs.
 *
 * Safety guarantees enforced here (independent of the controller):
 *
 *  • Hard cap of [AppAction.MAX_STEPS_PER_SEQUENCE] (8) actions per
 *    dispatch. Extra entries are dropped before any side effect.
 *  • [minStepIntervalMs] (200 ms) sleep between steps so the
 *    operator has time to flip Stream's «скасувати» if they decide
 *    the AI is going somewhere wrong.
 *  • Per-step [AppActionAuthorizer.authorize] check. The active
 *    package is re-read each step — a click that would have been
 *    fine when the sequence started can now land on a banking app
 *    if the operator switched windows mid-flight; we re-authorize
 *    so the suspend triggers in that case too.
 *  • Any [AccessibilityActionController.execute] returning `false`
 *    fails the sequence — the operator gets a Stream Notice rather
 *    than a half-executed chain.
 *
 * Progress is reported via [emitProgress] callbacks rather than a
 * SharedFlow inside the dispatcher so consumers can pick their own
 * delivery mechanism (the production wiring routes progress through
 * [AppActionTraceBus]).
 */
class AccessibilityActionDispatcher(
    private val authorizer: AppActionAuthorizer,
    private val controller: AccessibilityActionController,
    /** Active foreground package — re-read every step. */
    private val currentPackage: () -> String,
    /** Side-channel for progress events (started / step / suspended / failed / completed). */
    private val emitProgress: (AppActionProgress) -> Unit,
    private val minStepIntervalMs: Long = MIN_STEP_INTERVAL_MS,
    /**
     * R5.1 — timestamp (ms) of the last PHYSICAL touch the operator made
     * on the screen, as seen by the AccessibilityService's
     * TYPE_TOUCH_INTERACTION_START. Programmatic performAction does NOT
     * fire touch-interaction events, so a value newer than the current
     * step's start means the human reached in. Default returns 0 → the
     * check is inert for callers that don't wire it (tests, legacy path).
     */
    private val lastOperatorTouchAtMs: () -> Long = { 0L },
) {

    /**
     * Dispatch [actions] in order. Suspends across the inter-step
     * delay so a coroutine cancellation interrupts cleanly — the
     * operator's «скасувати» pill cancels the launching scope.
     *
     * Legacy widget path — delegates to [dispatchDetailed] so both the
     * AppActionTraceBus events and the structured outcome share one
     * implementation.
     */
    suspend fun dispatch(actions: List<AppAction>) {
        dispatchDetailed(actions)
    }

    /**
     * R5.1 — same dispatch, but returns a [SequenceOutcome] describing
     * exactly how far it got and why it stopped, and calls [onStep]
     * before each landed step so an awaiting caller (the `accessibility`
     * tool) can stream honest progress. Still emits the AppActionProgress
     * events for the widget UI.
     */
    suspend fun dispatchDetailed(
        actions: List<AppAction>,
        onStep: suspend (index: Int, total: Int, description: String) -> Unit = { _, _, _ -> },
    ): SequenceOutcome {
        if (actions.isEmpty()) return SequenceOutcome(0, 0, null)
        val capped = actions.take(AppAction.MAX_STEPS_PER_SEQUENCE)
        emitProgress(AppActionProgress.Started(total = capped.size))

        // Baseline: any touch strictly after this instant is the operator
        // reaching in mid-flight (our own performAction never trips it).
        val startedAtMs = System.currentTimeMillis()
        var completed = 0

        for ((idx0, action) in capped.withIndex()) {
            val stepIndex = idx0 + 1
            val pkg = currentPackage()

            // Operator-intervention guard — check BEFORE acting so we never
            // fight the human's hands. A touch newer than the sequence start
            // means they took over.
            if (lastOperatorTouchAtMs() > startedAtMs) {
                emitProgress(
                    AppActionProgress.Suspended(
                        reasonKey = REASON_OPERATOR_INTERVENED,
                        displayText = "оператор втрутився · зупиняюсь",
                        atStep = stepIndex,
                        totalSteps = capped.size,
                    ),
                )
                return SequenceOutcome(
                    completed, capped.size,
                    Stop(REASON_OPERATOR_INTERVENED, "оператор торкнувся екрана"),
                )
            }

            when (val auth = authorizer.authorize(action, pkg)) {
                is AuthorizationResult.Denied -> {
                    emitProgress(
                        AppActionProgress.Suspended(
                            reasonKey = auth.reasonKey,
                            displayText = auth.displayText,
                            atStep = stepIndex,
                            totalSteps = capped.size,
                        ),
                    )
                    return SequenceOutcome(
                        completed, capped.size,
                        Stop(auth.reasonKey, auth.displayText),
                    )
                }
                is AuthorizationResult.Allowed -> Unit
            }

            val description = describe(action)
            emitProgress(
                AppActionProgress.Step(
                    index = stepIndex,
                    total = capped.size,
                    description = description,
                ),
            )
            onStep(stepIndex, capped.size, description)

            // MAP-AI-3 — map verbs (NavigateTo / SearchPlace / ShowOnMap /
            // SaveOfflineRegion) bypass the accessibility controller and
            // route through MapActionRouter so they reach the in-chat
            // MapWidget / OfflineRegionsManager instead of trying to
            // tap a non-existent map node.
            val ok = if (MapActionRouter.isMapAction(action)) {
                runCatching { MapActionRouter.emit(action) }.getOrElse { false }
            } else {
                runCatching { controller.execute(action) }.getOrElse { false }
            }
            if (!ok) {
                emitProgress(
                    AppActionProgress.Failed(
                        action = action,
                        atStep = stepIndex,
                        totalSteps = capped.size,
                    ),
                )
                return SequenceOutcome(
                    completed, capped.size,
                    Stop(REASON_STEP_FAILED, describe(action)),
                )
            }
            completed++

            // Inter-step pacing — last step skips the wait so the
            // Completed event lands without an extra 200 ms tail.
            // After OpenApp we wait longer so the launched app's
            // accessibility tree has time to populate before the
            // next step queries it.
            if (stepIndex < capped.size) {
                val stepDelay = if (action is AppAction.OpenApp) OPEN_APP_SETTLE_MS
                else minStepIntervalMs
                delay(stepDelay)
            }
        }

        emitProgress(AppActionProgress.Completed(total = capped.size))
        return SequenceOutcome(completed, capped.size, null)
    }

    /** Structured result of one awaited [dispatchDetailed] run. */
    data class SequenceOutcome(
        val completedSteps: Int,
        val totalSteps: Int,
        val stop: Stop?,
    )

    /** Why a sequence stopped early. [reasonKey] is grep-able / stable. */
    data class Stop(val reasonKey: String, val detail: String)

    companion object {
        const val MIN_STEP_INTERVAL_MS: Long = 200L
        /** Sequence stopped because the operator physically touched the screen. */
        const val REASON_OPERATOR_INTERVENED: String = "operator-intervened"
        /** A step's performAction / node-lookup returned false. */
        const val REASON_STEP_FAILED: String = "step-failed"
        /** Wait after `open_app` so the freshly-launched activity's
         *  AccessibilityNodeInfo tree has time to populate before the
         *  next click / type lands. 1.5 s is enough for almost every
         *  app on modern hardware; longer than that and the chain
         *  feels broken anyway. */
        const val OPEN_APP_SETTLE_MS: Long = 1_500L

        /**
         * Human-readable Ukrainian per-step description for ToolTrace
         * rendering. The strings are verbatim per the W4-4 acceptance
         * («[крок 1/4] натискаю «Налаштування»») — keep them tight so
         * the chip reads cleanly on a phone.
         */
        fun describe(action: AppAction): String = when (action) {
            is AppAction.Click -> {
                val label = action.target.text
                    ?: action.target.resourceId
                    ?: action.target.bounds?.let { b -> "[${b.x},${b.y}]" }
                    ?: "ціль"
                "натискаю «$label»"
            }
            is AppAction.Scroll -> {
                val dir = when (action.direction) {
                    ScrollDirection.UP -> "вгору"
                    ScrollDirection.DOWN -> "вниз"
                    ScrollDirection.LEFT -> "вліво"
                    ScrollDirection.RIGHT -> "вправо"
                }
                "прокручую $dir"
            }
            is AppAction.Type -> "ввожу «${action.value.take(40)}»"
            AppAction.Back -> "повертаюсь назад"
            AppAction.Home -> "на головний екран"
            AppAction.Recents -> "відкриваю недавні"
            AppAction.Notifications -> "розгортаю шторку"
            AppAction.Screenshot -> "знімаю скріншот"
            is AppAction.OpenApp -> "відкриваю «${action.query}»"
            is AppAction.Swipe -> {
                val dx = action.endX - action.startX
                val dy = action.endY - action.startY
                val dir = when {
                    kotlin.math.abs(dx) >= kotlin.math.abs(dy) && dx > 0 -> "вправо"
                    kotlin.math.abs(dx) >= kotlin.math.abs(dy) -> "вліво"
                    dy > 0 -> "вниз"
                    else -> "вгору"
                }
                "свайп $dir"
            }
            is AppAction.Tap -> "тап @${action.x},${action.y}"
            is AppAction.LongPress -> "утримую @${action.x},${action.y}"
            // MAP-AI-3 — map verbs surface in ToolTrace as short
            // operator-readable cues. The receiver (MapWidget /
            // OfflineRegionsManager) provides the actual UI feedback.
            is AppAction.NavigateTo -> {
                val pretty = action.label
                    ?: "%.4f,%.4f".format(action.latitude, action.longitude)
                "прокладаю маршрут до «$pretty»"
            }
            is AppAction.SearchPlace -> "шукаю «${action.query.take(40)}»"
            is AppAction.ShowOnMap -> "показую на мапі"
            is AppAction.SaveOfflineRegion -> {
                val pretty = action.name ?: "поточний регіон"
                "зберігаю мапу: $pretty"
            }
        }
    }
}

/**
 * Side of the dispatcher that touches Android — the AccessibilityService
 * implements this with its `rootInActiveWindow` + `performAction`
 * APIs. Tests provide a fake that records calls and returns
 * deterministic success / failure.
 */
interface AccessibilityActionController {
    /** @return true when the action was performed (node found, action accepted). */
    fun execute(action: AppAction): Boolean
}

/** Progress events emitted during a dispatch — consumed by the AppActionWidget. */
sealed class AppActionProgress {
    data class Started(val total: Int) : AppActionProgress()
    data class Step(val index: Int, val total: Int, val description: String) : AppActionProgress()
    data class Suspended(
        val reasonKey: String,
        val displayText: String,
        val atStep: Int,
        val totalSteps: Int,
    ) : AppActionProgress()
    data class Failed(val action: AppAction, val atStep: Int, val totalSteps: Int) : AppActionProgress()
    data class Completed(val total: Int) : AppActionProgress()
}
