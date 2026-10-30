package local.phantom.companion.core.ai.tool

sealed interface ProductAsk {
    data class Remind(val text: String, val inMinutes: Int?, val localTime: String?) : ProductAsk
    data class Remember(val text: String) : ProductAsk
}

object ProductSpeech {

    fun read(text: String): ProductAsk? {
        if ('?' in text) return null
        val norm = text.replace(APOSTROPHES, "ʼ").replace(SPACES, " ").trim()
        if (norm.isEmpty()) return null
        return readRemember(norm) ?: readRemind(norm)
    }

    private fun readRemember(norm: String): ProductAsk.Remember? {
        val m = REMEMBER_HEAD.find(norm) ?: return null
        val tail = norm.substring(m.range.last + 1).trim().trim(*EDGE)
        if (tail.isEmpty()) return null
        return ProductAsk.Remember(tail)
    }

    private fun readRemind(norm: String): ProductAsk.Remind? {
        val head = REMIND_HEAD.find(norm) ?: return null
        val after = norm.substring(head.range.last + 1)
        if (DAY_WORDS.containsMatchIn(after)) return null

        val relMatches = RELATIVE_TIME.findAll(after).toList()
        val clockMatches = CLOCK_TIME.findAll(after).toList()
        if (relMatches.size + clockMatches.size != 1) return null

        var inMinutes: Int? = null
        var localTime: String? = null
        val cutRange: IntRange

        if (relMatches.size == 1) {
            val m = relMatches.single()
            val n = m.groupValues[1].toIntOrNull() ?: return null
            if (n <= 0) return null
            val unit = m.groupValues[2].lowercase()
            val mult = if (HOUR_UNITS.matches(unit)) 60 else 1
            inMinutes = n * mult
            cutRange = m.range
        } else {
            val m = clockMatches.single()
            val hh = m.groupValues[1].toIntOrNull() ?: return null
            val mm = m.groupValues[2].toIntOrNull() ?: return null
            if (hh !in 0..23 || mm !in 0..59) return null
            localTime = hh.toString().padStart(2, '0') + ":" + mm.toString().padStart(2, '0')
            cutRange = m.range
        }

        val withoutTime = (after.substring(0, cutRange.first) + " " + after.substring(cutRange.last + 1))
            .replace(SPACES, " ")
            .trim()
        val withoutConn = LEADING_CONNECTOR.replaceFirst(withoutTime, "").trim().trim(*EDGE)
        // Дата, якої правила не читають («20. října»), лишилась би в тексті, а нагадування — не того дня.
        if (withoutConn.isEmpty() || withoutConn.any { it.isDigit() }) return null

        return ProductAsk.Remind(
            text = withoutConn,
            inMinutes = inMinutes,
            localTime = localTime,
        )
    }

    private const val WORD = "[\\p{L}\\p{N}_]"
    private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
    private fun rx(p: String) = Regex(p.replace("\\b", BOUNDARY).replace("\\w", WORD), RegexOption.IGNORE_CASE)

    private val APOSTROPHES = Regex("['’`´‘]")
    private val SPACES = Regex("\\s+")
    private val EDGE = charArrayOf('.', ',', ';', ':', '!')

    private const val FILLER = "(?:(?:please|будь[\\s-]*ласка|prosím|фантом|phantom)[,\\s]+)*"

    private val REMIND_HEAD = rx(
        "^$FILLER\\b(?:remind\\s+me|set\\s+a\\s+reminder|нагадай(?:\\s+мені)?|постав\\s+нагадування|připomeň(?:\\s+mi)?|nastav\\s+připomínku)\\b[,\\s]*",
    )

    private val REMEMBER_HEAD = rx(
        "^$FILLER\\b(?:" +
            "remember\\s+that|" +
            "запамʼятай(?:\\s+собі)?(?:\\s*,?\\s*що)?|" +
            "zapamatuj\\s+si(?:\\s*,?\\s*že)?" +
            ")\\b[,\\s]*",
    )

    private val RELATIVE_TIME = rx(
        "\\b(?:in|через|za)\\s+(\\d{1,4})\\s*(min(?:ute)?s?|mins?|hours?|h|хв(?:илин\\w*)?|год(?:ин\\w*)?|minut\\w*|hod(?:in\\w*)?)\\b",
    )

    // Дня правила не вміють: local_time значить «найближчі такі години», тож це справа моделі.
    private val DAY_WORDS = rx(
        "\\b(?:today|tonight|tomorrow|monday|tuesday|wednesday|thursday|friday|saturday|sunday|next\\s+week|" +
            "сьогодні|завтра|післязавтра|понеділ\\w*|вівтор\\w*|серед\\w*|четвер\\w*|пʼятниц\\w*|субот\\w*|неділ\\w*|" +
            "dnes|dnes\\s+večer|zítra|pozítří|pondělí|úterý|středu|čtvrtek|pátek|sobotu|neděli)\\b",
    )

    private val HOUR_UNITS = rx("^(?:hours?|h|год(?:ин\\w*)?|hod(?:in\\w*)?)$")

    private val CLOCK_TIME = rx(
        "\\b(?:at|о|об|v|ve)\\s+(\\d{1,2})[:.](\\d{2})\\b",
    )

    private val LEADING_CONNECTOR = rx(
        "^(?:to|that|about|щоб|що|про|že|aby|na)\\b[,\\s]*",
    )
}
