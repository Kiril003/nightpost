package local.phantom.companion.self

/**
 * «Я прокидаюсь о шостій» → «Ти прокидаєшся о шостій»: слова людини, звернені
 * до неї самої. Дієслово узгоджується лише перше після «я» (і після «не»,
 * «теж»…), бо тільки там відомо, що це дієслово, а не іменник на «-ю».
 */
object SecondPerson {

    private val FIXED = mapOf(
        "живу" to "живеш", "їду" to "їдеш", "йду" to "йдеш", "іду" to "ідеш", "пишу" to "пишеш",
        "можу" to "можеш", "хочу" to "хочеш", "кажу" to "кажеш", "беру" to "береш", "пʼю" to "пʼєш",
        "стою" to "стоїш", "боюсь" to "боїшся", "боюся" to "боїшся", "лечу" to "летиш", "мушу" to "мусиш",
        "прошу" to "просиш", "ношу" to "носиш", "буду" to "будеш", "їм" to "їси", "їжджу" to "їздиш",
        "плачу" to "платиш", "сплю" to "спиш", "дивлюсь" to "дивишся", "дивлюся" to "дивишся",
        "знаю" to "знаєш", "є" to "є", "маю" to "маєш",
    )

    /** Слова, що можуть стояти між «я» і дієсловом. */
    private val BEFORE_VERB = setOf(
        "не", "теж", "також", "дуже", "ще", "вже", "часто", "завжди", "зазвичай", "рідко", "щодня",
        "щоранку", "щовечора", "іноді", "інколи", "постійно", "трохи", "добре", "погано", "насправді",
        "досі", "й", "і", "вже",
    )

    private val RULES: List<Pair<String, String>> = listOf(
        "джу" to "диш", "блю" to "биш", "плю" to "пиш", "влю" to "виш", "млю" to "миш", "флю" to "фиш",
        "рю" to "риш", "ню" to "ниш", "лю" to "лиш",
        "чу" to "чиш", "жу" to "жиш", "шу" to "сиш",
        "ну" to "неш", "ду" to "деш", "ву" to "веш", "су" to "сеш", "зу" to "зеш", "ру" to "реш", "ту" to "теш",
    ).sortedByDescending { it.first.length }

    private const val VOWELS = "аеєиіїоуюяʼ'"

    /** Друга особа однини для дієслова першої; null — це не схоже на таке дієслово. */
    fun verb(word: String): String? {
        val w = word.lowercase()
        FIXED[w]?.let { return keepCase(word, it) }
        val reflexive = w.endsWith("сь") || w.endsWith("ся")
        val base = if (reflexive) w.dropLast(2) else w
        if (base.length < 3) return null
        FIXED[base]?.let { return keepCase(word, it + if (reflexive) "ся" else "") }
        val out = when {
            base.endsWith("ю") && base[base.length - 2] in VOWELS -> base.dropLast(1) + "єш"
            else -> RULES.firstOrNull { base.endsWith(it.first) }?.let { base.dropLast(it.first.length) + it.second }
        } ?: return null
        return keepCase(word, out + if (reflexive) "ся" else "")
    }

    /**
     * Речення людини від першої особи — до другої. Повертає null, коли в ньому
     * нема «я» на початку, тобто перекладати нічого.
     */
    fun sentence(text: String): String? {
        val words = text.trim().split(Regex("\\s+")).toMutableList()
        if (words.isEmpty() || words[0].lowercase().trimEnd(',') != "я") return null
        words[0] = "Ти"
        var i = 1
        while (i < words.size && words[i].lowercase() in BEFORE_VERB) i++
        if (i < words.size) {
            val raw = words[i]
            val core = raw.trimEnd(',', '.', '!', ';', ':')
            verb(core)?.let { words[i] = it + raw.substring(core.length) }
        }
        return SelfSpeech.flip(words.joinToString(" "))
    }

    /** Третя особа («любить каву», «займається») — до другої («любиш», «займаєшся»). */
    fun fromThird(word: String): String? {
        val w = word.lowercase()
        if (w.endsWith("ться") && w.length > 5) {
            val stem = w.dropLast(4)
            return if (stem.last() in "еєиї") keepCase(word, stem + "шся") else null
        }
        val out = when {
            w.endsWith("ить") -> w.dropLast(3) + "иш"
            w.endsWith("їть") -> w.dropLast(3) + "їш"
            w.length > 2 && w.endsWith("є") -> w + "ш"
            w.length > 3 && w.endsWith("е") && w[w.length - 2] !in VOWELS -> w + "ш"
            else -> null
        } ?: return null
        return keepCase(word, out)
    }

    private fun keepCase(like: String, word: String): String =
        if (like.firstOrNull()?.isUpperCase() == true) word.replaceFirstChar { it.uppercase() } else word
}
