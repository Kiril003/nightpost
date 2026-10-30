package local.phantom.companion.core.ai.tool

/**
 * Що людина просить: зібрати друзів на зустріч або розділити рахунок.
 *
 * [Create] несе слова так, як їх сказано: імена («Олену», «Петра») — у знахідному
 * чи орудному, а не як у контактах; заклад — як назвали; час — готовим для
 * веба («19:00», «завтра 19:00», «о 7»). Звіряти це з контактами й переліком
 * закладів — не справа цього читача: він не має ні реєстру, ні мережі.
 */
sealed interface MeetupAsk {
    data class Create(val people: List<String>, val place: String?, val at: String?) : MeetupAsk

    /** [mode] — `claimed` (кожен відмічає свої страви) або `equal` (порівну). */
    data class Split(val mode: String) : MeetupAsk
}

/**
 * Прохання про зустріч і рахунок словами людини, без моделі й без мережі.
 * Лише розбір: нічого не відкриває й не надсилає — це робить інструмент, а
 * «Запросити» натискає людина.
 *
 * Читач навмисно скупий. «Збери» — звичайне слово («збери звіт до 18:00»), тож
 * зустріччю це стає лише коли є слово «зустріч» або імена з великої літери
 * разом з годиною. Усе інше лишається моделі, а не вгадується.
 */
object MeetupSpeech {

    fun read(text: String): MeetupAsk? {
        val said = Said.of(text)
        return readSplit(said) ?: readCreate(said)
    }

    // ── рахунок ──────────────────────────────────────────────────────────

    private fun readSplit(said: Said): MeetupAsk.Split? {
        val lower = said.lower
        if ('?' in lower || QUESTION.containsMatchIn(lower)) return null
        if (!SPLIT_VERB.containsMatchIn(lower) || !SPLIT_OBJECT.containsMatchIn(lower)) return null
        return MeetupAsk.Split(if (EQUALLY.containsMatchIn(lower)) "equal" else "claimed")
    }

    // ── зустріч ──────────────────────────────────────────────────────────

    private fun readCreate(said: Said): MeetupAsk.Create? {
        val verb = VERB.find(said.lower) ?: return null
        val meetWord = MEET_WORD.containsMatchIn(said.lower)
        var rest = said.from(verb.range.last + 1)

        // Слово дня вирізається перед годиною, щоб «v pátek» не приклеїлось до «19:00».
        val dayMatch = DAY.find(rest.lower)
        val dayText = dayMatch?.let { normalizeDay(it.value.trim()) }
        if (dayMatch != null) rest = rest.without(listOf(dayMatch.range))

        // Година: перша справжня. «До 18:00» — межа, не початок: її лише вирізаємо.
        var clock: String? = null
        val cut = ArrayList<IntRange>()
        for (m in CLOCK.findAll(rest.lower).toList()) {
            val read = readClock(m, rest.lower) ?: continue
            cut += m.range
            if (!read.deadline && clock == null) clock = read.text
        }
        rest = rest.without(cut)
        val at = clock?.let { c -> dayText?.let { "$it $c" } ?: c }

        // Заклад — усе після першого «в/у/на/біля…»; люди — усе перед ним.
        val prep = PLACE_PREP.find(rest.lower)
        val place = prep?.let { rest.from(it.range.last + 1).removeAll(POLITE).raw }
            ?.let(::cleanPlace)?.takeIf { it.isNotEmpty() }
        val peoplePart = prep?.let { rest.until(it.range.first) } ?: rest

        val people = readPeople(peoplePart, czech = CZECH_VERB.containsMatchIn(verb.value)) ?: return null
        val capitalized = people.names.isNotEmpty() &&
            people.names.all { n -> n.split(' ').all { w -> w.first().isUpperCase() } }
        if (!(meetWord || (clock != null && (capitalized || people.everybody)))) return null
        return MeetupAsk.Create(people.names, place, at)
    }

    private class People(val names: List<String>, val everybody: Boolean)

