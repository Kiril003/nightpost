package local.phantom.companion.self

import java.time.Instant
import java.time.ZoneId

/**
 * Розбір обіцянок («Надішлю Олі фото завтра», "I'll send Olia the photo tomorrow")
 * і відмінків імен без моделі — чиста логіка для JVM-тестів і продукту.
 */
object CommitmentSpeech {

    data class ParsedCommitment(
        val person: String,
        val personRaw: String,
        val what: String,
        val dueMs: Long?,
        val whenLine: Line? = null,
    )

    private val APOSTROPHES = Regex("['’`´]")
    private val TRAIL = Regex("[\\s.!,;:?…]+$")

    private fun norm(text: String): String = text.replace(APOSTROPHES, "ʼ").trim()
    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val PROMISE_LIST = listOf(
        rx("^(?:а\\s+|і\\s+)?(?:що|кому(?:\\s+і)?\\s+що)\\s+я\\s+(?:вже\\s+)?(?:обіцяв|обіцяла|пообіцяв|пообіцяла)(?:\\s+зробити)?$"),
        rx("^(?:мої\\s+обіцянки|список\\s+обіцянок)$"),
        rx("^(?:what\\s+(?:did|have)\\s+i\\s+promised?|what\\s+did\\s+i\\s+commit\\s+to|my\\s+(?:promises|commitments))$"),
    )

    private val DONE_WORDS = listOf(
        rx("^(?:готово|виконано|зроблено)(?:\\s|$|[.!,])"),
        rx("^done(?:\\s|$|[.!,])"),
    )

    fun isPromiseListQuery(text: String): Boolean {
        val bare = norm(text).replace(TRAIL, "")
        return PROMISE_LIST.any { it.matches(bare) }
    }

    fun isDoneCommand(text: String): Boolean {
        val bare = norm(text).replace(TRAIL, "")
        return DONE_WORDS.any { it.matches(bare) }
    }

    private data class VerbSpec(val rx: Regex, val defaultWhat: String, val lang: String)

    private val UK_OPT_YA = "(?:я\\s+)?(?:(?:обіцяю|пообіцяв|пообіцяла)\\s+)?"
    private val UK_EXPLICIT_PROMISE = "(?:я\\s+)?(?:обіцяю|пообіцяв|пообіцяла)\\s+"

