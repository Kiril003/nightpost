package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * Executes a sequence of accessibility actions parsed from an `actions`
 * JSON array — and reports the TRUTH about what happened.
 *
 * R5.1 rewrite. The old wiring fire-and-forgot each action into its own
 * concurrently-launched dispatch job and unconditionally returned
 * `true`, so the model heard «8 succeeded» while the phone did nothing
 * (Accessibility off, action denied, node not found — all invisible).
 * Now the WHOLE sequence goes through one awaited dispatch and the
 * result carries per-step reality:
 *
 *  - all steps landed          → Ok
 *  - Accessibility unbound     → Err(accessibility_off) telling the model
 *                                to ask the operator to enable the service
 *  - authorizer denial         → Err(denied) with the reason
 *  - operator touched the phone→ Err(operator_intervened) — stop and ask
 *  - a step failed mid-flight  → Err(step_failed) — read the screen and replan
 *
 * To avoid a circular dependency between `:core-symbiote` and
 * `:feature-godmode`, parsing and dispatch are injected as lambdas;
 * AppContainer binds the real implementations.
 *
 * @param parseActions Converts a JsonArray into a list of AppAction
 *   (typed as Any to avoid the cross-module dependency).
 * @param dispatchSequence Runs the full sequence once, streaming
 *   per-step progress through the callback, and returns the honest
 *   [AccessibilitySequenceOutcome].
 */
