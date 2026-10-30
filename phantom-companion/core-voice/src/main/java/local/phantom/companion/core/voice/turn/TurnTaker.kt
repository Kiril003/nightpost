package local.phantom.companion.core.voice.turn

/**
 * ЖИВА РОЗМОВА — «чи людина договорила?»
 *
 * Один поріг тиші не може бути правильним. 800 мс — це одночасно надто
 * повільно після «так» і надто швидко після «я думаю, що…». Людина вирішує
 * не за секундоміром, а за тим, ЩО щойно прозвучало: фраза, яка обірвалась
 * на «але», не закінчена, скільки б тиші за нею не було.
 *
 * Тому це окремий чистий клас, а не константа: без Android, без годинника,
 * без корутин — годуєш тиками, отримуєш рішення. Поведінку можна
 * сперечатись і перевіряти тестами, не тримаючи телефон у руці.
 */
enum class TailClass {
    /** Фраза звучить завершеною — можна відповідати. */
    COMPLETE,

    /** Обірвана на сполучнику, прийменнику чи комі — це пауза на роздум. */
    INCOMPLETE,

    /** Саме лише «ееее», «хм» — це не репліка. */
    FILLER,

    /** Нічого не почуто. */
    EMPTY,
}

sealed interface TurnDecision {
    /** Ще слухаємо. */
    data object Wait : TurnDecision

    /**
     * Фраза, найпевніше, завершена. Модель можна запускати НАПЕРЕД —
     * говорити ще ні. Якщо людина продовжить, здогад скасується.
     */
    data object Provisional : TurnDecision

    /** Репліка закрита: транскрипт остаточний, відповідати можна. */
    data object Commit : TurnDecision

    /**
     * Довга тиша після незакінченої фрази. Ми НЕ говоримо і НЕ відповідаємо
     * на уламок — лише показуємо, що досі слухаємо. Мовчазне видиме терпіння.
     */
    data object Patience : TurnDecision
}

/**
 * @param provisionalAfterQuestionMs здогад, коли PHANTOM щойно спитав — людина
 *   відповідає швидко й коротко, і чекати на неї довго означає тупість.
 */
class TurnTaker(
    private val lexicon: TailLexicon = TailLexicon.UKRAINIAN,
    private val provisionalAfterQuestionMs: Long = 250L,
    private val provisionalMs: Long = 350L,
    private val commitAfterQuestionMs: Long = 450L,
    private val commitMs: Long = 700L,
    /**
     * Незакінчений хвіст ніколи не отримує швидкої смуги, хай яким упевненим
     * буде VAD: заговорити в паузу на роздум — найдурніша з можливих помилок.
     */
    private val commitIncompleteMs: Long = 1200L,
    private val patienceMs: Long = 2500L,
    /** Скільки тиків поспіль партіал має не мінятись, щоб здогаду можна вірити. */
    private val stableTicksForProvisional: Int = 2,
) {

    private var lastPartial: String = ""
    private var stableTicks: Int = 0
    private var provisionalFired = false
    private var committed = false

    /** Нова репліка почалась — забути все про попередню. */
    fun reset() {
        lastPartial = ""
        stableTicks = 0
        provisionalFired = false
        committed = false
    }

    /** Людина знову заговорила після провізорного кінця: здогад більше не дійсний. */
    fun onSpeechResumed() {
        provisionalFired = false
        committed = false
    }

    /**
     * @param silenceMs скільки триває акустична тиша після останнього слова.
     * @param partial те, що розпізнавач чує просто зараз.
     * @param afterQuestion PHANTOM щойно поставив запитання.
     */
    fun onTick(silenceMs: Long, partial: String, afterQuestion: Boolean = false): TurnDecision {
        val text = partial.trim()
        if (text == lastPartial) stableTicks++ else stableTicks = 0
        lastPartial = text

        if (committed) return TurnDecision.Wait

        return when (lexicon.classify(text)) {
            TailClass.EMPTY -> TurnDecision.Wait

            // Саме лише «ееее» — репліки не було. Мовчимо й слухаємо далі;
            // відповідати на вагання нічим.
            TailClass.FILLER ->
                if (silenceMs >= patienceMs) TurnDecision.Patience else TurnDecision.Wait

            TailClass.INCOMPLETE -> when {
                silenceMs >= commitIncompleteMs -> commit()
                silenceMs >= patienceMs -> TurnDecision.Patience
                else -> TurnDecision.Wait
            }

            TailClass.COMPLETE -> {
                val commitAt = if (afterQuestion) commitAfterQuestionMs else commitMs
                val provisionalAt = if (afterQuestion) provisionalAfterQuestionMs else provisionalMs
                when {
                    silenceMs >= commitAt -> commit()
                    silenceMs >= provisionalAt &&
                        !provisionalFired &&
                        stableTicks >= stableTicksForProvisional -> {
                        provisionalFired = true
                        TurnDecision.Provisional
                    }
                    else -> TurnDecision.Wait
                }
            }
        }
    }

    private fun commit(): TurnDecision {
        committed = true
        return TurnDecision.Commit
    }

    /** Чи встиг вилетіти здогад — тому, хто вирішує, чи скасовувати генерацію. */
    val speculated: Boolean get() = provisionalFired
}

