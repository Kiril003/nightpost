package local.phantom.companion.core.data.memory

/**
 * Чи згадали факт у цій фразі — рахунок за словами, не за вектором.
 *
 * Ембедінг all-MiniLM ріже кирилицю й дає шум замість близькості, тож відбір
 * фактів для підказки не можна вішати на нього: він мовчки не працює. Тут
 * навпаки — збіг видно очима, і його ловить тест.
 *
 * Межу чергування в корені (Київ → Києві, ніч → ночі) цей розбір не бере: без
 * словника її не взяти, і краще знати про діру, ніж вдавати, що її нема.
 */
object FactMention {

    /**
     * Апостроф не є буквою для [Char.isLetterOrDigit], тож «мʼясо» розпалося б
     * на «м» і «ясо» — і не збіглося б із «мяса». Три кодові позиції апострофа
     * люди набирають упереміш, тому вони зникають з обох боків однаково.
     */
    private const val APOSTROPHES = "'’ʼ`´"

    private val STOP: Set<String> = setOf(
        "я", "ти", "ми", "ви", "він", "вона", "воно", "вони", "мене", "мені", "мною",
        "тебе", "тобі", "його", "її", "їх", "нас", "нам", "вас", "вам", "себе", "собі",
        "мій", "моя", "моє", "мої", "твій", "твоя", "твоє", "твої", "наш", "наша", "наші",
        "цей", "ця", "це", "ці", "той", "та", "те", "ті", "такий", "така", "таке",
        "що", "як", "де", "коли", "чому", "куди", "хто", "який", "яка", "яке", "які",
        "але", "або", "чи", "бо", "щоб", "якщо", "тому", "тож", "ще", "вже", "дуже",
        "не", "ні", "так", "був", "була", "було", "були", "бути", "буде", "буду",
        "на", "зі", "до", "за", "по", "від", "для", "про", "при", "над", "під",
        "між", "через", "без", "біля", "після", "перед", "тільки", "лише", "просто",
        "треба", "можна", "маю", "має", "хочу", "хоче", "знаю", "знає", "думаю",
        "кажу", "каже", "скажи", "покажи", "будь", "ласка", "давай", "нехай", "ось",
        "the", "and", "are", "was", "were", "not", "you", "she", "they", "this",
        "that", "have", "has", "does", "for", "with", "your", "but",
    )

    /** Найдовші спершу: відрубується рівно один суфікс. */
    private val ENDINGS: List<String> = listOf(
        "ами", "ями", "ові", "еві", "єві", "ого", "ому", "ему", "єму", "ими", "іми",
        "ах", "ях", "ам", "ям", "ої", "ею", "єю", "ою", "ий", "ій", "их", "іх",
        "им", "ім", "ем", "ом",
        "а", "я", "е", "є", "и", "і", "ї", "о", "у", "ю",
    ).sortedByDescending { it.length }

    private const val MIN_STEM = 3
    private const val MIN_TOKEN = 3
    private const val PREFIX_FLOOR = 4

    /** Слова фрази, нормалізовані й без службових. */
    fun tokens(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            word.setLength(0)
            if (w.length >= MIN_TOKEN && w !in STOP) out += w
        }
        for (ch in text.lowercase()) {
            when {
                ch in APOSTROPHES -> Unit
                ch.isLetterOrDigit() -> word.append(ch)
                else -> flush()
            }
        }
        flush()
        return out
    }

    /**
     * Три кодові позиції апострофа зникають з обох боків рівно так само, як у
     * [tokens] — інакше «мʼясо» і «мʼясо» розходяться на самому першому символі
     * порівняння спільного початку, ще до того, як відмінок встигне збігтися.
     */
    private fun dropApostrophes(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) if (ch !in APOSTROPHES) sb.append(ch)
        return sb.toString()
    }

    /** Основа слова: українська змінює хвіст, тож хвіст і відрубуємо. */
    fun stem(token: String): String {
        for (e in ENDINGS) {
            if (token.length - e.length >= MIN_STEM && token.endsWith(e)) {
                return token.dropLast(e.length)
            }
        }
        return token
    }

    /**
     * Однакові основи, або спільний початок від чотирьох літер — друге ловить
     * те, чого відрубування хвоста не бачить: «собака» → «собаці».
     */
    fun matches(a: String, b: String): Boolean {
        val sa = stem(dropApostrophes(a))
        val sb = stem(dropApostrophes(b))
        if (sa == sb) return true
        if (sa.length < PREFIX_FLOOR || sb.length < PREFIX_FLOOR) return false
        var i = 0
        while (i < sa.length && i < sb.length && sa[i] == sb[i]) i++
        return i >= PREFIX_FLOOR
    }

    /**
     * 0 — фразу й факт ніщо не повʼязує; більше нуля — скільки саме звʼязку.
     * Власна назва важить удвічі: «Марта» в розмові — сигнал, а «машина» може
     * бути й випадковим словом.
     */
    fun score(factText: String, queryTokens: List<String>): Float {
        if (queryTokens.isEmpty()) return 0f
        val factTokens = tokens(factText)
        if (factTokens.isEmpty()) return 0f
        val proper = properNouns(factText)
        var sum = 0f
        for (q in queryTokens.distinct()) {
            val hit = factTokens.firstOrNull { matches(it, q) } ?: continue
            val weight = if (proper.any { matches(it, hit) }) 2f else 1f
            sum += weight * (1f + ((q.length - 4).coerceIn(0, 5)) * 0.1f)
        }
        return sum
    }

    /** [score], стиснутий у 0..1, щоб змішувався з рештою ознак. */
    fun relevance(factText: String, queryTokens: List<String>): Float {
        val raw = score(factText, queryTokens)
        return if (raw <= 0f) 0f else raw / (raw + 2f)
    }

    /** Слова з великої літери — імена людей, кличка пса, марка авта. */
    fun properNouns(text: String): List<String> {
        val out = ArrayList<String>()
        val word = StringBuilder()
        var upper = false
        fun flush() {
            if (word.isNotEmpty() && upper) out += word.toString().lowercase()
            word.setLength(0)
        }
        for (ch in text) {
            when {
                ch in APOSTROPHES -> Unit
                ch.isLetterOrDigit() -> {
                    if (word.isEmpty()) upper = ch.isUpperCase()
                    word.append(ch)
                }
                else -> flush()
            }
        }
        flush()
        return out
    }
}
