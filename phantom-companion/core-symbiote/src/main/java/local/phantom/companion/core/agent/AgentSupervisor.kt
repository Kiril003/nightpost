package local.phantom.companion.core.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolCallParser
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolExecutor
import local.phantom.companion.core.tool.ToolPromptInjector
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.diary.DecisionDiary
import local.phantom.companion.core.tool.diary.DecisionEntry
import local.phantom.companion.core.tool.permission.GateOutcome
import local.phantom.companion.core.tool.permission.SymbioteStop
import java.util.UUID

/**
 * Single-agent supervisor loop (Phase 2.A).
 *
 * Drives one [AgentSpec] to completion by interleaving model calls with tool
 * dispatches. The caller supplies all I/O dependencies as lambdas so this
 * class is pure-JVM testable — no Android Context leaks in.
 *
 * Concurrency / cancellation: the loop runs inside [coroutineScope]; callers
 * cancel via the enclosing scope. Phase 2.B will add an explicit cancellation
 * token and parallel sub-agent dispatch.
 *
 * @param cloudRouter          AI router for cloud inference (Gemini).
 * @param localRouter          AI router for on-device inference (Gemma).
 * @param toolRegistryLookup   Returns the [Tool] for a given ID, or null.
 * @param toolRegistryList     Returns all currently registered tools.
 * @param diary                Append-only audit log of every invocation.
 * @param toolGate             Permission gate; returns [GateOutcome].
 * @param toolContextFactory   Builds a [ToolContext] for a given tool + depth.
 * @param toolPromptInjector   Renders the `[TOOLS: …]` prompt block.
 * @param clock                Monotonic clock in epoch-millis (injectable for tests).
 * @param constitutionGate     Optional constitutional gate; when supplied the supervisor
 *                             checks the spec's manifest before running and releases it
 *                             afterwards. Defaults to `null` (off) for tests.
 * @param toolExecutor         Hardened execution shell (watchdog timeout + per-tool
 *                             circuit breaker + exception capture) every step's tool
 *                             call goes through.
 */
