package local.phantom.companion.core.ai.route

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.SystemPrompts

/**
 * Розуми по черзі; кожен шматок несе маршрут того, хто його справді дав.
 *
 * [availableRoutes] — лише справжні розуми. Правила інструментів сюди не
 * входять: інакше «розум є» на телефоні, де думати нічим, і сам він уже
 * нічого не шукатиме.
 */
class BrainChain(
    private val brains: List<AIRouter>,
    private val rules: OnDeviceToolRules = OnDeviceToolRules(),
    private val memory: MemoryGround? = null,
    private val firstChunkTimeoutMs: (Route?) -> Long = ::defaultFirstChunkTimeoutMs,
    private val memoryTimeoutMs: Long = DEFAULT_MEMORY_TIMEOUT_MS,
) : AIRouter {

    override suspend fun availableRoutes(): Set<Route> {
        val first = brains.filter { it !is LastResort }
            .flatMapTo(mutableSetOf()) { runCatching { it.availableRoutes() }.getOrDefault(emptySet()) }
        if (first.isNotEmpty()) return first
        return brains.filter { it is LastResort }
            .flatMapTo(mutableSetOf()) { runCatching { it.availableRoutes() }.getOrDefault(emptySet()) }
    }

    override fun complete(original: AIIntent): Flow<AIChunk> = flow {
        if (original is AIIntent.Text) {
            if (rules.answersItself(original)) {
                val outcome = rules.toolOutcome(original)!!
                emit(AIChunk.Token(text = outcome, route = Route.ON_DEVICE_RULES))
                emit(AIChunk.Final(text = outcome, tokensUsed = 0, route = Route.ON_DEVICE_RULES, durationMs = 0L))
                return@flow
            }
            rules.firstCall(original)?.let { emit(it); return@flow }
        }
        var firstError: AIChunk.ErrorChunk? = null
        var intent = original
        // Опора в памʼяті — лише для розмови власника і лише на його репліку (не на хід інструмента).
        val ground = memory?.takeIf {
            intent is AIIntent.Text && (intent as AIIntent.Text).grounded &&
                (intent as AIIntent.Text).history.lastOrNull()?.role != AIIntent.Text.Role.TOOL
        }
        val filter = if (ground != null) CitationFilter() else null
        // «З памʼяті:» — підпис під відповіддю моделі, тож мовою питання, а не мовою коду.
        val sourcesPrefix = if (SystemPrompts.replyLanguage((intent as? AIIntent.Text)?.prompt, "uk") == "en") SOURCES_PREFIX_EN else SOURCES_PREFIX
        if (ground != null) {
            val text = intent as AIIntent.Text
            // Про сам телефон (додатки, календар) памʼять не свідок — питання йде до розуму з інструментами.
            if (!rules.asksPhone(text.prompt)) runCatching {
                realTimeoutOrNull(memoryTimeoutMs) { ground.dontKnow(text.prompt) }
            }.getOrNull()?.let { dk ->
                emit(AIChunk.Token(text = dk, route = Route.ON_DEVICE_RULES, isFinal = true))
                emit(AIChunk.Final(text = dk, tokensUsed = 0, route = Route.ON_DEVICE_RULES, durationMs = 0L))
                return@flow
            }
            val block = runCatching {
                realTimeoutOrNull(memoryTimeoutMs) { ground.block(text.prompt) }
            }.getOrNull()
            if (!block.isNullOrBlank() && text.systemPrompt?.contains(MemoryGround.HEADER_MARK) != true) {
                intent = text.copy(systemPrompt = listOfNotNull(text.systemPrompt, block).joinToString("\n\n"))
            }
        }

        for (brain in brains) {
            val ready = runCatching {
                realTimeoutOrNull(AVAILABLE_TIMEOUT_MS) { brain.availableRoutes() }
            }.getOrNull().orEmpty()
            if (ready.isEmpty()) continue
            val primaryRoute = ready.firstOrNull()
            val waitMs = firstChunkTimeoutMs(primaryRoute)
            var produced = false
            coroutineScope {
                val chan = Channel<AIChunk>(Channel.UNLIMITED)
                val silent = FirstChunkTimeout()
                // Годинник справжній, не диспетчерний: під runTest віртуальний час
                // перескакує, поки мережевий фейк відповідає з іншого потоку.
                val watchdog = launch(Dispatchers.Default) {
                    delay(waitMs)
                    chan.close(silent)
                }
                val job = launch {
                    try {
                        brain.complete(intent).collect { chan.send(it) }
                        chan.close()
                    } catch (t: Throwable) {
                        chan.close(t)
                    }
                }
                try {
                    while (true) {
                        val rawResult = chan.receiveCatching()
                        if (rawResult.isClosed) {
                            rawResult.exceptionOrNull()?.let { throw it }
                            break
                        }
                        val raw = rawResult.getOrThrow()
                        val chunk = if (filter != null && ground != null) {
                            cited(raw, filter, ground, sourcesPrefix)
                        } else {
                            raw
                        }
                        when {
                            produced -> emit(chunk)
                            chunk is AIChunk.ErrorChunk -> {
                                android.util.Log.w("BrainChain", "${chunk.route}: ${chunk.cause::class.java.simpleName}: ${chunk.cause.message}")
                                if (firstError == null) firstError = chunk
                            }
                            chunk is AIChunk.Notice -> emit(chunk)
                            else -> {
                                produced = true
                                watchdog.cancel()
                                emit(chunk)
                            }
                        }
                    }
                } catch (_: FirstChunkTimeout) {
                    job.cancel()
                    if (!produced && firstError == null) {
                        val cause = if (primaryRoute == Route.PC_NODE) {
                            AIError.PcUnavailable(504, "pc timeout")
                        } else {
                            AIError.CloudRejected(504, "timeout")
                        }
                        firstError = AIChunk.ErrorChunk(
                            cause = cause,
                            route = primaryRoute ?: Route.ON_DEVICE_RULES,
                        )
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (t: Throwable) {
                    job.cancel()
                    android.util.Log.w("BrainChain", "$primaryRoute failed", t)
                    if (!produced && firstError == null) {
                        val cause = (t as? AIError) ?: brokenOrOffline(t)
                        firstError = AIChunk.ErrorChunk(
                            cause = cause,
                            route = primaryRoute ?: Route.ON_DEVICE_RULES,
                        )
                    }
                } finally {
                    watchdog.cancel()
                    job.cancel()
                }
            }
            if (produced) return@flow
        }

        if (intent is AIIntent.Text) {
            rules.toolOutcome(intent)?.let { text ->
                emit(AIChunk.Token(text = text, route = Route.ON_DEVICE_RULES))
                emit(AIChunk.Final(text = text, tokensUsed = 0, route = Route.ON_DEVICE_RULES, durationMs = 0L))
                return@flow
            }
            rules.toolCall(intent)?.let { emit(it); return@flow }
        }

        emit(firstError ?: AIChunk.ErrorChunk(cause = AIError.NoBrain(), route = Route.ON_DEVICE_RULES))
    }

    /**
     * Мітки памʼяті людині не показуються: з токенів прибираються, а в остаточній
     * відповіді стають рядком «З памʼяті: …» — звідки PHANTOM це взяв.
     */
    private suspend fun cited(chunk: AIChunk, filter: CitationFilter, ground: MemoryGround, prefix: String): AIChunk = when (chunk) {
        is AIChunk.Token -> chunk.copy(text = filter.feed(chunk.text))
        is AIChunk.Final -> {
            MemoryGround.CITE.findAll(chunk.text).forEach { filter.seen += it.groupValues[1] }
            val clean = MemoryGround.CITE.replace(chunk.text, "").trim()
            val sources = runCatching {
                realTimeoutOrNull(memoryTimeoutMs) { ground.cite(filter.seen.toList()) }
            }.getOrNull().orEmpty()
            chunk.copy(text = if (sources.isEmpty()) clean else clean + "\n\n" + prefix + sources.joinToString("; ") { "«$it»" })
        }
        else -> chunk
    }

    companion object {
        const val SOURCES_PREFIX = "З памʼяті: "
        const val SOURCES_PREFIX_EN = "From memory: "
        const val PC_FIRST_CHUNK_TIMEOUT_MS = 20_000L
        const val CLOUD_FIRST_CHUNK_TIMEOUT_MS = 20_000L
        const val DEFAULT_MEMORY_TIMEOUT_MS = 2_500L
        private const val AVAILABLE_TIMEOUT_MS = 5_000L

        fun defaultFirstChunkTimeoutMs(route: Route?): Long = when (route) {
            Route.PC_NODE -> PC_FIRST_CHUNK_TIMEOUT_MS
            else -> CLOUD_FIRST_CHUNK_TIMEOUT_MS
        }
    }
}

private class FirstChunkTimeout : Exception()

internal fun brokenOrOffline(t: Throwable): AIError =
    if (t is java.io.IOException) AIError.NoNetwork()
    else AIError.Broken("${t::class.java.simpleName}: ${t.message.orEmpty()}".trimEnd(':', ' '))

private suspend fun <T> realTimeoutOrNull(ms: Long, block: suspend () -> T): T? =
    withContext(Dispatchers.Default) { withTimeoutOrNull(ms) { block() } }
