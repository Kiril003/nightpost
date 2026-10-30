package local.phantom.companion.ui.messenger

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.ui.messenger.AgencySessionManager.Live
import local.phantom.companion.ui.messenger.AgencySessionManager.Phase

/** Відповідь, що росте: один рядок на процес, у памʼяті, а не в журналі ([Live]). */
internal class AssistantLiveBoard(private val now: () -> Long = System::currentTimeMillis) {

    private val _live = MutableStateFlow<Live?>(null)
    val live: StateFlow<Live?> = _live.asStateFlow()

    // StateFlow зливає останні слова з кінцем ходу (вони ставляться впритул), і сторінка
    // отримала б «done» зі словами на крок старшими. Сторінці — кожен стан.
    private val _feed = MutableSharedFlow<Live?>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val feed: SharedFlow<Live?> = _feed.asSharedFlow()

    private fun set(next: Live?) {
        _live.value = next
        _feed.tryEmit(next)
    }

    @Synchronized
    fun begin(conversationId: String, messageId: String, questionId: String? = null) {
        val at = now()
        set(Live(
            conversationId = conversationId,
            messageId = messageId,
            phase = Phase.THINKING,
            text = "",
            questionId = questionId,
            startedAtMs = at,
            changedAtMs = at,
        ))
    }

    @Synchronized
    fun push(messageId: String, phase: Phase, text: String, toolName: String = "") {
        val current = _live.value ?: return
        if (current.messageId != messageId) return
        set(current.copy(phase = phase, text = text, toolName = toolName, changedAtMs = now()))
    }

    @Synchronized
    fun fail(messageId: String, reason: String) {
        val current = _live.value ?: return
        if (current.messageId != messageId) return
        set(current.copy(phase = Phase.FAILED, reason = reason, changedAtMs = now()))
    }

    @Synchronized
    fun end(messageId: String) {
        if (_live.value?.messageId == messageId) set(null)
    }

    @Synchronized
    fun dismiss(conversationId: String) {
        if (_live.value?.conversationId == conversationId) set(null)
    }

    /** [AIChunk.ErrorChunk] не ковтається: причина йде в [SpokenFold.failure], а не мовчанням. */
    suspend fun stream(messageId: String, onToken: (String) -> Unit = {}, chunks: () -> Flow<AIChunk>): SpokenFold {
        val fold = SpokenFold()
        var lastPush: Long? = null
        runCatching {
            chunks().collect { chunk ->
                fold.feed(chunk)
                if (chunk is AIChunk.Token) {
                    onToken(chunk.text)
                    val at = now()
                    if (lastPush.let { it == null || at - it >= TICK_MS }) {
                        lastPush = at
                        push(messageId, Phase.WRITING, fold.shown)
                    }
                }
            }
        }.onFailure { fold.fail(it) }
        if (fold.failure == null) push(messageId, Phase.WRITING, fold.text)
        return fold
    }

    private companion object {
        /** Місцева модель дає десятки лексем за секунду; рядок частіше за це ніхто не прочитає. */
        const val TICK_MS = 60L
    }
}
