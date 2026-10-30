package local.phantom.companion.core.ai.route

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route

/**
 * Одна запасна спроба на збій хмари: 5xx або мовчання довше за [firstTryMs] — одне
 * питання до [fallback] (легша модель), і далі чесна помилка. 429 не повторюється:
 * повтор палить ту саму квоту, а людина чекає хвилини замість «Ще раз».
 * Після першого слова повтору немає: він дублював би сказане.
 */
class RetryingAIRouter(
    private val delegate: AIRouter,
    private val fallback: AIRouter = delegate,
    private val firstTryMs: Long = FIRST_TRY_MS,
    private val fallbackMs: Long = FALLBACK_MS,
    // Справжній годинник: під runTest віртуальний час перескакує, поки мережевий фейк відповідає з іншого потоку.
    private val clock: CoroutineContext = Dispatchers.Default,
    private val fallbackOn: (AIError.CloudRejected) -> Boolean = { it.httpCode in 500..599 },
) : AIRouter {

    override fun complete(intent: AIIntent): Flow<AIChunk> = flow {
        val first = attempt(delegate, intent, firstTryMs) ?: return@flow
        if (first is Tried.Failed) {
            val cause = first.chunk.cause
            if (cause !is AIError.CloudRejected || !fallbackOn(cause)) {
                emit(first.chunk)
                return@flow
            }
        }
        when (val second = attempt(fallback, intent, fallbackMs)) {
            null -> Unit
            is Tried.Failed -> emit(second.chunk)
            Tried.Silent -> emit(AIChunk.ErrorChunk(cause = AIError.CloudRejected(504, "timeout"), route = Route.CLOUD_GEMINI))
        }
    }

    override suspend fun availableRoutes(): Set<Route> = delegate.availableRoutes()

    private sealed interface Tried {
        class Failed(val chunk: AIChunk.ErrorChunk) : Tried
        object Silent : Tried
    }

    /** null — спроба заговорила або скінчилась без слова; що було, вже передано далі. */
    private suspend fun FlowCollector<AIChunk>.attempt(router: AIRouter, intent: AIIntent, waitMs: Long): Tried? = coroutineScope {
        val chunks = Channel<AIChunk>(Channel.UNLIMITED)
        val job = launch {
            try {
                router.complete(intent).collect { chunks.send(it) }
                chunks.close()
            } catch (t: Throwable) {
                chunks.close(t)
            }
        }
        val timeUp = launch(clock) { delay(waitMs) }
        try {
            var spoke = false
            var failed: AIChunk.ErrorChunk? = null
            while (true) {
                val next = if (spoke) {
                    chunks.receiveCatching()
                } else {
                    select<ChannelResult<AIChunk>?> {
                        chunks.onReceiveCatching { it }
                        timeUp.onJoin { null }
                    } ?: return@coroutineScope Tried.Silent
                }
                if (next.isClosed) {
                    next.exceptionOrNull()?.let { throw it }
                    break
                }
                val chunk = next.getOrThrow()
                when {
                    spoke -> emit(chunk)
                    chunk is AIChunk.ErrorChunk -> failed = chunk
                    else -> {
                        spoke = true
                        timeUp.cancel()
                        emit(chunk)
                    }
                }
            }
            if (spoke) null else failed?.let { Tried.Failed(it) }
        } finally {
            timeUp.cancel()
            job.cancel()
        }
    }

    companion object {
        const val FIRST_TRY_MS: Long = 11_000L
        const val FALLBACK_MS: Long = 8_000L
    }
}
