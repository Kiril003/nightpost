package local.phantom.companion.self

/**
 * Що людина сказала PHANTOM про себе — без моделі, лише за формою речення.
 *
 * Мовна межа `\b` у JVM знає тільки ASCII, тому межі тут — пробіли й краї рядка.
 */
object SelfSpeech {

    sealed interface Heard {
        data class Remember(val fact: Fact) : Heard
        data class Forget(val query: String) : Heard
        data object ForgetAll : Heard
        data object WhatDoYouKnow : Heard
        data object WhatDidIPromise : Heard
        data object CommitmentDone : Heard
        data class Commitment(val parsed: CommitmentSpeech.ParsedCommitment) : Heard
        data class Facts(val facts: List<Fact>) : Heard
        data object Nothing : Heard
    }

    /** [slot] — місце, де може стояти лише один факт (імʼя одне, вік один). */
    data class Fact(val text: String, val slot: String? = null)

    private val APOSTROPHES = Regex("['’`´]")
    private val TRAIL = Regex("[\\s.!,;:…]+$")
    private val SENTENCES = Regex("(?<=[.!?…])\\s+|\\n+|;\\s*")

    private fun norm(text: String): String = text.replace(APOSTROPHES, "ʼ").trim()

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val LIST = listOf(
        rx("^(?:а\\s+)?що\\s+ти\\s+(?:вже\\s+)?(?:знаєш|памʼятаєш)\\s+про\\s+мене"),
        rx("^(?:а\\s+)?що\\s+ти\\s+про\\s+мене\\s+(?:вже\\s+)?(?:знаєш|памʼятаєш)"),
        rx("^what\\s+do\\s+you\\s+(?:know|remember)\\s+about\\s+me"),
    )
    private val FORGET_ALL = listOf(
        rx("^(?:будь\\s+ласка,?\\s+)?забудь(?:те)?\\s+(?:усе|все)(?:\\s+(?:про\\s+мене|що\\s+знаєш(?:\\s+про\\s+мене)?))?$"),
        rx("^forget\\s+everything(?:\\s+about\\s+me)?$"),
    )
    private val FORGET = listOf(
        rx("^(?:будь\\s+ласка,?\\s+)?забудь(?:те)?[,:]?\\s+(?:,?\\s*що\\s+|про\\s+)?(.+)$"),
        rx("^forget(?:\\s+that|\\s+about)?\\s+(.+)$"),
    )
    private val REMEMBER = listOf(
        rx("^(?:будь\\s+ласка,?\\s+)?запамʼятай(?:те)?[,:]?\\s*(?:,?\\s*що\\s+)?(.+)$"),
        rx("^remember(?:\\s+that)?[,:]?\\s+(.+)$"),
    )

    private class Pattern(val rx: Regex, val slot: String?, val build: (MatchResult) -> String?)

