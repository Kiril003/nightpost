package local.phantom.companion.ui.messenger

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.ui.messenger.AgencySessionManager.Live

/** Живі слова помічника на сторінку: лише розмова Nightpost, не частіше за [FLUSH_MS]. */
internal object WebMessengerAssistantLive {

    const val FLUSH_MS = 200L

    data class Flush(
        val messageId: String,
        val phase: String,
        val text: String,
        val questionId: String? = null,
        val reason: String = "",
    )

    /** Що востаннє дійшло до сторінки — для проби ходу (debug). */
    val shown = MutableStateFlow<Flush?>(null)

    fun flushes(
        live: Flow<Live?>,
        everyMs: Long = FLUSH_MS,
        now: () -> Long = System::currentTimeMillis,
    ): Flow<Flush> = channelFlow {
        var seen: Live? = null
        var sent: Flush? = null
        var sentAt = 0L
        var trailing: Job? = null

        suspend fun out(f: Flush) {
            sent = f
            sentAt = now()
            send(f)
        }

        live.collect { l ->
            val mine = l?.takeIf { it.conversationId == AgencySessionManager.PHANTOM_AGENT_ID }
            val was = seen
            seen = mine
            val f = when {
                mine != null -> Flush(mine.messageId, mine.phase.name.lowercase(), mine.text, mine.questionId, mine.reason)
                was == null -> return@collect
                was.phase == AgencySessionManager.Phase.FAILED -> Flush(was.messageId, "clear", "")
                else -> Flush(was.messageId, "done", was.text, was.questionId)
            }
            trailing?.cancel()
            trailing = null
            val last = sent
            val wait = sentAt + everyMs - now()
            // Зміна фази (перше слово, інструмент, поразка, кінець) не чекає: її не можна згубити.
            if (last == null || last.messageId != f.messageId || last.phase != f.phase || wait <= 0) {
                out(f)
            } else {
                trailing = launch {
                    delay(wait)
                    trailing = null
                    out(f)
                }
            }
        }
    }

    fun json(f: Flush): String = buildJsonObject {
        put("chat_id", JsonPrimitive(AgencySessionManager.PHANTOM_AGENT_ID))
        put("message_id", JsonPrimitive(f.messageId))
        put("phase", JsonPrimitive(f.phase))
        put("text", JsonPrimitive(f.text))
        put("question_id", f.questionId?.let(::JsonPrimitive) ?: JsonNull)
        put("reason", JsonPrimitive(f.reason))
    }.toString()
}