/**
 * Слова, після яких українське речення не закінчується. Це не модель і не
 * граматика — це список на тридцять рядків, який покриває переважну більшість
 * пауз на роздум, бо в українській вони майже завжди стаються ПІСЛЯ
 * сполучника чи прийменника.
 *
 * Лексикон — дані, а не код: інша мова підставляється, а не дописується.
 */
class TailLexicon(
    private val danglingTails: Set<String>,
    private val fillers: Set<String>,
) {

    fun classify(text: String): TailClass {
        val clean = text.trim()
        if (clean.isEmpty()) return TailClass.EMPTY

        // Кома чи тире в кінці — та сама обірвана думка, тільки записана.
        val lastChar = clean.last()
        if (lastChar in CLAUSE_HANGERS) return TailClass.INCOMPLETE

        val words = clean.split(WORD_SPLIT).filter { it.isNotBlank() }
        if (words.isEmpty()) return TailClass.EMPTY
        if (words.all { isFiller(it) }) return TailClass.FILLER

        val last = words.last()
        if (isFiller(last)) return TailClass.INCOMPLETE
        return if (normalize(last) in danglingTails) TailClass.INCOMPLETE else TailClass.COMPLETE
    }

    /**
     * «е», «ееее», «еееееее» — те саме вагання різної довжини, і перелічувати
     * їх у списку безнадійно. Повтори однієї літери схлопуються перед
     * звірянням; на справжні слова це не впливає, бо звіряємось лише зі
     * списком вагань.
     */
    private fun isFiller(word: String): Boolean {
        val n = normalize(word)
        if (n in fillers) return true
        return n.replace(REPEATS, "$1") in fillers
    }

    private fun normalize(word: String): String =
        word.lowercase()
            .replace('\'', 'ʼ')
            .replace('’', 'ʼ')
            .trim { it in TRIM_CHARS }

    companion object {
        private val CLAUSE_HANGERS = charArrayOf(',', ';', ':', '—', '–', '-')
        private val WORD_SPLIT = Regex("\\s+")
        private val REPEATS = Regex("(.)\\1+")
        private val TRIM_CHARS = charArrayOf('.', '!', '?', '…', '"', '«', '»', '(', ')')

        val UKRAINIAN = TailLexicon(
            danglingTails = setOf(
                // сполучники
                "і", "й", "та", "а", "але", "або", "чи", "бо", "що", "щоб", "аби",
                "якщо", "якби", "коли", "поки", "доки", "тому", "тобто", "себто",
                "ніж", "наче", "ніби", "мов", "хоч", "хоча", "проте", "однак",
                "зате", "адже", "оскільки", "тож", "тоді",
                // прийменники
                "в", "у", "на", "до", "з", "зі", "із", "від", "для", "про", "за",
                "під", "над", "при", "без", "через", "між", "біля", "коло",
                "після", "перед", "повз", "крізь", "серед", "поза", "попри",
                "щодо", "разом", "як",
                // вагання, що тягне продовження
                "ну", "ще", "оце", "отже",
            ),
            fillers = setOf(
                "е", "ее", "еее", "ем", "емм", "емме", "хм", "хмм", "м", "мм",
                "ммм", "а-а", "и-и", "ааа", "еее-е",
            ),
        )

        val ENGLISH = TailLexicon(
            danglingTails = setOf(
                "and", "or", "but", "so", "because", "if", "when", "while",
                "that", "which", "to", "of", "in", "on", "at", "for", "with",
                "from", "about", "into", "than", "as", "the", "a", "an",
            ),
            fillers = setOf("uh", "uhh", "um", "umm", "hmm", "hm", "er", "err"),
        )
    }
}