    private val PATTERNS: List<Pattern> = listOf(
        Pattern(rx("^(?:мене\\s+звати|моє\\s+імʼя)\\s+(.{2,40})$"), "name") { m ->
            name(m.groupValues[1])?.let { "Тебе звати $it" }
        },
        Pattern(rx("^my\\s+name\\s+is\\s+(.{2,40})$"), "name") { m ->
            name(m.groupValues[1])?.let { "Your name is $it" }
        },
        Pattern(rx("^мені\\s+(\\d{1,3})(?:\\s+(рік|роки|років))?$"), "age") { m ->
            val n = m.groupValues[1].toInt()
            "Тобі $n ${m.groupValues[2].lowercase().ifEmpty { years(n) }}"
        },
        Pattern(rx("^i\\s+am\\s+(\\d{1,3})(?:\\s+years\\s+old)?$"), "age") { m ->
            "You are ${m.groupValues[1]}"
        },
        Pattern(rx("^я\\s+живу\\s+(в|у|на)\\s+(.{2,60})$"), "home") { m ->
            "Ти живеш ${m.groupValues[1].lowercase()} ${flip(m.groupValues[2])}"
        },
        Pattern(rx("^i\\s+live\\s+in\\s+(.{2,60})$"), "home") { m -> "You live in ${flip(m.groupValues[1])}" },
        Pattern(rx("^я\\s+працюю\\s+(.{2,80})$"), null) { m ->
            steady(m.groupValues[1])?.let { "Ти працюєш $it" }
        },
        Pattern(rx("^я\\s+за\\s+(?:фахом|професією)\\s+(.{2,60})$"), "trade") { m ->
            "За фахом ти ${flip(m.groupValues[1])}"
        },
        Pattern(rx("^я\\s+не\\s+люблю\\s+(.{2,80})$"), null) { m ->
            steady(m.groupValues[1])?.let { "Ти не любиш $it" }
        },
        Pattern(rx("^я\\s+(?:дуже\\s+)?люблю\\s+(.{2,80})$"), null) { m ->
            steady(m.groupValues[1])?.let { "Ти любиш $it" }
        },
        Pattern(rx("^я\\s+(?:вчу|вивчаю)\\s+(.{2,60})$"), null) { m ->
            steady(m.groupValues[1])?.let { "Ти вчиш $it" }
        },
        Pattern(rx("^(?:у|в)\\s+мене\\s+(?:є\\s+)?($KIN)(?:\\s*(?:—|–|-|,))?(?:\\s+(.{1,60}))?$"), null) { m ->
            val who = m.groupValues[1].lowercase()
            val rest = m.groupValues[2].trim()
            if (rest.isEmpty()) "У тебе є $who" else "У тебе є $who ${flip(rest)}"
        },
        Pattern(rx("^(?:у|в)\\s+мене\\s+є\\s+(.{2,80})$"), null) { m ->
            steady(m.groupValues[1])?.let { "У тебе є $it" }
        },
        Pattern(rx("^(мого|мою|моїх)\\s+($KIN_ACC)\\s+звати\\s+(.{2,40})$"), null) { m ->
            name(m.groupValues[3])?.let { "${flip(m.groupValues[1])} ${m.groupValues[2].lowercase()} звати $it" }
        },
        Pattern(rx("^(мій|моя|моє)\\s+($KIN)(?:\\s*(?:—|–|-|це))?\\s+(.{2,80})$"), null) { m ->
            val whose = flip(m.groupValues[1])
            val who = m.groupValues[2].lowercase()
            val rest = m.groupValues[3].trim()
            val named = name(rest)
            when {
                named != null && rest.first().isUpperCase() -> "$whose $who — $named"
                else -> steady(rest)?.let { "$whose $who $it" }
            }
        },
        Pattern(rx("^мій\\s+улюблений\\s+(\\S+)\\s*(?:—|–|-|це)\\s*(.{2,60})$"), null) { m ->
            "Твій улюблений ${m.groupValues[1].lowercase()} — ${flip(m.groupValues[2])}"
        },
        Pattern(rx("^i\\s+(?:love|like)\\s+(.{2,80})$"), null) { m ->
            steady(m.groupValues[1])?.let { "You like $it" }
        },
        Pattern(rx("^i\\s+(?:am\\s+learning|learn)\\s+(.{2,60})$"), null) { m ->
            steady(m.groupValues[1])?.let { "You are learning $it" }
        },
    )

    private const val KIN = "брат|сестра|мама|тато|батько|мати|дружина|чоловік|син|донька|дочка|дідусь|бабуся|дід|баба|" +
        "друг|подруга|дівчина|хлопець|собака|пес|кіт|кішка|начальник|шеф|колега|сусід|сусідка|онук|онука|дядько|тітка"
    private const val KIN_ACC = "брата|сестру|маму|тата|батька|дружину|чоловіка|сина|доньку|дочку|дідуся|бабусю|" +
        "друга|подругу|дівчину|хлопця|собаку|пса|кота|кішку|начальника|шефа|колегу|сусіда|сусідку|онука|онуку|дітей"

    private fun years(n: Int): String = when {
        n % 100 in 11..14 -> "років"
        n % 10 == 1 -> "рік"
        n % 10 in 2..4 -> "роки"
        else -> "років"
    }

