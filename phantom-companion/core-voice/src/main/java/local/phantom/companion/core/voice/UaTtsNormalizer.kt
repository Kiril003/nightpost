package local.phantom.companion.core.voice

/**
 * Normalizes Ukrainian text for TTS engines:
 * - Strips Markdown (**, _, `, etc.)
 * - Expands abbreviations (вул., просп., м. Київ, обл.)
 * - Expands numbers into natural Ukrainian words with correct noun cases
 * - Handles navigation cues, signs (+/-), roundabouts, highway refs, and speeds
 * - Cleans up punctuation that confuses TTS
 * - Removes URL links
 */
object UaTtsNormalizer {

    private val markdownRegex = Regex("""(\*\*|__|[*_`~])""")
    private val urlRegex = Regex("""https?://\S+""")
    private val artifactRegex = Regex("""[\{\}\[\]]""")

    /** Word abbreviations, longest first so «м.» never loses to «м». */
    private val wordExpansions = listOf(
        "просп." to "проспекту",
        "т.д." to "так далі",
        "т.п." to "тому подібне",
        "вул." to "вулиці",
        "обл." to "область",
        "ім." to "імені",
        "м." to "місто",
        "км/год" to "кілометрів на годину",
        "м/с" to "метрів на секунду",
        "°c" to "градусів",
        "°C" to "градусів",
    )

    enum class Gender { MASCULINE, FEMININE, NEUTER }

    private val unitsMasculine = listOf("", "один", "два", "три", "чотири", "пʼять", "шість", "сім", "вісім", "девʼять")
    private val unitsFeminine = listOf("", "одна", "дві", "три", "чотири", "пʼять", "шість", "сім", "вісім", "девʼять")
    private val unitsNeuter = listOf("", "одне", "два", "три", "чотири", "пʼять", "шість", "сім", "вісім", "девʼять")
    private val teens = listOf(
        "десять", "одинадцять", "дванадцять", "тринадцять", "чотирнадцять",
        "пʼятнадцять", "шістнадцять", "сімнадцять", "вісімнадцять", "девʼятнадцять",
    )
    private val tens = listOf(
        "", "десять", "двадцять", "тридцять", "сорок",
        "пʼятдесят", "шістдесят", "сімдесят", "вісімдесят", "девʼяносто",
    )
    private val hundreds = listOf(
        "", "сто", "двісті", "триста", "чотириста",
        "пʼятсот", "шістсот", "сімсот", "вісімсот", "девʼятсот",
    )

    fun spellNumberUk(num: Long, gender: Gender = Gender.MASCULINE): String {
        if (num == 0L) return "нуль"
        if (num < 0) return "мінус " + spellNumberUk(-num, gender)
        val parts = mutableListOf<String>()

        val thousands = num / 1000
        val remainder = num % 1000

        if (thousands > 0) {
            val thWords = spellUnder1000(thousands.toInt(), Gender.FEMININE)
            val thSuffix = when {
                thousands % 100 in 11..14 -> "тисяч"
                thousands % 10 == 1L -> "тисяча"
                thousands % 10 in 2..4 -> "тисячі"
                else -> "тисяч"
            }
            parts.add("$thWords $thSuffix")
        }

        if (remainder > 0) {
            parts.add(spellUnder1000(remainder.toInt(), gender))
        }

        return parts.joinToString(" ")
    }

    private fun spellUnder1000(n: Int, gender: Gender): String {
        val h = n / 100
        val t = (n % 100) / 10
        val u = n % 10
        val words = mutableListOf<String>()

        if (h > 0) words.add(hundreds[h])

        if (t == 1) {
            words.add(teens[u])
        } else {
            if (t > 1) words.add(tens[t])
            if (u > 0) {
                val uWord = when (gender) {
                    Gender.FEMININE -> unitsFeminine[u]
                    Gender.NEUTER -> unitsNeuter[u]
                    Gender.MASCULINE -> unitsMasculine[u]
                }
                words.add(uWord)
            }
        }
        return words.joinToString(" ")
    }

