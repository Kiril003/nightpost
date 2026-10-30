package local.phantom.companion.core.tool

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.tool.permission.SymbioteStop
import java.util.concurrent.ConcurrentHashMap

/**
 * Hardened execution shell around [Tool.execute] — the single place both
 * dispatch paths (StreamViewModel.runToolCall and AgentSupervisor's step
 * loop) go through, so every one of the registered tools gets the same
 * three defenses without implementing any of them itself:
 *
 *  1. **Watchdog timeout.** A hung tool (network stall, dead
 *     AccessibilityService, wedged shell) previously froze the whole
 *     chain forever — no `AIChunk.Final`, no error, the operator stared
 *     at a breathing orb. Budget: [SLOW_TIMEOUT_MS] for `streaming` tools
 *     (deep research, shell, device hands legitimately run minutes),
 *     [DEFAULT_TIMEOUT_MS] for everything else.
 *
 *  2. **Per-tool circuit breaker.** [TRIP_THRESHOLD] consecutive failures
 *     open the circuit for [COOLDOWN_MS]: further calls short-circuit to
 *     an honest [ToolResult.Err] (code [CODE_CIRCUIT_OPEN]) instead of
 *     re-burning a chain step + tokens on an endpoint that is down. After
 *     the cooldown one probe call is allowed through — success closes the
 *     circuit, failure re-opens it. [ToolResult.PermissionDenied] and
 *     [ToolResult.Cancelled] never count as failures: a denied gate or an
 *     operator cancel says nothing about the tool's health.
 *
 *  3. **Exception capture.** Any [Throwable] out of a tool becomes a
 *     typed [ToolResult.Err] — raw stack traces never reach the stream.
 *     [CancellationException] from the OUTER scope still propagates
 *     (structured concurrency stays intact); only our own watchdog's
 *     [TimeoutCancellationException] is converted.
 *
 * Err messages are operator-language (Ukrainian) because they travel two
 * ways: back into the model turn (which explains the failure in prose)
 * and into the tool-trace card's status line.
 *
 * One instance per host (ViewModel / supervisor) — breaker state is
 * scoped to that host's lifetime. [clock] is injectable for tests.
 */
class ToolExecutor(
    private val clock: () -> Long = System::currentTimeMillis,
    private val defaultTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val slowTimeoutMs: Long = SLOW_TIMEOUT_MS,
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 60_000L
        const val SLOW_TIMEOUT_MS = 300_000L
        const val TRIP_THRESHOLD = 3
        const val COOLDOWN_MS = 45_000L

        const val CODE_TIMEOUT = "timeout"
        const val CODE_CIRCUIT_OPEN = "circuit_open"
        const val CODE_EXCEPTION = "exception"
        const val CODE_BAD_ARGS = "bad_args"
    }

    private class Breaker {
        var consecutiveFailures = 0
        var openedAtMs = 0L
    }

    private val breakers = ConcurrentHashMap<String, Breaker>()

    /** True while the tool's circuit is open (cooldown not yet elapsed). */
    fun isOpen(toolId: String): Boolean {
        val b = breakers[toolId] ?: return false
        return synchronized(b) {
            b.consecutiveFailures >= TRIP_THRESHOLD &&
                clock() - b.openedAtMs < COOLDOWN_MS
        }
    }

    private class StoppedByKillSwitchException : CancellationException("stopped_by_kill_switch")

    suspend fun run(
        tool: Tool,
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        if (SymbioteStop.stopped.value) {
            return ToolResult.Cancelled(durationMs = 0L)
        }
        val breaker = breakers.getOrPut(tool.id) { Breaker() }
        if (isOpen(tool.id)) {
            return ToolResult.Err(
                code = CODE_CIRCUIT_OPEN,
                message = "інструмент «${tool.id}» тимчасово вимкнено після " +
                    "$TRIP_THRESHOLD збоїв поспіль — повтор через " +
                    "${COOLDOWN_MS / 1000}с, спробуй інший шлях",
                durationMs = 0L,
            )
        }

        // The schema was prompt decoration until now: a call missing a required
        // field ran anyway, the tool substituted a default, and the operator got
        // a confident answer computed from arguments the model never sent. Told
        // what is wrong, the model fixes the call on the next turn; told nothing,
        // it repeats it. Not a circuit-breaker failure — the tool never ran.
        val violations = ToolArgsValidator.validate(tool.argsSchema, args)
        if (violations.isNotEmpty()) {
            return ToolResult.Err(
                code = CODE_BAD_ARGS,
                message = "«${tool.id}» не запускався: ${violations.joinToString("; ")}. " +
                    "Наступний крок — повтори виклик з виправленими аргументами.",
                durationMs = 0L,
            )
        }

        val budgetMs = if (tool.streaming) slowTimeoutMs else defaultTimeoutMs
        val started = clock()
        val result = try {
            coroutineScope {
                val child = async {
                    withTimeout(budgetMs) { tool.execute(args, context, emit) }
                }
                val unregister = SymbioteStop.registerOnStop {
                    child.cancel(StoppedByKillSwitchException())
                }
                if (SymbioteStop.stopped.value) {
                    child.cancel(StoppedByKillSwitchException())
                }
                try {
                    child.await()
                } finally {
                    unregister()
                }
            }
        } catch (t: TimeoutCancellationException) {
            ToolResult.Err(
                code = CODE_TIMEOUT,
                message = "інструмент «${tool.id}» не відповів за ${budgetMs / 1000}с — перервано. " +
                    "Що встигло зробитись, невідомо: не кажи оператору ні «зроблено», ні «не зроблено». " +
                    "Наступний крок — перевір стан іншим інструментом, перш ніж повторювати.",
                durationMs = clock() - started,
            )
        } catch (s: StoppedByKillSwitchException) {
            ToolResult.Cancelled(durationMs = clock() - started)
        } catch (c: CancellationException) {
            if (SymbioteStop.stopped.value) {
                ToolResult.Cancelled(durationMs = clock() - started)
            } else {
                throw c
            }
        } catch (t: Throwable) {
            ToolResult.Err(
                code = CODE_EXCEPTION,
                message = t.message ?: "несподівана помилка «${tool.id}»",
                durationMs = clock() - started,
            )
        }

        synchronized(breaker) {
            when (result) {
                is ToolResult.Ok -> breaker.consecutiveFailures = 0
                is ToolResult.Err -> {
                    breaker.consecutiveFailures++
                    if (breaker.consecutiveFailures >= TRIP_THRESHOLD) {
                        breaker.openedAtMs = clock()
                    }
                }
                // Health-neutral outcomes.
                is ToolResult.PermissionDenied, is ToolResult.Cancelled -> Unit
            }
        }
        return result
    }
}