class AccessibilityTool(
    private val parseActions: (JsonArray) -> List<Any>,
    private val dispatchSequence: suspend (
        actions: List<Any>,
        onStep: suspend (index: Int, total: Int, description: String) -> Unit,
    ) -> AccessibilitySequenceOutcome,
) : Tool {

    override val id = "accessibility"

    override val description =
        "Execute a sequence of UI accessibility actions (click, scroll, type, back, home, " +
            "open_app, screenshot…). Capped at $MAX_ACTIONS steps per call — chain calls for " +
            "longer flows; the result says how many steps were not parsed or not sent, so never " +
            "assume the rest ran. It reports what was dispatched, NOT what the screen became — " +
            "call screen.read to verify before the next step. Stops honestly on failure or when " +
            "the operator touches the phone."

    override val argsSchema: JsonObject = buildJsonObject {
        put("actions", kotlinx.serialization.json.JsonPrimitive("array"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.ACCESSIBILITY)

    override val risk: ToolRisk = ToolRisk.DEVICE

    override val streaming: Boolean = true

    override val manifest = FeatureManifest(
        id = "tool.accessibility",
        tier = 1,
        displayName = "Доступність",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val startMs = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - startMs }

        if (ToolPermission.ACCESSIBILITY !in context.approvedPermissions) {
            return ToolResult.PermissionDenied(ToolPermission.ACCESSIBILITY, elapsed())
        }

        val actionsElement = args["actions"]
            ?: return ToolResult.Err(
                "bad_args",
                "нема поля actions — передай масив дій, напр. actions=[{\"action\":\"back\"}].",
                0L,
            )

        val actionsArray = actionsElement as? JsonArray
            ?: return ToolResult.Err(
                "bad_args",
                "actions має бути JSON-масивом, а не одиничним значенням — загорни дію в [ ].",
                0L,
            )

        if (actionsArray.isEmpty()) {
            return ToolResult.Err(
                "bad_args",
                "масив actions порожній — нічого виконувати. Спершу screen.read, потім склади кроки.",
                0L,
            )
        }

        val requested = actionsArray.size
        val understood: List<Any> = parseActions(actionsArray)
        if (understood.isEmpty()) {
            return ToolResult.Err(
                "bad_args",
                "жодної дії з $requested не розібрав — жодна НЕ виконана. Перевір назви дій " +
                    "(click / scroll / type / back / home / open_app / screenshot) і поля кожної.",
                elapsed(),
            )
        }
        // Both silent drops the old code had: an action the parser did not
        // recognise, and everything past the cap. Either one let the model
        // believe it had done work nobody did.
        val unparsed = requested - understood.size
        val parsed: List<Any> = understood.take(MAX_ACTIONS)
        val deferred = understood.size - parsed.size

        emit(ToolEvent.Notice("виконую ${parsed.size} дій(ю) на екрані"))

        val outcome = runCatching {
            dispatchSequence(parsed) { index, total, description ->
                emit(
                    ToolEvent.Progress(
                        ratio = index.toFloat() / total,
                        label = "крок $index/$total · $description",
                    )
                )
            }
        }.getOrElse { t ->
            return ToolResult.Err(
                "exception",
                "диспетчер дій упав (${t.message ?: "без повідомлення"}) — скільки кроків встигло " +
                    "лягти, невідомо. Виклич screen.read і подивись реальний стан екрана, перш ніж діяти далі.",
                elapsed(),
            )
        }

        emit(ToolEvent.Progress(1f, "готово"))

        // Whatever was left unsent is the model's next call, not a rounding error.
        val leftover = buildString {
            if (unparsed > 0) append(" $unparsed дій(ї) не розібрано — перевір їх формат і надішли ще раз.")
            if (deferred > 0) {
                append(" Ще $deferred дій(ї) не надіслано (ліміт $MAX_ACTIONS за виклик) — ")
                append("спершу screen.read, потім наступний виклик.")
            }
        }

        val structured = buildJsonObject {
            put("dispatched", outcome.totalSteps)
            put("succeeded", outcome.completedSteps)
            put("requested", requested)
            put("unparsed", unparsed)
            put("deferred", deferred)
            // The dispatcher reports what it sent, not what the screen became.
            put("screen_verified", false)
            outcome.failure?.let {
                put("failure", it.kind)
                put("failure_detail", it.detail)
            }
        }

        val failure = outcome.failure
            ?: return ToolResult.Ok(
                output = "Виконано всі ${outcome.totalSteps} крок(и).$leftover " +
                    "Результат на екрані не перевірено — виклич screen.read.",
                structured = structured,
                durationMs = elapsed(),
            )

        val progress = "виконано ${outcome.completedSteps}/${outcome.totalSteps}"
        return when (failure.kind) {
            AccessibilitySequenceOutcome.KIND_SERVICE_UNBOUND -> ToolResult.Err(
                "accessibility_off",
                "сервіс доступності Nightpost вимкнено — жодна дія не виконана. " +
                    "Скажи оператору увімкнути: Налаштування → Спеціальні можливості → Nightpost, " +
                    "потім повтори.",
                elapsed(),
            )
            AccessibilitySequenceOutcome.KIND_OPERATOR_INTERVENED -> ToolResult.Err(
                "operator_intervened",
                "оператор сам торкнувся телефона ($progress). НЕ продовжуй — " +
                    "запитай оператора, чи виконувати далі.",
                elapsed(),
            )
            AccessibilitySequenceOutcome.KIND_STEP_FAILED -> ToolResult.Err(
                "step_failed",
                "$progress; далі не вдалося: ${failure.detail}. " +
                    "Виклич screen.read, подивись реальний стан екрана і сплануй інакше.",
                elapsed(),
            )
            else -> ToolResult.Err(
                "denied",
                "$progress; зупинено: ${failure.detail}. Це заборона, а не збій — " +
                    "поясни оператору причину і, якщо треба, попроси його зняти обмеження " +
                    "(розблокувати сховище чи дозволити цей застосунок).",
                elapsed(),
            )
        }
    }

    companion object {
        /** Hard cap matching AppAction.MAX_STEPS_PER_SEQUENCE. */
        const val MAX_ACTIONS = 8
    }
}

/**
 * Neutral (godmode-agnostic) result of one awaited accessibility
 * sequence. [completedSteps] counts steps that actually landed;
 * [failure] is null when the whole sequence completed.
 */
data class AccessibilitySequenceOutcome(
    val completedSteps: Int,
    val totalSteps: Int,
    val failure: Failure? = null,
) {
    data class Failure(val kind: String, val detail: String)

    companion object {
        const val KIND_SERVICE_UNBOUND = "service-unbound"
        const val KIND_OPERATOR_INTERVENED = "operator-intervened"
        const val KIND_STEP_FAILED = "step-failed"
        /** Authorizer denials pass their reasonKey (vault-locked / sensitive-app). */
    }
}
