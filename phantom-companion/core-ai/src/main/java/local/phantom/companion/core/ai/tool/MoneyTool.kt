package local.phantom.companion.core.ai.tool

import java.math.BigDecimal

sealed interface MoneyAsk {
    /** [who] — слова імені так, як їх сказано («олені», «коваль»); [coin] — `sol`/`usdc`/`eurc`/`skr` або null. */
    data class Pay(val who: List<String>, val address: String?, val amount: BigDecimal?, val coin: String?, val note: String? = null) : MoneyAsk

    data object Seal : MoneyAsk
}

sealed interface Pick<out T> {
    data class One<T>(val who: T) : Pick<T>
    data class Several<T>(val who: List<T>) : Pick<T>
    data class NoWallet<T>(val who: List<T>) : Pick<T>
    data object Nobody : Pick<Nothing>
}

/**
 * Прохання про гроші словами людини, без мережі й без моделі. Лише розбір:
 * що сказано, кому й скільки. Нічого не підписує й не надсилає.
 */
object MoneyTool {

    fun read(text: String): MoneyAsk? {
        val raw = normalize(text)
        val lower = raw.lowercase()
        if (SEAL_VERB.containsMatchIn(lower) && SEAL_OBJECT.containsMatchIn(lower)) {
            return if ('?' in lower || QUESTION.containsMatchIn(lower)) null else MoneyAsk.Seal
        }

        val address = ADDRESS.find(raw)?.value
        var restRaw = if (address != null) raw.replace(address, " ") else raw
        var rest = String(CharArray(restRaw.length) { restRaw[it].lowercaseChar() })
        var note: String? = null
        REASON_TAIL.find(rest)?.let { m ->
            val tail = restRaw.substring(m.groups[1]!!.range.first).trim().trim('.', ',', ';', '!', '?')
            if (tail.isNotEmpty()) note = tail
            restRaw = restRaw.substring(0, m.range.first)
            rest = rest.substring(0, m.range.first)
        }
        if (note == null) {
            FOR_TAIL.find(rest)?.let { m ->
                if (PRICED.containsMatchIn(rest.substring(0, m.range.first))) {
                    val tail = restRaw.substring(m.groups[1]!!.range.first).trim().trim('.', ',', ';', '!', '?')
                    if (tail.isNotEmpty()) note = tail
                    rest = rest.substring(0, m.range.first)
                }
            }
        }

        var coin: String? = null
        DOLLAR_SIGN.find(rest)?.let { m -> coin = "usdc"; rest = rest.replaceRange(m.range, " " + m.groupValues[1] + " ") }

        val digits = AMOUNT.findAll(rest).toList()
        if (digits.size > 1) return null
        var amount: BigDecimal? = digits.firstOrNull()?.value?.replace(',', '.')?.toBigDecimalOrNull()
        digits.firstOrNull()?.let { rest = rest.replaceRange(it.range, " ") }

        val tokens = rest.split(SPLIT).filter { it.isNotBlank() }.map { it.trim('\'') }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        if (tokens.any { t -> FOREIGN_MONEY.any { it.matches(t) } }) return null

        var verb: Verb? = null
        val left = ArrayList<String>()
        val spoken = ArrayList<BigDecimal>()
        for (t in tokens) {
            val v = VERBS.entries.firstOrNull { (rx, _) -> rx.matches(t) }?.value
            if (v != null && verb == null) { verb = v; continue }
            val wire = COINS.entries.firstOrNull { (rx, _) -> rx.matches(t) }?.value
            if (wire != null) {
                if (coin != null && coin != wire) return null
                coin = wire
                continue
            }
            val n = NUMBERS[t]
            if (n != null) { spoken += n; continue }
            if (t in FILLERS) continue
            left += t
        }
        verb ?: return null
        if (amount == null && spoken.isNotEmpty()) amount = words(spoken)
        if (amount != null && digits.isNotEmpty() && spoken.isNotEmpty()) return null
        if (coin == null && (verb == Verb.WEAK || amount == null)) return null
        if (left.size > 3) return null
        return MoneyAsk.Pay(left, address, amount, coin, note)
    }