    /** Імена з частини фрази перед закладом; `null` — це схоже на речення, а не на перелік імен. */
    private fun readPeople(part: Said, czech: Boolean): People? {
        var working = part.removeAll(FOR_ACTIVITY).removeAll(POLITE)
        var czechList = czech
        WITH_MARKER.find(working.lower)?.let { m ->
            // Слова перед «with / s» — або вид зустрічі (одне-два слова без великої літери), або імена.
            val before = working.until(m.range.first)
            val after = working.from(m.range.last + 1)
            val words = before.raw.split(' ').filter { it.isNotEmpty() }
            working = when {
                words.isEmpty() -> after
                words.any { it.first().isUpperCase() } -> Said(before.raw + ", " + after.raw, before.lower + ", " + after.lower)
                words.size <= MAX_ACTIVITY_WORDS -> after
                else -> return null
            }
            if (m.value.trim().lowercase() != "with") czechList = true
        }
        val cleaned = working.removeAll(MEET_WORD).removeAll(SELF_WORD).removeAll(POLITE)
        var everybody = false
        val names = ArrayList<String>()
        for (chunk in cleaned.split(if (czechList) SEPARATOR_CS else SEPARATOR)) {
            val c = Said(chunk.raw.replace(QUOTES, ""), chunk.lower.replace(QUOTES, "")).trimmed()
            if (c.raw.isEmpty()) continue
            if (c.lower.split(' ').all { it in EVERYBODY }) {
                everybody = true
                continue
            }
            if (c.raw.split(' ').size > MAX_NAME_WORDS) return null
            // «для 5 людей», «3 друзями» — це не імена, і шукати їх у контактах не можна.
            if (c.raw.any { it.isDigit() }) continue
            if (c.raw !in names) names += c.raw
        }
        if (names.size > MAX_PEOPLE) return null
        return People(names, everybody)
    }

    private fun normalizeDay(raw: String): String {
        val cleaned = raw.lowercase().replace(SPACES, " ").trim()
        DAY_NORMALIZED[cleaned]?.let { return it }
        val stripped = cleaned.removePrefix("on ").removePrefix("this ")
        EN_WEEKDAYS[stripped]?.let { return it }
        return cleaned
    }

    private fun cleanPlace(raw: String): String = raw.replace(QUOTES, "").trim().trim(',', '.', ';', '!', ' ')

    private class Clock(val text: String, val deadline: Boolean)

    private fun readClock(m: MatchResult, lower: String): Clock? {
        val prep = m.groups[PREP_GROUP]?.value
        val hh = m.groups[HOUR_GROUP]!!.value.toInt()
        val sep = m.groups[SEP_GROUP]?.value
        val mm = m.groups[MINUTE_GROUP]?.value?.toInt()
        val named = m.groups[HOURS_GROUP] != null
        val part = m.groups[PART_GROUP]?.value
        if (hh > 23 || (mm != null && mm > 59)) return null
        if (prep == "до" || prep == "by") return Clock("", deadline = true)
        val firm = prep == "о" || prep == "об" || prep == "at" || prep == "ve"
        // «25.09» — дата, а не година.
        if (sep == "." && !firm && !named && part == null) return null
        // Голе число без прийменника й без двокрапки — не година.
        if (prep == null && sep != ":" && !named && part == null) return null
        // «в 7» — година лише наприкінці фрази; «на 5 осіб» і «в 2 рази» — ні.
        if (mm == null && !firm && !named && part == null && !TAIL_ENDS.containsMatchIn(lower.substring(m.range.last + 1))) {
            return null
        }
        var hour = hh
        when (part) {
            "вечора", "дня", "pm", "p.m." -> if (hour < 12) hour += 12
            "ранку", "зранку", "am", "a.m." -> if (hour == 12) hour = 0
            "ночі" -> if (hour == 12) hour = 0 else if (hour in 9..11) hour += 12
        }
        // Година, яку можна прочитати і як 7, і як 19, лишається «о 7»: вирішує веб (найближча така).
        val sure = part != null || hour >= 12 || hour == 0 || mm != null
        return Clock(if (sure) pad(hour) + ":" + pad(mm ?: 0) else "о $hour", deadline = false)
    }

    private fun pad(n: Int) = n.toString().padStart(2, '0')

    /**
     * Рядок і його нижній регістр однакової довжини: індекси з одного лягають
     * на другий. `String.lowercase()` довжину не гарантує, тож — по символу.
     */
    private class Said(val raw: String, val lower: String) {
        fun from(start: Int) = Said(raw.substring(start), lower.substring(start)).trimmed()

        fun until(end: Int) = Said(raw.substring(0, end), lower.substring(0, end)).trimmed()

        /** Без пробілів і розділових знаків по краях. */
        fun trimmed(): Said {
            val s = raw.indexOfFirst { !it.isWhitespace() && it !in EDGE }.let { if (it < 0) raw.length else it }
            val e = raw.indexOfLast { !it.isWhitespace() && it !in EDGE }.let { if (it < s) s - 1 else it }
            return Said(raw.substring(s, e + 1), lower.substring(s, e + 1))
        }

        fun without(ranges: List<IntRange>): Said {
            if (ranges.isEmpty()) return this
            val r = StringBuilder(raw)
            val l = StringBuilder(lower)
            ranges.forEach { range -> for (i in range) if (i in 0 until r.length) { r.setCharAt(i, ' '); l.setCharAt(i, ' ') } }
            return Said(r.toString().replace(SPACES, " "), l.toString().replace(SPACES, " ")).trimmed()
        }

