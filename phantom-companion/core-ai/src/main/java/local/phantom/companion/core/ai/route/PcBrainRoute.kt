package local.phantom.companion.core.ai.route

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route

/**
 * Розум спареного ПК: `POST /api/v1/chat/message` з токеном пристрою.
 * Відповідь приходить цілою, тож і віддається цілою — без удаваного потоку.
 *
 * Інструментів телефона ПК не має: хід, що чекає на них, він віддає тому,
 * хто вміє ними керувати ([yieldToolTurns]).
 */
class PcBrainRoute(
    private val reachable: suspend () -> Boolean,
    private val ask: suspend (content: String, sessionId: String?) -> Reply,
    private val yieldToolTurns: suspend () -> Boolean = { false },
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) : AIRouter {

    data class Reply(val status: Int, val body: String)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Volatile private var sessionId: String? = null

    override suspend fun availableRoutes(): Set<Route> =
        if (runCatching { reachable() }.getOrDefault(false)) setOf(Route.PC_NODE) else emptySet()

    override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
        if (intent !is AIIntent.Text) return@flow
        if (intent.history.lastOrNull()?.role == AIIntent.Text.Role.TOOL) return@flow
        if (intent.tools.isNotEmpty() && yieldToolTurns()) return@flow
        if (intent.attachments.isNotEmpty()) {
            emit(fail(415, "attachments are not accepted by the PC chat"))
            return@flow
        }

        val started = nowMs()
        val reply = try {
            withTimeout(timeoutMs) {
                ask(intent.prompt, sessionId)
            }
        } catch (t: TimeoutCancellationException) {
            emit(fail(504, "pc timeout"))
            return@flow
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            emit(fail(0, t.message ?: t::class.java.simpleName))
            return@flow
        }

        if (reply.status != 200) {
            emit(fail(reply.status, detailOf(reply.body)))
            return@flow
        }
        val root = parse(reply.body)
        val text = (root?.get("message") as? JsonObject)?.string("content")?.trim().orEmpty()
        if (text.isEmpty()) {
            emit(fail(reply.status, "empty answer"))
            return@flow
        }
        root?.string("session_id")?.takeIf { it.isNotBlank() }?.let { sessionId = it }

        emit(AIChunk.Token(text = text, route = Route.PC_NODE, isFinal = true))
        emit(AIChunk.Final(text = text, tokensUsed = 0, route = Route.PC_NODE, durationMs = nowMs() - started))
    }

    private fun fail(code: Int, detail: String) =
        AIChunk.ErrorChunk(cause = AIError.PcUnavailable(code, detail.take(200)), route = Route.PC_NODE)

    private fun parse(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()

    private fun detailOf(body: String): String = parse(body)?.string("detail") ?: body

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
    }
}