    private fun formatQuantity(n: Long, unit: String): String {
        return when (unit) {
            "км", "кілометр", "кілометрів", "кілометри" -> {
                val word = spellNumberUk(n, Gender.MASCULINE)
                val noun = when {
                    n % 100 in 11..14 -> "кілометрів"
                    n % 10 == 1L -> "кілометр"
                    n % 10 in 2..4 -> "кілометри"
                    else -> "кілометрів"
                }
                "$word $noun"
            }
            "м", "метр", "метрів", "метри" -> {
                val word = spellNumberUk(n, Gender.MASCULINE)
                val noun = when {
                    n % 100 in 11..14 -> "метрів"
                    n % 10 == 1L -> "метр"
                    n % 10 in 2..4 -> "метри"
                    else -> "метрів"
                }
                "$word $noun"
            }
            "хв", "хвилина", "хвилин", "хвилини" -> {
                val word = spellNumberUk(n, Gender.FEMININE)
                val noun = when {
                    n % 100 in 11..14 -> "хвилин"
                    n % 10 == 1L -> "хвилина"
                    n % 10 in 2..4 -> "хвилини"
                    else -> "хвилин"
                }
                "$word $noun"
            }
            "год", "година", "годин", "години" -> {
                val word = spellNumberUk(n, Gender.FEMININE)
                val noun = when {
                    n % 100 in 11..14 -> "годин"
                    n % 10 == 1L -> "година"
                    n % 10 in 2..4 -> "години"
                    else -> "годин"
                }
                "$word $noun"
            }
            "сек", "секунда", "секунд", "секунди" -> {
                val word = spellNumberUk(n, Gender.FEMININE)
                val noun = when {
                    n % 100 in 11..14 -> "секунд"
                    n % 10 == 1L -> "секунда"
                    n % 10 in 2..4 -> "секунди"
                    else -> "секунд"
                }
                "$word $noun"
            }
            "грн", "гривня", "гривень", "гривні" -> {
                val word = spellNumberUk(n, Gender.FEMININE)
                val noun = when {
                    n % 100 in 11..14 -> "гривень"
                    n % 10 == 1L -> "гривня"
                    n % 10 in 2..4 -> "гривні"
                    else -> "гривень"
                }
                "$word $noun"
            }
            "%", "відсоток", "відсотка", "відсотків" -> {
                val word = spellNumberUk(n, Gender.MASCULINE)
                val noun = when {
                    n % 100 in 11..14 -> "відсотків"
                    n % 10 == 1L -> "відсоток"
                    n % 10 in 2..4 -> "відсотки"
                    else -> "відсотків"
                }
                "$word $noun"
            }
            else -> "${spellNumberUk(n, Gender.MASCULINE)} $unit"
        }
    }