        fun removeAll(rx: Regex): Said = without(rx.findAll(lower).map { it.range }.toList())

        fun split(rx: Regex): List<Said> {
            val out = ArrayList<Said>()
            var from = 0
            for (m in rx.findAll(lower)) {
                out += Said(raw.substring(from, m.range.first), lower.substring(from, m.range.first))
                from = m.range.last + 1
            }
            out += Said(raw.substring(from), lower.substring(from))
            return out
        }

        fun replace(rx: Regex, with: (MatchResult) -> String): Said {
            var last = 0
            val r = StringBuilder()
            val l = StringBuilder()
            for (m in rx.findAll(lower)) {
                r.append(raw, last, m.range.first)
                l.append(lower, last, m.range.first)
                val s = with(m)
                r.append(s)
                l.append(s)
                last = m.range.last + 1
            }
            r.append(raw, last, raw.length)
            l.append(lower, last, lower.length)
            return Said(r.toString(), l.toString())
        }

        companion object {
            fun of(text: String): Said {
                // Апостроф усередині слова — ʼ (U+02BC), як скрізь у продукті: «пʼятницю» з будь-якої
                // розкладки — одне слово. Решта знаків-лапок, як і раніше, — ASCII.
                val raw = text.replace(IN_WORD_APOSTROPHE, "ʼ").replace(APOSTROPHES, "'").replace(SPACES, " ").trim()
                val lower = StringBuilder(raw.length).also { sb -> raw.forEach { sb.append(it.lowercaseChar()) } }.toString()
                // «о сьомій вечора» → «о 7 вечора»: веб читає цифри, а не числівники.
                return Said(raw, lower).replace(HOUR_WORD) { m -> m.groupValues[1] + " " + HOURS.getValue(m.groupValues[2]) }
            }
        }
    }

    // Без вбудованого прапорця U: на Android (ICU) він — синтаксична помилка (див. MoneyTool).
    // Тому \w і \b розписані явно — однаково на JVM і на телефоні.
    private const val WORD = "[\\p{L}\\p{N}_]"
    private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
    private fun rx(p: String) = Regex(p.replace("\\b", BOUNDARY).replace("\\w", WORD))

    private const val MAX_PEOPLE = 8
    private const val MAX_NAME_WORDS = 3
    private const val MAX_ACTIVITY_WORDS = 2

    private val IN_WORD_APOSTROPHE = Regex("(?<=\\p{L})['ʼ’`´‘](?=\\p{L})")
    // Без ʼ: його щойно поставив [IN_WORD_APOSTROPHE], і назад у ASCII він не йде.
    private val APOSTROPHES = Regex("[’`´‘]")
    private val SPACES = Regex("\\s+")
    private val QUOTES = Regex("[«»\"“”„]")
    private val EDGE = setOf(',', '.', ';', '!', '?', '…', ':', '-', '—')
    private val TAIL_ENDS = Regex("^\\s*(?:[,.!?;)].*)?$")

    private val QUESTION = rx("^(?:фантом[,\\s]+)?(?:як|що|чому|навіщо|how|what|why|jak|co|proč)\\s")
    private val SPLIT_VERB = rx("\\b(?:розділ\\w*|поділ\\w*|розбий\\w*|розбит\\w*|розкид\\w*|розподіл\\w*|split|rozděl(?:it|te)?)\\b")
    private val SPLIT_OBJECT = rx("\\b(?:рахунок|рахунку|рахунки|рахунків|чек|чеку|чеки|bill|check|účet|účtu|útratu)\\b")
    private val EQUALLY = rx("\\b(?:порівну|навпіл|нарівно|поровну|equally|evenly|rovným\\s+dílem|stejným\\s+dílem|napůl)\\b")