    fun read(
        text: String,
        nowMs: Long = System.currentTimeMillis(),
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
        rosterNames: Iterable<String> = emptyList(),
    ): Heard {
        val t = norm(text)
        if (t.isEmpty()) return Heard.Nothing
        val bare = t.replace(TRAIL, "")
        if (LIST.any { it.containsMatchIn(bare) }) return Heard.WhatDoYouKnow
        if (CommitmentSpeech.isPromiseListQuery(t)) return Heard.WhatDidIPromise
        if (CommitmentSpeech.isDoneCommand(t)) return Heard.CommitmentDone
        if (FORGET_ALL.any { it.matches(bare) }) return Heard.ForgetAll
        FORGET.firstNotNullOfOrNull { it.matchEntire(bare) }?.let { m ->
            val q = m.groupValues[1].trim()
            if (q.isNotEmpty()) return Heard.Forget(q)
        }
        REMEMBER.firstNotNullOfOrNull { it.matchEntire(bare) }?.let { m ->
            fromRemember(m.groupValues[1].trim())?.let { return Heard.Remember(it) }
        }
        CommitmentSpeech.parse(t, nowMs, zone, rosterNames)?.let { return Heard.Commitment(it) }
        val facts = t.split(SENTENCES)
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.endsWith("?") }
            .mapNotNull { fromPatterns(it.replace(TRAIL, "")) }
            .distinctBy { it.text.lowercase() }
        return if (facts.isEmpty()) Heard.Nothing else Heard.Facts(facts)
    }

    private fun fromPatterns(sentence: String): Fact? {
        for (p in PATTERNS) {
            val m = p.rx.matchEntire(sentence) ?: continue
            val text = p.build(m) ?: return null
            return Fact(capital(text), p.slot)
        }
        return null
    }

    private fun fromRemember(what: String): Fact? {
        val clean = what.replace(TRAIL, "").trim()
        if (clean.length < 2) return null
        fromPatterns(clean)?.let { return it }
        val first = clean.substringBefore(' ').lowercase()
        if (first == "я") SecondPerson.sentence(clean)?.let { return Fact(capital(it)) }
        if (first == "i") {
            val rest = clean.substringAfter(' ').split(' ').joinToString(" ") { if (it == "am") "are" else it }
            return Fact("You " + flip(rest))
        }
        return Fact(capital(flip(clean)))
    }

    private val TRANSIENT = setOf(
        "над", "зараз", "ще", "сьогодні", "завтра", "вчора", "трохи", "тут", "тебе", "вас",
        "on", "now", "today", "you", "it", "this",
    )

    private fun steady(value: String): String? {
        val v = value.trim()
        val first = v.substringBefore(' ').lowercase()
        if (first in TRANSIENT) return null
        return flip(v)
    }

    private fun name(raw: String): String? {
        val words = raw.trim().split(Regex("\\s+"))
        if (words.size > 3) return null
        if (words.any { w -> w.any { !it.isLetter() && it != '-' && it != '\'' } }) return null
        return words.joinToString(" ") { capital(it) }
    }

    private val FLIP = mapOf(
        "мій" to "твій", "моя" to "твоя", "моє" to "твоє", "мої" to "твої", "мого" to "твого",
        "моєї" to "твоєї", "моєму" to "твоєму", "моїй" to "твоїй", "моїм" to "твоїм",
        "моїх" to "твоїх", "мою" to "твою", "моїми" to "твоїми",
        "мене" to "тебе", "мені" to "тобі", "мною" to "тобою",
        "my" to "your", "me" to "you", "mine" to "yours",
    )

    /** Займенники першої особи → другої; решта слів як були. */
    fun flip(text: String): String {
        val out = StringBuilder(text.length + 8)
        val word = StringBuilder()
        fun flush() {
            if (word.isEmpty()) return
            val w = word.toString()
            val to = FLIP[w.lowercase()]
            out.append(if (to == null) w else if (w[0].isUpperCase()) capital(to) else to)
            word.setLength(0)
        }
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch == '\'') word.append(ch) else { flush(); out.append(ch) }
        }
        flush()
        return out.toString()
    }

    private fun capital(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
}