    fun <T> pick(said: List<String>, people: List<T>, name: (T) -> String, payable: (T) -> Boolean): Pick<T> {
        if (said.isEmpty()) return Pick.Nobody
        val named = people.filter { nameMatches(said, name(it)) }
        if (named.isEmpty()) return Pick.Nobody
        val ready = named.filter(payable)
        return when (ready.size) {
            0 -> Pick.NoWallet(named)
            1 -> Pick.One(ready.single())
            else -> Pick.Several(ready)
        }
    }

    /** «Олені» → Олена, «Кирилу» → Кирило, «Ользі» → Ольга: кожне сказане слово мусить лягти на слово імені. */
    fun nameMatches(said: List<String>, full: String): Boolean {
        val parts = normalize(full).lowercase().split(SPLIT).filter { it.length >= 2 }
        return said.isNotEmpty() && said.all { s -> parts.any { p -> sameName(normalize(s).lowercase(), p) } }
    }

    private fun sameName(said: String, name: String): Boolean {
        if (said == name) return true
        // Короткій основі — коротке закінчення: інакше «Оля» забирає «Олені».
        return stems(name).any { stem -> stem.length >= 2 && said.startsWith(stem) && said.length - stem.length <= if (stem.length <= 3) 2 else 3 }
    }

    private fun stems(name: String): Set<String> {
        val out = mutableSetOf(name)
        if (name.length > 2 && name.last() in ENDINGS) {
            val stem = name.dropLast(1)
            out += stem
            ALTERNATION[stem.last()]?.let { out += stem.dropLast(1) + it }
        }
        return out
    }

    private fun words(parts: List<BigDecimal>): BigDecimal {
        var total = BigDecimal.ZERO
        var current = BigDecimal.ZERO
        for (p in parts) {
            when {
                p >= THOUSAND -> { total += (if (current.signum() == 0) BigDecimal.ONE else current) * p; current = BigDecimal.ZERO }
                p.compareTo(HUNDRED) == 0 && current.signum() > 0 && current < HUNDRED -> current *= p
                else -> current += p
            }
        }
        return total + current
    }

    private fun normalize(text: String): String = text.replace(APOSTROPHES, "'")

    private enum class Verb { STRONG, WEAK }

    // Без вбудованого прапорця U: на Android (ICU) він — синтаксична помилка, клас падав при першому
    // зверненні на телефоні, хоча на JVM тести зелені. Тому \w і \b розписані явно — однаково на обох.
    private const val WORD = "[\\p{L}\\p{N}_]"
    private const val BOUNDARY = "(?:(?<=$WORD)(?!$WORD)|(?<!$WORD)(?=$WORD))"
    private fun rx(p: String) = Regex(p.replace("\\b", BOUNDARY).replace("\\w", WORD))

    private val APOSTROPHES = Regex("[ʼ’`´‘]")
    private val SPLIT = Regex("[\\s,;:!?()«»\"/]+|(?<!\\d)\\.(?!\\d)")
    private val ADDRESS = Regex("(?<![1-9A-HJ-NP-Za-km-z])[1-9A-HJ-NP-Za-km-z]{32,44}(?![1-9A-HJ-NP-Za-km-z])")
    private val AMOUNT = Regex("(?<![\\w.,])\\d+(?:[.,]\\d+)?(?![\\w]|[.,]\\d)")
    private val DOLLAR_SIGN = Regex("\\$\\s*(\\d+(?:[.,]\\d+)?)")
    private val REASON_TAIL = Regex("\\s(?:за|za|бо|щоб|because|for the|for my|for our)\\s+(.*)$")
    private val FOR_TAIL = Regex("\\sfor\\s+(.*)$")
    private val PRICED = Regex("\\d|\\$|usdc|eurc|\\bsol\\b|\\bskr\\b|dollar")
    private val HUNDRED = BigDecimal(100)
    private val THOUSAND = BigDecimal(1000)