    private const val FILLER = "(?:(?:будь[\\s-]*ласка|please|prosím|фантом|phantom|nightpost|найтпост)[,\\s]+)*"
    private val VERB = rx(
        "^$FILLER(?:(?:давай|let's|lets)\\s+)?(?:(?:(?:ти\\s+)?(?:можеш|зможеш|можете)|can\\s+you|could\\s+you)\\s+)?(?:мені\\s+|нам\\s+|mi\\s+)?" +
            "\\b(?:збер(?:и|іть|емо|імо|ем)|збирай(?:те)?|організуй(?:те)?|організуємо|влаштуй(?:те)?|створи(?:ти)?|" +
            "призначи(?:ти)?|заплануй(?:те)?|запроси(?:ти)?|запросіть|поклич(?:те)?|покликати|" +
            "plan|organi[sz]e|arrange|schedule|set\\s+up|invite|gather|get\\s+together|" +
            "naplánuj|zorganizuj|svolej|pozvi|uspořádej)\\b",
    )
    private val MEET_WORD = rx(
        "\\b(?:зустріч\\w*|зустрін\\w*|побачен\\w*|посиденьк\\w*|сходк\\w*|" +
            "meetup|get-together|schůzk\\w*|setkání)\\b",
    )
    private val FOR_ACTIVITY = rx("\\bfor\\s+(?:a\\s+)?(?:coffee|dinner|lunch|drinks|pizza|beer|tea|breakfast|brunch)\\b")
    private val WITH_MARKER = rx("\\b(?:with|s|se)\\s+")
    private val SELF_WORD = rx("\\b(?:мене|мені|нам|нас|себе|мою|нашу)\\b")
    private val POLITE = rx("\\b(?:будь[\\s-]*ласка|please|prosím)\\b")

    private val SEPARATOR = rx("(?:[,;+&]|\\b(?:й|і|та|и|з|із|зі|для|між|разом|плюс|and)\\b)")
    private val SEPARATOR_CS = rx("(?:[,;+&]|\\b(?:й|і|та|и|з|із|зі|для|між|разом|плюс|and|a)\\b)")
    private val CZECH_VERB = rx("\\b(?:naplánuj|zorganizuj|svolej|pozvi|uspořádej)$")
    private val PLACE_PREP = rx("(?<![\\p{L}\\p{N}_])(?:в|у|ув|на|біля|коло|at|in|near|v|ve|u|blízko)\\s+")

    private val EVERYBODY = setOf(
        "всіх", "усіх", "всі", "усі", "друзів", "друзі", "хлопців", "хлопці", "дівчат", "компанію", "команду",
        "наших", "своїх", "народ", "людей", "всю", "усю", "everyone", "everybody", "friends",
    )

    private val EN_WEEKDAYS = mapOf(
        "monday" to "on monday",
        "tuesday" to "on tuesday",
        "wednesday" to "on wednesday",
        "thursday" to "on thursday",
        "friday" to "on friday",
        "saturday" to "on saturday",
        "sunday" to "on sunday",
    )

    private val DAY_NORMALIZED = mapOf(
        "today" to "today",
        "tomorrow" to "tomorrow",
        "dnes" to "today",
        "zítra" to "tomorrow",
        "v pondělí" to "on monday",
        "v úterý" to "on tuesday",
        "ve středu" to "on wednesday",
        "ve čtvrtek" to "on thursday",
        "v pátek" to "on friday",
        "v sobotu" to "on saturday",
        "v neděli" to "on sunday",
    )

    private val DAY = rx(
        "\\b(?:" +
            "сьогодні|завтра|післязавтра|(?:в|у)\\s+(?:понеділок|вівторок|середу|четвер|пʼятницю|суботу|неділю)|" +
            "today|tomorrow|(?:(?:on|this)\\s+)?(?:monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "dnes|zítra|(?:v|ve)\\s+(?:pondělí|úterý|středu|čtvrtek|pátek|sobotu|neděli)" +
            ")\\b",
    )

    private val HOURS: Map<String, String> = mapOf(
        "першій" to "1", "другій" to "2", "третій" to "3", "четвертій" to "4", "пʼятій" to "5", "шостій" to "6",
        "сьомій" to "7", "восьмій" to "8", "девʼятій" to "9", "десятій" to "10", "одинадцятій" to "11",
        "дванадцятій" to "12",
    )
    private val HOUR_WORD = rx("\\b(о|об|на|в|у|к)\\s+(${HOURS.keys.joinToString("|")})\\b")

    // Групи CLOCK: 1 — прийменник, 2 — година, 3 — роздільник, 4 — хвилини, 5 — «год…», 6 — частина доби.
    private const val PREP_GROUP = 1
    private const val HOUR_GROUP = 2
    private const val SEP_GROUP = 3
    private const val MINUTE_GROUP = 4
    private const val HOURS_GROUP = 5
    private const val PART_GROUP = 6
    private val CLOCK = rx(
        "(?<![\\p{L}\\p{N}_.:,])(?:(о|об|на|в|у|к|до|at|by|v|ve)\\s+)?(\\d{1,2})" +
            "(?:([:.])(\\d{2}))?(?:\\s*(год(?:ин\\w*|\\.)?))?(?:\\s*(ранку|зранку|вечора|дня|ночі|am|pm|a\\.m\\.|p\\.m\\.))?" +
            "(?![\\p{L}\\p{N}_])(?!\\s*(?:осіб|особи|людей|чолов\\w*|хвилин\\w*|хв|гривн\\w*|грн|%|разів|раз|штук|шт))",
    )
}