class AgentSupervisor(
    private val cloudRouter: AIRouter,
    private val localRouter: AIRouter,
    private val toolRegistryLookup: suspend (toolId: String) -> Tool?,
    private val toolRegistryList: suspend () -> List<Tool>,
    private val diary: DecisionDiary,
    private val toolGate: suspend (tool: Tool, argsPreview: String) -> GateOutcome,
    private val toolContextFactory: (tool: Tool, agentDepth: Int, requestId: String) -> ToolContext,
    private val toolPromptInjector: (List<Tool>) -> String = { tools -> ToolPromptInjector.render(tools) },
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val constitutionGate: AgentConstitutionGate? = null,
    private val toolExecutor: ToolExecutor = ToolExecutor(clock),
    private val isStopped: () -> Boolean = { SymbioteStop.stopped.value },
) {

    private class StoppedByKillSwitchException : CancellationException("stopped_by_kill_switch")

    private fun halted(): Boolean = isStopped() || SymbioteStop.stopped.value

    /**
     * Runs the agent described by [spec], forwarding per-step progress to [progressSink].
     *
     * Returns one of:
     * - [AgentResult.Done]      — AI emitted `{"type":"agent-done",...}`.
     * - [AgentResult.Failed]    — any protocol / permission / budget error.
     * - [AgentResult.Cancelled] — the enclosing coroutine was cancelled.
     *
     * Constitutional gate (Phase 2.B): if [constitutionGate] is set, the spec's
     * ephemeral manifest is registered before the loop starts and unregistered in
     * a `finally` block so cancel/error paths always release it.
     */
    suspend fun run(spec: AgentSpec, progressSink: AgentProgressSink): AgentResult {
        if (halted()) {
            return AgentResult.Failed(
                agentId = spec.agentId,
                stepsRun = 0,
                durationMs = 0L,
                reason = "stopped: Усе зупинено на телефоні",
            )
        }

        // ── Constitutional pre-check ─────────────────────────────────────────
        if (constitutionGate?.acquire(spec) == false) {
            return AgentResult.Failed(
                agentId = spec.agentId,
                stepsRun = 0,
                durationMs = 0L,
                reason = "constitution_rejected",
            )
        }

        val started = clock()
        var stepsRunSoFar = 0
        return try {
            coroutineScope {
                val child = async {
                    runLoop(spec, progressSink, started) { stepsRunSoFar = it }
                }
                val unregister = SymbioteStop.registerOnStop {
                    child.cancel(StoppedByKillSwitchException())
                }
                try {
                    child.await().also { stepsRunSoFar = it.stepsRun }
                } finally {
                    unregister()
                }
            }
        } catch (ce: CancellationException) {
            withContext(NonCancellable) {
                if (ce is StoppedByKillSwitchException || halted()) {
                    AgentResult.Failed(
                        agentId = spec.agentId,
                        stepsRun = stepsRunSoFar,
                        durationMs = clock() - started,
                        reason = "stopped: Усе зупинено на телефоні",
                    )
                } else {
                    AgentResult.Cancelled(
                        agentId = spec.agentId,
                        stepsRun = stepsRunSoFar,
                        durationMs = clock() - started,
                    )
                }
            }
        } finally {
            constitutionGate?.release(spec)
        }
    }

    // ── Core loop (extracted so the outer try/catch stays readable) ──────────

    private suspend fun runLoop(
        spec: AgentSpec,
        progressSink: AgentProgressSink,
        started: Long,
        onStepCount: (Int) -> Unit = {},
    ): AgentResult = coroutineScope {
            val router = if (spec.preferLocal) localRouter else cloudRouter

            // Build the tool-prompt block from only the tools this agent may use.
            val allowedToolInstances = toolRegistryList()
                .filter { it.id in spec.allowedTools }
            val toolPromptBlock = toolPromptInjector(allowedToolInstances)

            // Emit the initial "thinking" ping before the first model call.
            progressSink.onStep(AgentProgress(spec.agentId, 0, "thinking", "running"))

            val history = mutableListOf(
                AIIntent.Text.Turn(
                    role = AIIntent.Text.Role.SYSTEM,
                    content = AgentSystemPromptBuilder.build(spec, toolPromptBlock),
                ),
                AIIntent.Text.Turn(
                    role = AIIntent.Text.Role.USER,
                    content = spec.goal,
                ),
            )

            var step = 0
            // R6 chain-honesty: a prose reply mid-loop is not a protocol
            // crime. First offence gets a corrective system turn; a second
            // non-blank prose in a row IS the agent's answer — return it as
            // Done instead of killing the whole mission with
            // bad_response/unrecognized_type (operator: «завдання
            // послідовні не виконає»).
            var proseStrikes = 0
            var lastProse: String? = null
            while (step < spec.maxSteps && (clock() - started) < spec.budgetMs) {
                if (halted()) {
                    progressSink.onStep(
                        AgentProgress(spec.agentId, step, "stopped", "error", "Усе зупинено на телефоні"),
                    )
                    return@coroutineScope AgentResult.Failed(
                        agentId = spec.agentId,
                        stepsRun = step,
                        durationMs = clock() - started,
                        reason = "stopped: Усе зупинено на телефоні",
                        partialSummary = lastProse?.take(300),
                    )
                }
                step++
                onStepCount(step)

                // ── 1. Ask the model for the next move ──────────────────────
                val response = runCatching {
                    router.complete(
                        AIIntent.Text(
                            prompt = "",
                            history = history.toList(),
                            systemPrompt = null,
                            context = null,
                        )
                    ).accumulateFinalText()
                }.getOrElse { t ->
                    // CancellationException must propagate so the outer
                    // try/catch in run() can return AgentResult.Cancelled.
                    if (t is CancellationException) throw t
                    return@coroutineScope AgentResult.Failed(
                        agentId = spec.agentId,
                        stepsRun = step,
                        durationMs = clock() - started,
                        // A throwable with no message rendered as the literal
                        // "router_error: null" on the operator's agent card.
                        reason = "router_error: ${t.message ?: t::class.simpleName}",
                        partialSummary = null,
                    )
                }

                if (halted()) {
                    progressSink.onStep(
                        AgentProgress(spec.agentId, step, "stopped", "error", "Усе зупинено на телефоні"),
                    )
                    return@coroutineScope AgentResult.Failed(
                        agentId = spec.agentId,
                        stepsRun = step,
                        durationMs = clock() - started,
                        reason = "stopped: Усе зупинено на телефоні",
                        partialSummary = lastProse?.take(300),
                    )
                }

                history += AIIntent.Text.Turn(
                    role = AIIntent.Text.Role.ASSISTANT,
                    content = response,
                )

                // ── 2. Parse the top-level JSON object ──────────────────────
                val parsed = AgentJsonExtractor.extract(response)
                val responseType = (parsed?.get("type") as? JsonPrimitive)?.content

                if (parsed == null || (responseType != "tool-call" && responseType != "agent-done")) {
                    val prose = response.trim()
                    if (prose.isNotBlank()) lastProse = prose
                    proseStrikes++
                    val settledProse = lastProse
                    if (proseStrikes >= 2) {
                        if (settledProse.isNullOrBlank()) {
                            return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "bad_response",
                                partialSummary = response.take(300),
                            )
                        }
                        progressSink.onStep(
                            AgentProgress(spec.agentId, step, "done", "ok", settledProse.take(120))
                        )
                        return@coroutineScope AgentResult.Done(
                            agentId = spec.agentId,
                            stepsRun = step,
                            durationMs = clock() - started,
                            summary = settledProse.take(600),
                            artifacts = emptyList(),
                        )
                    }
                    history += AIIntent.Text.Turn(
                        role = AIIntent.Text.Role.SYSTEM,
                        content = "[ПРОТОКОЛ] Відповідай ЛИШЕ одним JSON-обʼєктом: " +
                            """{"type":"tool-call","tool":"<id>","args":{...}} або """ +
                            """{"type":"agent-done","summary":"<підсумок>"}. """ +
                            "Якщо попередня репліка і була твоєю фінальною відповіддю — " +
                            "переклади її у agent-done.summary.",
                    )
                    progressSink.onStep(
                        AgentProgress(spec.agentId, step, "thinking", "running", "нагадав протокол")
                    )
                    continue
                }

                // ── 3. Dispatch on the "type" discriminator ──────────────────
                when (responseType) {

                    "tool-call" -> {
                        proseStrikes = 0
                        val call = ToolCallParser.tryParse(parsed)
                            ?: return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "bad_tool_call",
                                partialSummary = response.take(300),
                            )

                        // Enforce allowedTools whitelist.
                        if (call.toolId !in spec.allowedTools) {
                            progressSink.onStep(
                                AgentProgress(spec.agentId, step, call.toolId, "skipped", "forbidden")
                            )
                            return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "tool_forbidden: ${call.toolId}",
                                partialSummary = null,
                            )
                        }

                        val tool = toolRegistryLookup(call.toolId)
                            ?: return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "tool_missing: ${call.toolId}",
                                partialSummary = null,
                            )

                        progressSink.onStep(
                            AgentProgress(spec.agentId, step, tool.id, "running", call.say)
                        )

                        // ── Permission gate ──────────────────────────────────
                        if (halted()) {
                            progressSink.onStep(
                                AgentProgress(spec.agentId, step, "stopped", "error", "Усе зупинено на телефоні"),
                            )
                            return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "stopped: Усе зупинено на телефоні",
                                partialSummary = lastProse?.take(300),
                            )
                        }
                        val argsPreview = call.args.toString()
                        val gate = toolGate(tool, argsPreview)
                        if (gate is GateOutcome.Denied) {
                            progressSink.onStep(
                                AgentProgress(spec.agentId, step, tool.id, "error", "DENIED: ${gate.reason}")
                            )
                            return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "permission_denied: ${gate.reason}",
                                partialSummary = null,
                            )
                        }
                        val approved = (gate as GateOutcome.Granted).approved
                        val ctx = toolContextFactory(tool, 1, spec.agentId)
                            .copy(approvedPermissions = approved)

                        // ── Execute (hardened: watchdog + breaker) ───────────
                        val result = toolExecutor.run(
                            tool = tool,
                            args = call.args,
                            context = ctx,
                            emit = { /* tool-trace Phase 2.C */ },
                        )

                        // ── Diary ────────────────────────────────────────────
                        diary.write(
                            DecisionEntry(
                                id = UUID.randomUUID().toString(),
                                timestamp = clock(),
                                toolId = tool.id,
                                args = call.args.toString().take(500),
                                outcome = result.outcomeCode(),
                                outputDigest = result.outputForAgent().take(200),
                                agentId = spec.agentId,
                                agentDepth = 1,
                            )
                        )

                        val resultStatus = if (result is ToolResult.Ok) "ok" else "error"
                        progressSink.onStep(
                            AgentProgress(
                                agentId = spec.agentId,
                                stepIndex = step,
                                action = tool.id,
                                status = resultStatus,
                                detail = result.outputForAgent().take(120),
                            )
                        )

                        if (halted()) {
                            progressSink.onStep(
                                AgentProgress(spec.agentId, step, "stopped", "error", "Усе зупинено на телефоні"),
                            )
                            return@coroutineScope AgentResult.Failed(
                                agentId = spec.agentId,
                                stepsRun = step,
                                durationMs = clock() - started,
                                reason = "stopped: Усе зупинено на телефоні",
                                partialSummary = lastProse?.take(300),
                            )
                        }

                        // Feed result back into history as untrusted USER data inside a
                        // delimited block — never as SYSTEM role (G11).
                        history += AIIntent.Text.Turn(
                            role = AIIntent.Text.Role.USER,
                            content = formatToolResultTurn(
                                toolId = tool.id,
                                ok = result is ToolResult.Ok,
                                output = result.outputForAgent().take(2000),
                            ),
                        )
                    }

                    "agent-done" -> {
                        val summary = (parsed["summary"] as? JsonPrimitive)?.content.orEmpty()
                        val artifacts = (parsed["artifacts"] as? JsonArray)?.toList().orEmpty()
                        progressSink.onStep(
                            AgentProgress(spec.agentId, step, "done", "ok", summary.take(120))
                        )
                        return@coroutineScope AgentResult.Done(
                            agentId = spec.agentId,
                            stepsRun = step,
                            durationMs = clock() - started,
                            summary = summary,
                            artifacts = artifacts,
                        )
                    }

                }
            }

            // Loop exited without agent-done. Surface whatever the model
            // said last so the caller has something honest to relay.
            AgentResult.Failed(
                agentId = spec.agentId,
                stepsRun = step,
                durationMs = clock() - started,
                reason = "budget_exhausted",
                partialSummary = lastProse?.take(300),
            )
        }
}

