package local.phantom.companion.core.voice.stream

/**
 * Облік почутого: людина перебила PHANTOM — наступна думка моделі мусить
 * знати, до якого речення вона дослухала, і не посилатись на решту.
 *
 * Межа — ціле речення: обірване на півслові людина теж не почула як думку.
 */
class HeardLedger(private val capacity: Int = 32) {

    private val cut = LinkedHashMap<String, String>()

    /** [heard] — що прозвучало за головкою відтворення, [full] — вся відповідь. */
    fun interrupted(messageId: String, full: String, heard: String) {
        val kept = Sentences.heardWhole(full, heard)
        synchronized(cut) {
            cut.remove(messageId)
            if (kept == Sentences.split(full).joinToString(" ")) return
            cut[messageId] = kept
            while (cut.size > capacity) cut.remove(cut.keys.first())
        }
    }

    /** Текст відповіді таким, яким його має бачити модель у історії. */
    fun forPrompt(messageId: String, body: String, mark: (String) -> String): String {
        val kept = synchronized(cut) { cut[messageId] } ?: return body
        return mark(kept)
    }

    fun heard(messageId: String): String? = synchronized(cut) { cut[messageId] }
}

object Sentences {

    private val END = Regex("(?<=[.!?…])\\s+")

    fun split(text: String): List<String> =
        text.trim().split(END).map { it.trim() }.filter { it.isNotEmpty() }

    /**
     * Цілі речення [full], які людина дослухала. Почуте приходить із синтезу
     * вже нормалізованим («5» → «пʼять»), тож звіряємо не символи, а кількість
     * речень, що в почутому встигли закінчитись.
     */
    fun heardWhole(full: String, heard: String): String {
        val h = heard.trim()
        if (h.isEmpty()) return ""
        val pieces = split(h)
        val whole = if (h.last() in ENDERS) pieces.size else pieces.size - 1
        return split(full).take(whole.coerceAtLeast(0)).joinToString(" ")
    }

    private const val ENDERS = ".!?…"
}
