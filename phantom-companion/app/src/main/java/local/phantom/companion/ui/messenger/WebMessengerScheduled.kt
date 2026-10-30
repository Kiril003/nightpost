package local.phantom.companion.ui.messenger

import local.phantom.companion.data.scheduled.ScheduledLetters

/**
 * Двері `/messenger/scheduled` для вебу телефона: список, створити, скасувати,
 * надіслати зараз. Міст лише перекладає відповідь у JSON.
 */
internal class WebMessengerScheduled(
    private val letters: ScheduledLetters,
    /** Чи є така розмова: людина з ключем, група або «Збережене». */
    private val known: (String) -> Boolean,
    private val send: suspend (ScheduledLetters.Letter) -> String?,
    private val rearm: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Out {
        data class One(val letter: ScheduledLetters.Letter) : Out
        data class Refused(val status: Int, val code: String, val why: String = "") : Out
    }

    /** Прострочені (будильник не встиг, телефон спав) ідуть перед тим, як показати список. */
    suspend fun list(): List<ScheduledLetters.Letter> {
        if (letters.fireDue(now(), send) > 0) rearm()
        return letters.all()
    }

    fun add(
        conversationId: String,
        text: String,
        dueAtMs: Long,
        quiet: Boolean,
        repeat: String = "",
        whenOnline: Boolean = false,
    ): Out {
        if (text.isBlank()) return Out.Refused(400, "empty")
        if (!known(conversationId)) return Out.Refused(404, "no_chat")
        val letter = letters.add(conversationId, text, dueAtMs, quiet, now(), repeat, whenOnline) ?: return Out.Refused(422, "past")
        rearm()
        return Out.One(letter)
    }

    /** PATCH: перенести, змінити текст або повтор. Null — поле не чіпати. */
    fun edit(id: String, text: String?, dueAtMs: Long?, repeat: String?, whenOnline: Boolean?): Out =
        when (val e = letters.update(id, now(), text, dueAtMs, repeat, whenOnline)) {
            is ScheduledLetters.Edit.Done -> Out.One(e.letter).also { rearm() }
            ScheduledLetters.Edit.Gone -> Out.Refused(404, "no_letter")
            ScheduledLetters.Edit.Empty -> Out.Refused(400, "empty")
            ScheduledLetters.Edit.Past -> Out.Refused(422, "past")
        }

    fun cancel(id: String): Out {
        val gone = letters.remove(id) ?: return Out.Refused(404, "no_letter")
        rearm()
        return Out.One(gone)
    }

    suspend fun sendNow(id: String): Out {
        val letter = letters.get(id) ?: return Out.Refused(404, "no_letter")
        return when (val why = letters.deliver(id, send)) {
            null -> Out.One(letter).also { rearm() }
            ScheduledLetters.GONE -> Out.Refused(404, "no_letter")
            else -> Out.Refused(409, "send_failed", why)
        }
    }
}