    fun normalize(text: String): String {
        var clean = text
            .replace(urlRegex, "посиланню")
            .replace(markdownRegex, "")
            .replace(artifactRegex, " ")

        wordExpansions.forEach { (abbr, full) ->
            clean = clean.replace(
                Regex("""(?<=\s|^)${Regex.escape(abbr)}(?=\s|$)"""),
                full,
            )
        }

        // 1. Navigation ordinals for roundabouts & turns:
        clean = clean
            .replace(Regex("""\b1-му зʼїзд\w*"""), "першому зʼїзді")
            .replace(Regex("""\b2-му зʼїзд\w*"""), "другому зʼїзді")
            .replace(Regex("""\b3-му зʼїзд\w*"""), "третьому зʼїзді")
            .replace(Regex("""\b4-му зʼїзд\w*"""), "четвертому зʼїзді")
            .replace(Regex("""\b1-й зʼїзд\w*"""), "перший зʼїзд")
            .replace(Regex("""\b2-й зʼїзд\w*"""), "другий зʼїзд")
            .replace(Regex("""\b3-й зʼїзд\w*"""), "третій зʼїзд")
            .replace(Regex("""\b4-й зʼїзд\w*"""), "четвертий зʼїзд")

        // 2. Highway prefixes: «М-06», «Н-01» -> «траса М нуль шість»
        clean = clean.replace(Regex("""\b([МмНнРрТт])-(\d{1,2})\b""")) { m ->
            val letter = m.groupValues[1].uppercase()
            val code = m.groupValues[2]
            val codeSpelled = if (code.startsWith("0")) {
                "нуль " + spellNumberUk(code.toLong(), Gender.MASCULINE)
            } else {
                spellNumberUk(code.toLong(), Gender.MASCULINE)
            }
            "траса $letter $codeSpelled"
        }

        // 3. Signed numbers (+5 хв, -12 хв):
        clean = clean.replace(Regex("""\+(\d+)\s*(хв|хвилин|м|км)(?![\p{L}\p{N}])""")) { m ->
            "плюс " + formatQuantity(m.groupValues[1].toLong(), m.groupValues[2])
        }
        clean = clean.replace(Regex("""-(\d+)\s*(хв|хвилин|м|км)(?![\p{L}\p{N}])""")) { m ->
            "мінус " + formatQuantity(m.groupValues[1].toLong(), m.groupValues[2])
        }

        // 4. Clock times (14:30 -> чотирнадцять тридцять):
        clean = clean.replace(Regex("""\b([012]?\d):([0-5]\d)\b""")) { m ->
            val h = m.groupValues[1].toLong()
            val min = m.groupValues[2].toLong()
            val hWord = spellNumberUk(h, Gender.FEMININE)
            val minWord = if (min == 0L) "нуль нуль" else spellNumberUk(min, Gender.FEMININE)
            "$hWord $minWord"
        }

        // 5. Decimal quantities with units: «1,5 км» / «1.5 км» -> «півтора кілометра»
        // Та сама ASCII-межа, що й у кроці 6, лише знайдена пізніше: поки крок 6
        // не працював, тест падав на ранішому випадку й до десяткового не доходив.
        // Без цього «1.5 км» розпадалось на «один» + «.» + «пʼять кілометрів».
        clean = clean.replace(Regex("""(?<![\p{L}\p{N}])(\d+)[.,](\d+)\s*(км|м|год|хв|сек)(?![\p{L}\p{N}])""")) { m ->
            val whole = m.groupValues[1].toLongOrNull() ?: 0L
            val frac = m.groupValues[2].toLongOrNull() ?: 0L
            val unit = m.groupValues[3]
            if (whole == 1L && frac == 5L && unit == "км") {
                "півтора кілометра"
            } else if (whole == 0L && frac == 5L && unit == "км") {
                "пів кілометра"
            } else {
                "${spellNumberUk(whole, Gender.MASCULINE)} і ${spellNumberUk(frac, Gender.MASCULINE)} десятих ${unitNounGenitive(unit)}"
            }
        }

        // 6. Integer quantities with units: «200 м» -> «двісті метрів», «5 км» -> «пʼять кілометрів»
        // `\b` у Java рахує межу за ASCII-`\w`, тож після кирилічного «м» її немає
        // НІКОЛИ: «через 200 м» лишалось «через двісті м», і навігація вимовляла
        // букву замість слова. Межі — явні, через літери й цифри Unicode.
        clean = clean.replace(Regex("""(?<![\p{L}\p{N}])(\d+)\s*(км|м|см|мм|год|хв|сек|грн|дол|євр|%|метрів|кілометрів|хвилин|годин|відсотків)(?![\p{L}\p{N}])""")) { m ->
            val num = m.groupValues[1].toLongOrNull() ?: 0L
            val unit = m.groupValues[2]
            formatQuantity(num, unit)
        }

        // 7. Standalone numbers
        clean = clean.replace(Regex("""\b\d+\b""")) { m ->
            val n = m.value.toLongOrNull()
            if (n != null && n <= 999_999) spellNumberUk(n, Gender.MASCULINE) else m.value
        }

        return clean.replace(Regex("""\s+"""), " ").trim()
    }

    private fun unitNounGenitive(unit: String): String = when (unit) {
        "км" -> "кілометра"
        "м" -> "метра"
        "год" -> "години"
        "хв" -> "хвилини"
        "сек" -> "секунди"
        else -> unit
    }
}