    private val UK_VERBS = listOf(
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:надішлю|скину|перешлю|відправлю|вишлю|кину)|${UK_EXPLICIT_PROMISE}(?:надіслати|скинути|переслати|відправити|вислати|кинути))\\s+(.+)$"), "повідомлення", "uk"),
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:передам|віддам|поверну|принесу|занесу|привезу|закину)|${UK_EXPLICIT_PROMISE}(?:передати|віддати|повернути|принести|занести|привезти|закинути))\\s+(.+)$"), "річ", "uk"),
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:покажу|куплю|підготую|зроблю)|${UK_EXPLICIT_PROMISE}(?:показати|купити|підготувати|зробити))\\s+(.+)$"), "справу", "uk"),
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:подзвоню|передзвоню|зателефоную|наберу)|${UK_EXPLICIT_PROMISE}(?:подзвонити|передзвонити|зателефонувати|набрати))\\s+(.+)$"), "дзвінок", "uk"),
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:напишу|скажу|нагадаю)|${UK_EXPLICIT_PROMISE}(?:написати|сказати|нагадати))\\s+(.+)$"), "повідомлення", "uk"),
        VerbSpec(rx("^(?:${UK_OPT_YA}(?:зустріну|побачу)|${UK_EXPLICIT_PROMISE}(?:зустріти|побачити))\\s+(.+)$"), "зустріч", "uk"),
    )

    private val EN_PREFIX = "^(?:i\\s+will|iʼll|i\\s+am\\s+going\\s+to|iʼm\\s+going\\s+to|i\\s+promise\\s+to|promise\\s+to)\\s+"

    private val EN_VERBS = listOf(
        VerbSpec(rx("${EN_PREFIX}(?:send|give|bring|return|hand|pass|forward|share|show|email|mail|buy|prepare)\\s+(.+)$"), "item", "en"),
        VerbSpec(rx("${EN_PREFIX}(?:call|ring|phone)\\s+(.+)$"), "call", "en"),
        VerbSpec(rx("${EN_PREFIX}(?:text|message|write)\\s+(.+)$"), "message", "en"),
    )

    fun parse(
        text: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        rosterNames: Iterable<String> = emptyList(),
    ): ParsedCommitment? {
        val cleaned = norm(text)
        if (cleaned.isEmpty() || cleaned.endsWith("?")) return null
        val bare = cleaned.replace(TRAIL, "").trim()
        if (bare.isEmpty()) return null

        val timeExtracted = extractTime(bare, nowMs, zone)
        val withoutTime = timeExtracted.remaining.replace(Regex("\\s{2,}"), " ").trim()
        if (withoutTime.isEmpty()) return null

        for (spec in UK_VERBS + EN_VERBS) {
            val m = spec.rx.matchEntire(withoutTime) ?: continue
            val rest = m.groupValues[1].trim()
            val (personRaw, whatRaw) = splitPersonAndWhat(rest, spec.lang, spec.defaultWhat, rosterNames) ?: continue
            val personNom = nominativeName(personRaw)
            val what = cleanWhat(whatRaw, spec.lang).ifEmpty { spec.defaultWhat }
            return ParsedCommitment(
                person = personNom,
                personRaw = personRaw,
                what = what,
                dueMs = timeExtracted.dueMs,
                whenLine = timeExtracted.whenLine,
            )
        }
        return null
    }

    /**
     * Розбір обіцянки для прямої 1:1 розмови, де адресат — це співрозмовник (`peerName`),
     * навіть якщо в тексті не вказано його імʼя (наприклад: «Скину звіт завтра о 18:00»).
     */
    fun offerFor(text: String, peerName: String, sentAtMs: Long, nowMs: Long, zone: ZoneId): ParsedCommitment? =
        parseForPeer(text, peerName, sentAtMs, zone)?.takeIf { (it.dueMs ?: 0L) > nowMs }

    fun parseForPeer(
        text: String,
        peerName: String,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): ParsedCommitment? {
        val direct = parse(text, nowMs, zone, listOf(peerName))
        if (direct != null) return direct

        val cleaned = norm(text)
        if (cleaned.isEmpty() || cleaned.endsWith("?")) return null
        val bare = cleaned.replace(TRAIL, "").trim()
        if (bare.isEmpty()) return null

        val timeExtracted = extractTime(bare, nowMs, zone)
        if (timeExtracted.dueMs == null) return null

        val withoutTime = timeExtracted.remaining.replace(Regex("\\s{2,}"), " ").trim()
        if (withoutTime.isEmpty()) return null

        for (spec in UK_VERBS + EN_VERBS) {
            val m = spec.rx.matchEntire(withoutTime) ?: continue
            val rest = m.groupValues[1].trim()
            val what = cleanWhat(rest, spec.lang).ifEmpty { spec.defaultWhat }
            return ParsedCommitment(
                person = peerName,
                personRaw = peerName,
                what = what,
                dueMs = timeExtracted.dueMs,
                whenLine = timeExtracted.whenLine,
            )
        }
        return null
    }

    private data class TimeExtract(val remaining: String, val dueMs: Long?, val whenLine: Line?)

    private fun extractTime(text: String, nowMs: Long, zone: ZoneId): TimeExtract {
        val hit = SpeechTime.parse(text, nowMs, zone) ?: return TimeExtract(text, null, null)
        return TimeExtract(hit.remaining, hit.atMs, hit.whenLine)
    }

    private val EN_TO_PERSON_RX = rx("^(.+?)\\s+(?:to|for)\\s+([\\p{L}ʼ'-]{2,32})$")

    private val COMMON_NON_NAMES = setOf(
        "фото", "звіт", "ключі", "гроші", "документи", "документ", "книгу", "книжку", "лист",
        "листа", "файл", "файли", "відео", "посилання", "лінків", "лінк", "номер", "контакт",
        "адресу", "проєкт", "проект", "завдання", "рахунок", "чек", "квитки", "квиток",
        "укриття", "тест", "молоко", "скрипт", "код",
        "the", "a", "an", "my", "our", "this", "that", "photo", "photos", "report", "file",
        "files", "keys", "key", "book", "document", "link", "money", "invoice", "ticket", "script",
    )

    private val NON_RECIPIENT_PREPOSITIONS = setOf(
        "в", "у", "на", "до", "з", "із", "зі", "від", "за", "про", "під", "над", "через", "біля", "коло", "по",
    )

    private fun splitPersonAndWhat(
        rest: String,
        lang: String,
        defaultWhat: String,
        rosterNames: Iterable<String>,
    ): Pair<String, String>? {
        val trimmed = rest.trim().trimEnd(',', '.', ';')
        if (trimmed.isEmpty()) return null

        if (lang == "en") {
            EN_TO_PERSON_RX.matchEntire(trimmed)?.let { m ->
                val whatPart = m.groupValues[1].trim()
                val personPart = m.groupValues[2].trim()
                if (looksLikePersonToken(personPart, rosterNames)) {
                    return personPart to whatPart
                }
            }
        }

        val tokens = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val first = tokens[0].trim(',', ':', ';')
        if (tokens.size == 1) {
            return if (looksLikePersonToken(first, rosterNames)) first to defaultWhat else null
        }

        if (looksLikePersonToken(first, rosterNames)) {
            val what = tokens.drop(1).joinToString(" ")
            return first to what
        }

        if (lang == "uk") {
            val capIdx = (1 until tokens.size).firstOrNull { idx ->
                val prev = tokens[idx - 1].trim(',', ':', ';').lowercase()
                prev !in NON_RECIPIENT_PREPOSITIONS && looksLikePersonToken(tokens[idx], rosterNames)
            }
            if (capIdx != null) {
                val person = tokens[capIdx].trim(',', ':', ';')
                val what = (tokens.subList(0, capIdx) + tokens.subList(capIdx + 1, tokens.size)).joinToString(" ")
                return person to what
            }
        }

        return null
    }

    private fun looksLikePersonToken(token: String, rosterNames: Iterable<String>): Boolean {
        val clean = token.trim(',', '.', '!', '?', ';', ':')
        if (clean.length < 2 || clean.any { !it.isLetter() && it != 'ʼ' && it != '\'' && it != '-' }) return false
        val low = clean.lowercase()
        if (low in COMMON_NON_NAMES) return false
        if (isKnownPersonName(low)) return true
        return rosterNames.any { matchesFirstName(clean, it) }
    }

    private fun isKnownPersonName(low: String): Boolean =
        low in KNOWN_NAME_FORMS ||
            low in KNOWN_NOMINATIVES ||
            low in KNOWN_EN_NAMES ||
            nominativeLower(low) in KNOWN_NOMINATIVES

    private fun cleanWhat(raw: String, lang: String): String {
        var s = raw.trim().replace(TRAIL, "")
        if (lang == "en") {
            s = s.replace(rx("^(?:the|a|an)\\s+"), "").trim()
        } else {
            s = s.replace(rx("^(?:про\\s+те\\s+що\\s+|про\\s+|щодо\\s+)"), "").trim()
        }
        return s
    }

    // ── Відмінки українських імен та порівняння ──────────────────────────

    private val DIRECT_NOMINATIVE = mapOf(
        "олі" to "Оля",
        "ользі" to "Оля",
        "олю" to "Оля",
        "олею" to "Оля",
        "оля" to "Оля",
        "ольгу" to "Ольга",
        "ольга" to "Ольга",
        "сашкові" to "Сашко",
        "сашку" to "Сашко",
        "сашком" to "Сашко",
        "сашко" to "Сашко",
        "саші" to "Саша",
        "сашу" to "Саша",
        "саша" to "Саша",
        "петрові" to "Петро",
        "петру" to "Петро",
        "петром" to "Петро",
        "петро" to "Петро",
        "дмитрові" to "Дмитро",
        "дмитру" to "Дмитро",
        "дмитро" to "Дмитро",
        "кирилові" to "Кирило",
        "кирилу" to "Кирило",
        "кирило" to "Кирило",
        "михайлові" to "Михайло",
        "михайлу" to "Михайло",
        "михайло" to "Михайло",
        "павлові" to "Павло",
        "павлу" to "Павло",
        "павло" to "Павло",
        "данилові" to "Данило",
        "данилу" to "Данило",
        "данило" to "Данило",
        "миколі" to "Микола",
        "миколу" to "Микола",
        "микола" to "Микола",
        "андрієві" to "Андрій",
        "андрію" to "Андрій",
        "андрій" to "Андрій",
        "сергієві" to "Сергій",
        "сергію" to "Сергій",
        "сергій" to "Сергій",
        "юрієві" to "Юрій",
        "юрію" to "Юрій",
        "юрій" to "Юрій",
        "олексієві" to "Олексій",
        "олексію" to "Олексій",
        "олексій" to "Олексій",
        "віталієві" to "Віталій",
        "віталію" to "Віталій",
        "віталій" to "Віталій",
        "юлі" to "Юля",
        "юлю" to "Юля",
        "юля" to "Юля",
        "юлії" to "Юлія",
        "юлію" to "Юлія",
        "юлія" to "Юлія",
        "марії" to "Марія",
        "марію" to "Марія",
        "марія" to "Марія",
        "софії" to "Софія",
        "софію" to "Софія",
        "софія" to "Софія",
        "наталії" to "Наталія",
        "наталію" to "Наталія",
        "наталія" to "Наталія",
        "наталі" to "Наталя",
        "наталю" to "Наталя",
        "наталя" to "Наталя",
        "наталці" to "Наталка",
        "наталку" to "Наталка",
        "наталка" to "Наталка",
        "оленці" to "Оленка",
        "оленку" to "Оленка",
        "оленка" to "Оленка",
        "олені" to "Олена",
        "олену" to "Олена",
        "олена" to "Олена",
        "марті" to "Марта",
        "марту" to "Марта",
        "марта" to "Марта",
        "оксані" to "Оксана",
        "оксану" to "Оксана",
        "оксана" to "Оксана",
        "ірині" to "Ірина",
        "ірину" to "Ірина",
        "ірина" to "Ірина",
        "ірі" to "Іра",
        "іру" to "Іра",
        "іра" to "Іра",
        "тані" to "Таня",
        "таню" to "Таня",
        "таня" to "Таня",
        "тетяні" to "Тетяна",
        "тетяну" to "Тетяна",
        "тетяна" to "Тетяна",
        "каті" to "Катя",
        "катю" to "Катя",
        "катя" to "Катя",
        "катерині" to "Катерина",
        "катерину" to "Катерина",
        "катерина" to "Катерина",
        "ані" to "Аня",
        "аню" to "Аня",
        "аня" to "Аня",
        "ганні" to "Ганна",
        "ганну" to "Ганна",
        "ганна" to "Ганна",
        "анні" to "Анна",
        "анну" to "Анна",
        "анна" to "Анна",
        "дарині" to "Дарина",
        "дарину" to "Дарина",
        "дарина" to "Дарина",
        "марині" to "Марина",
        "марину" to "Марина",
        "марина" to "Марина",
        "лесі" to "Леся",
        "лесю" to "Леся",
        "леся" to "Леся",
        "олесі" to "Олеся",
        "олесю" to "Олеся",
        "олеся" to "Олеся",
        "надії" to "Надія",
        "надію" to "Надія",
        "надія" to "Надія",
        "наді" to "Надя",
        "надю" to "Надя",
        "надя" to "Надя",
        "вікторії" to "Вікторія",
        "вікторію" to "Вікторія",
        "вікторія" to "Вікторія",
        "вікторові" to "Віктор",
        "віктору" to "Віктор",
        "віктор" to "Віктор",
        "маркові" to "Марк",
        "марку" to "Марк",
        "марк" to "Марк",
        "маратові" to "Марат",
        "марату" to "Марат",
        "марат" to "Марат",
        "іванові" to "Іван",
        "івану" to "Іван",
        "іван" to "Іван",
        "тарасові" to "Тарас",
        "тарасу" to "Тарас",
        "тарас" to "Тарас",
        "богданові" to "Богдан",
        "богдану" to "Богдан",
        "богдан" to "Богдан",
        "максимові" to "Максим",
        "максиму" to "Максим",
        "максим" to "Максим",
        "олегові" to "Олег",
        "олегу" to "Олег",
        "олег" to "Олег",
        "романові" to "Роман",
        "роману" to "Роман",
        "роман" to "Роман",
        "василеві" to "Василь",
        "василю" to "Василь",
        "василь" to "Василь",
        "степанові" to "Степан",
        "степану" to "Степан",
        "степан" to "Степан",
        "ігореві" to "Ігор",
        "ігорю" to "Ігор",
        "ігор" to "Ігор",
        "зоряні" to "Зоряна",
        "зоряну" to "Зоряна",
        "зоряна" to "Зоряна",
        "аліні" to "Аліна",
        "аліну" to "Аліна",
        "аліна" to "Аліна",
        "діані" to "Діана",
        "діану" to "Діана",
        "діана" to "Діана",
        "христині" to "Христина",
        "христину" to "Христина",
        "христина" to "Христина",
        "анастасії" to "Анастасія",
        "анастасію" to "Анастасія",
        "анастасія" to "Анастасія",
        "валерії" to "Валерія",
        "валерію" to "Валерія",
        "валерія" to "Валерія",
        "євгенії" to "Євгенія",
        "євгенію" to "Євгенія",
        "євгенія" to "Євгенія",
        "євгенові" to "Євген",
        "євгену" to "Євген",
        "євген" to "Євген",
        "денисові" to "Денис",
        "денису" to "Денис",
        "денис" to "Денис",
        "артемові" to "Артем",
        "артему" to "Артем",
        "артем" to "Артем",
        "ярославові" to "Ярослав",
        "ярославу" to "Ярослав",
        "ярослав" to "Ярослав",
        "владові" to "Влад",
        "владу" to "Влад",
        "влад" to "Влад",
    )

    private val KNOWN_EN_NAMES: Set<String> = setOf(
        "olia", "olya", "olga", "sashko", "sasha", "petro", "peter", "dmytro", "dima",
        "kyrylo", "mykhailo", "pavlo", "danylo", "mykola", "andrii", "andriy", "andrew",
        "serhii", "sergiy", "yurii", "yuriy", "oleksii", "vitalii", "yulia", "julia",
        "mariia", "maria", "mary", "sofiia", "sofia", "nataliia", "natalia", "natalie",
        "olena", "helen", "marta", "martha", "oksana", "iryna", "ira", "irina",
        "tania", "tanya", "tetiana", "katia", "katya", "kateryna", "kate",
        "ania", "anya", "anna", "hanna", "daryna", "maryna", "marina", "lesia", "olesia",
        "nadiia", "nadia", "viktoriia", "victoria", "viktor", "victor", "mark", "marat",
        "ivan", "taras", "bohdan", "maksym", "max", "oleh", "oleg", "roman", "vasyl",
        "stepan", "ihor", "igor", "alina", "diana", "khrystyna", "anastasiia", "valeriia",
        "yevhen", "denys", "denis", "artem", "yaroslav", "vlad", "john", "alex", "sarah",
        "david", "michael", "daniel", "paul", "alice", "bob", "emma", "tom", "jack",
        "nick", "sam", "ben", "tim", "dan", "chris", "matt", "luke", "lucy", "claire",
        "emily", "jessica", "laura", "rachel",
    )

    private val O_STEMS = setOf("сашк", "петр", "дмитр", "кирил", "михайл", "павл", "данил", "левк", "юрк")
    private val SOFT_YA_STEMS = setOf("ол", "юл", "тан", "кат", "ан", "сон", "вал", "гал", "над", "лес", "олес", "марус", "дар")

    private val KNOWN_NAME_FORMS: Set<String> = DIRECT_NOMINATIVE.keys
    private val KNOWN_NOMINATIVES: Set<String> = DIRECT_NOMINATIVE.values.map { it.lowercase() }.toSet()

    fun nominativeName(raw: String): String {
        val clean = norm(raw).trim(',', '.', '!', '?', ';', ':')
        if (clean.isEmpty()) return clean
        val low = clean.lowercase()
        DIRECT_NOMINATIVE[low]?.let { return it }
        val resolved = nominativeLower(low)
        return capital(resolved)
    }

    private fun nominativeLower(low: String): String {
        DIRECT_NOMINATIVE[low]?.let { return it.lowercase() }
        if (low.length < 3) return low
        return when {
            low.endsWith("єві") && low.length >= 5 -> low.dropLast(3) + "й"
            low.endsWith("ові") && low.length >= 5 -> {
                val stem = low.dropLast(3)
                if (stem in O_STEMS) "${stem}о" else stem
            }
            low.endsWith("еві") && low.length >= 5 -> low.dropLast(3)
            low.endsWith("ії") && low.length >= 4 -> low.dropLast(2) + "ія"
            low.endsWith("ію") && low.length >= 4 -> low.dropLast(2) + "ія"
            low.endsWith("ці") && low.length >= 4 -> low.dropLast(2) + "ка"
            low.endsWith("зі") && low.length >= 4 -> low.dropLast(2) + "га"
            low.endsWith("і") -> {
                val stem = low.dropLast(1)
                if (stem in SOFT_YA_STEMS) "${stem}я" else "${stem}а"
            }
            low.endsWith("ю") -> {
                val stem = low.dropLast(1)
                when {
                    stem.endsWith("і") -> "${stem}й"
                    stem in SOFT_YA_STEMS -> "${stem}я"
                    else -> "${stem}я"
                }
            }
            low.endsWith("у") && low.length >= 4 -> {
                val stem = low.dropLast(1)
                if (stem in O_STEMS) "${stem}о" else "${stem}а"
            }
            else -> low
        }
    }

    private val MASC_CASE_ENDINGS = setOf("у", "ю", "ові", "еві", "єві", "а", "я", "ом", "ем", "єм", "е")

    private val CANONICAL_GROUPS: List<Set<String>> = listOf(
        setOf("оля", "ольга", "олі", "ользі", "олю", "ольгу"),
        setOf("сашко", "саша", "сашкові", "сашку", "саші", "сашу"),
        setOf("юля", "юлія", "юлі", "юлії", "юлю", "юлію"),
        setOf("наталя", "наталія", "наталка", "наталі", "наталії", "наталці", "наталю", "наталку"),
        setOf("олена", "оленка", "олені", "оленці", "олену", "оленку"),
        setOf("ірина", "іра", "ірині", "ірі", "ірину", "іру"),
        setOf("катя", "катерина", "каті", "катерині", "катю", "катерину"),
        setOf("таня", "тетяна", "тані", "тетяні", "таню", "тетяну"),
        setOf("аня", "анна", "ганна", "ані", "анні", "ганні", "аню", "анну", "ганну"),
    )

    private fun canonicalGroup(nameLow: String): Set<String>? =
        CANONICAL_GROUPS.firstOrNull { nameLow in it }

    /**
     * Стійке до відмінків порівняння першого імені (від 3 літер, кирилиця й латиниця).
     */
    fun matchesFirstName(mention: String, candidateFullName: String): Boolean {
        val mRaw = norm(mention).trim().lowercase()
        val pFirst = norm(candidateFullName).trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
        if (mRaw.length < 3 || pFirst.length < 3) return false
        val mNom = nominativeLower(mRaw)
        val pNom = nominativeLower(pFirst)
        if (mRaw == pFirst || mNom == pFirst || mRaw == pNom || mNom == pNom) return true
        if (mRaw.startsWith(pFirst) && mRaw.removePrefix(pFirst) in MASC_CASE_ENDINGS) return true

        val group = canonicalGroup(mRaw) ?: canonicalGroup(mNom)
        if (group != null && (pFirst in group || pNom in group)) return true

        return false
    }

    fun whenWord(dueMs: Long?, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), english: Boolean = false): String {
        if (dueMs == null) return if (english) "today" else "сьогодні"
        val nowDate = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val dueDate = Instant.ofEpochMilli(dueMs).atZone(zone).toLocalDate()
        return when (dueDate) {
            nowDate -> if (english) "today" else "сьогодні"
            nowDate.plusDays(1) -> if (english) "tomorrow" else "завтра"
            nowDate.plusDays(2) -> if (english) "day after tomorrow" else "післязавтра"
            else -> dueDate.format(
                java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", if (english) java.util.Locale.ENGLISH else java.util.Locale("uk")),
            )
        }
    }

    fun dueLine(person: String, what: String, dueWord: String): String =
        if (what.isBlank()) "$person — $dueWord" else "$person: $what — $dueWord"

    private fun capital(s: String): String =
        if (s.isEmpty()) s else s.substring(0, 1).uppercase() + s.substring(1)
}