// ── Private helpers ──────────────────────────────────────────────────────────

/**
 * Accumulates [AIChunk.Token] fragments and returns [AIChunk.Final.text] if
 * present, otherwise the concatenated token text. Stops collecting after
 * [AIChunk.Final] arrives.
 */
private suspend fun Flow<AIChunk>.accumulateFinalText(): String {
    val sb = StringBuilder()
    var finalText: String? = null
    collect { chunk ->
        when (chunk) {
            is AIChunk.Token -> sb.append(chunk.text)
            is AIChunk.Final -> finalText = chunk.text
            else -> Unit
        }
    }
    return finalText ?: sb.toString()
}

/** Short outcome code for [DecisionEntry.outcome]. */
private fun ToolResult.outcomeCode(): String = when (this) {
    is ToolResult.Ok -> "ok"
    is ToolResult.Err -> "err:$code"
    is ToolResult.PermissionDenied -> "denied"
    is ToolResult.Cancelled -> "cancelled"
}

private val TOOL_RESULT_TAG = Regex("""<\s*/?\s*tool_result\b""", RegexOption.IGNORE_CASE)

internal fun formatToolResultTurn(toolId: String, ok: Boolean, output: String): String =
    "<tool_result id=\"$toolId\" ok=\"$ok\">\n" +
        "[TOOL_RESULT id=$toolId ok=$ok]\n" +
        output.replace(TOOL_RESULT_TAG, "<tool_result_") + "\n" +
        "</tool_result>"

/** Agent-facing plain-text rendering of any [ToolResult] variant. */
private fun ToolResult.outputForAgent(): String = when (this) {
    is ToolResult.Ok -> output
    is ToolResult.Err -> "ERROR: $code — $message"
    is ToolResult.PermissionDenied -> "DENIED: $permission"
    is ToolResult.Cancelled -> "CANCELLED"
}