    private val SEAL_VERB = rx("засвідч|опечата|\\bseal\\b|notari[sz]e")
    private val QUESTION = rx("^(?:що|як|чому|навіщо|what|how|why)\\s")
    private val SEAL_OBJECT = rx("фот|знім|світлин|картин|файл|photo|picture|pic\\b|image|file")

    private val VERBS: Map<Regex, Verb> = linkedMapOf(
        rx("переказ\\w*|перекаж\\w*|заплат\\w*|оплат\\w*|pay|transfer|tip|zaplať|zaplatit|převeď|převést|převeďte") to Verb.STRONG,
        rx("переда\\w*|скин\\w*|кин\\w*|надішл\\w*|надісл\\w*|надсила\\w*|відправ\\w*|перевед\\w*|перевест\\w*|дай|дати|send|give|pošli|pošlete|poslat|dej|dát") to Verb.WEAK,
    )

    private val COINS: Map<Regex, String> = linkedMapOf(
        rx("usdc|юсдс|юсдц|usd|долар\\w*|доллар\\w*|dollars?|бакс\\w*|bucks?|dolar\\w*|dolary|dolarů") to "usdc",
        rx("eurc|eur|eura|euros?|євро|евро|€") to "eurc",
        rx("sol|сол|солан\\w*|solana") to "sol",
        rx("skr|скр|сікер\\w*") to "skr",
    )

    private val FOREIGN_MONEY = listOf(rx("грн|гривн\\w*|гривен\\w*|uah|рубл\\w*|btc|біткоїн\\w*|eth|ether|korun\\w*|kč|czk"))

    private val FILLERS = setOf(
        "будь", "ласка", "please", "pls", "фантом", "phantom", "мені", "me", "myself", "на", "до", "для", "to", "for",
        "будь-ласка", "ну", "а", "і", "й", "та", "and", "the", "a", "an", "of", "гроші", "грошей", "money", "суму", "сума",
        "зараз", "now", "в", "у", "with", "з", "із", "через", "via", "solana-ою", "мережі",
        "prosím", "mi", "pro",
    )

    private val ENDINGS = setOf('а', 'я', 'о', 'е', 'є', 'й', 'ь', 'a', 'e', 'o', 'y')
    private val ALTERNATION = mapOf('г' to 'з', 'к' to 'ц', 'х' to 'с')

    private val NUMBERS: Map<String, BigDecimal> = buildMap {
        // Ключі пишуться ʼ і згортаються тим самим [normalize], що й сказане людиною:
        // «пʼять», набране з ' чи ’, лягає на той самий ключ.
        fun num(n: Number, vararg w: String) = w.forEach { put(normalize(it), BigDecimal(n.toString())) }
        num(1, "один", "одна", "одну", "одного", "one", "jeden", "jedna")
        num(2, "два", "дві", "two", "dva", "dvě")
        num(3, "три", "three", "tři")
        num(4, "чотири", "four", "čtyři")
        num(5, "пʼять", "пять", "five", "pět")
        num(6, "шість", "six", "šest")
        num(7, "сім", "seven", "sedm")
        num(8, "вісім", "eight", "osm")
        num(9, "девʼять", "девять", "nine", "devět")
        num(10, "десять", "ten", "deset")
        num(11, "одинадцять", "eleven")
        num(12, "дванадцять", "twelve")
        num(15, "пʼятнадцять", "fifteen")
        num(20, "двадцять", "twenty", "dvacet")
        num(30, "тридцять", "thirty")
        num(40, "сорок", "forty")
        num(50, "пʼятдесят", "fifty", "padesát")
        num(100, "сто", "hundred", "sto")
        num(200, "двісті")
        num(300, "триста")
        num(500, "пʼятсот")
        num(1000, "тисяча", "тисячу", "thousand")
        num(0.5, "пів", "половину", "half")
        num(1.5, "півтора", "півтори")
    }
}
