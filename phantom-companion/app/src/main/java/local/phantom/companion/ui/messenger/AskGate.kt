package local.phantom.companion.ui.messenger

/**
 * Одне питання — одна відповідь.
 *
 * Обхід A52 18.10: на одне «Ти тут?» прийшло дві різні відповіді, тобто два
 * походи до моделі й два запити в денний ліміт. Ворота не пускають друге
 * питання з тим самим id, поки перше в роботі, і ті самі слова під новим id
 * у межах [windowMs] — якщо перше не скінчилось поразкою.
 */
internal class AskGate(
    private val windowMs: Long = WINDOW_MS,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private class Ask(val ticket: Long, val questionId: String?, val words: String, val at: Long) {
        var open = true
        var failed = false
    }

    private val asks = ArrayDeque<Ask>()
    private var next = 0L

    /** Квиток для [settle] або null — це питання вже має відповідь чи в роботі. */
    @Synchronized
    fun admit(questionId: String?, prompt: String, retry: Boolean = false): Long? {
        val t = now()
        asks.removeAll { !it.open && t - it.at > windowMs }
        val words = normalize(prompt)
        val twin = asks.any { a ->
            (questionId != null && a.questionId == questionId && a.open) ||
                (!retry && !a.failed && a.words == words && t - a.at <= windowMs)
        }
        if (twin) return null
        while (asks.size >= MAX_KEPT) asks.removeFirst()
        return (++next).also { asks.addLast(Ask(it, questionId, words, t)) }
    }

    @Synchronized
    fun settle(ticket: Long, failed: Boolean) {
        asks.firstOrNull { it.ticket == ticket }?.let {
            it.open = false
            it.failed = failed
        }
    }

    companion object {
        const val WINDOW_MS = 10_000L
        private const val MAX_KEPT = 32
        private val SAFE_ID = Regex("[A-Za-z0-9_-]{1,64}")

        private fun normalize(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

        /** Id питання з client_id веба: повторений лист знаходить той самий рядок журналу. */
        fun questionIdFor(clientId: String?): String? {
            val id = clientId?.trim().orEmpty()
            if (!SAFE_ID.matches(id)) return null
            return if (id.startsWith("msg_")) id else "msg_$id"
        }
    }
}
