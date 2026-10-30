package local.phantom.companion.core.memory

import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Нормалізація, основи слів, підказки предикатів, хеш-вектор і лічба токенів —
 * дзеркало `model.py::normalize` і `index.py` ПК. Символьні класи повторюють
 * Python (`\w` = літера, число чи «_»; `\s` = `str.isspace`), а не Java-регекси:
 * інакше «²» чи нерозривний пробіл давали б інший відбиток на двох вузлах.
 */
object Text {

    private fun isWord(cp: Int): Boolean = cp == '_'.code || Character.isLetter(cp) || when (Character.getType(cp)) {
        Character.DECIMAL_DIGIT_NUMBER.toInt(), Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt() -> true
        else -> false
    }

    private fun cps(s: String): IntArray = s.codePoints().toArray()

    private fun str(cps: IntArray, from: Int = 0, to: Int = cps.size): String = String(cps, from, to - from)

    /**
     * Усі апострофи — в один, ASCII, як у `model.py` ПК: на ньому стоять основи слів,
     * хеш-вектор і нормалізовані предикати, що їздять синком, тож знак тут не наш вибір.
     * Ключі нижче (стоп-слова, підказки, регекси [Grounding]) пишуться ʼ, як увесь
     * продукт, і проходять через це саме згортання — так «памʼятаєш», набране людиною
     * з ʼ, ' чи ’, сходиться з ключем в одному знаку.
     */
    fun foldApostrophes(s: String): String = s.replace("ʼ", "'").replace("’", "'").replace("`", "'")

    /** `model.py::normalize`: нижній регістр, один апостроф, «ё»→«е», без пунктуації, один пробіл. */
    fun normalize(text: String?): String {
        val s = foldApostrophes((text ?: "").lowercase())
            .replace("ё", "е")
        val out = StringBuilder()
        // [^\w\s'-]+ → " ", далі \s+ → " ", strip()
        var pendingSpace = false
        for (cp in cps(s)) {
            val keep = isWord(cp) || cp == '\''.code || cp == '-'.code
            if (keep) {
                if (pendingSpace && out.isNotEmpty()) out.append(' ')
                pendingSpace = false
                out.appendCodePoint(cp)
            } else {
                pendingSpace = true // і пробіл, і пунктуація стають одним пробілом
            }
        }
        return out.toString()
    }

    fun normalizePredicate(p: String?): String =
        normalize(p).replace(" ", "_").replace("-", "_").ifEmpty { "about" }

    private val SELF_WORDS = setOf("", "я", "мене", "мені", "user", "owner", "власник", "користувач", "self", "i", "me")

    fun normalizeSubject(s: String?): String {
        val n = normalize(s)
        return if (n in SELF_WORDS) MemoryItem.OWNER else n
    }

    /** `service.py::estimate_tokens`: кирилиця ≈ 3 символи на токен; символи — кодові точки. */
    fun estimateTokens(text: String): Int = ceil(text.codePointCount(0, text.length) / 3.0).toInt() + 1

    // ── index.py ───────────────────────────────────────────────────────────
    const val DIM = 512

    private val STOP: Set<String> = setOf(
        "я", "ти", "мій", "моя", "моє", "мої", "мене", "мені", "тебе", "тобі", "твій", "твоя", "твоє",
        "в", "у", "на", "з", "із", "зі", "до", "та", "і", "й", "а", "що", "як", "де", "коли", "хто",
        "який", "яка", "яке", "які", "це", "є", "не", "чи", "про", "по", "за", "мою", "мого", "моєї",
        "звати", "знаєш", "памʼятаєш", "скажи", "нагадай",
    ).mapTo(HashSet()) { foldApostrophes(it) }

    fun stem(word: String): String {
        val c = cps(word)
        return if (c.size > 5) str(c, 0, 5) else word
    }

    /** `[\w']+` по нормалізованому тексту. */
    private fun words(norm: String): List<String> {
        val out = ArrayList<String>()
        val c = cps(norm)
        var start = -1
        for (i in c.indices) {
            val w = isWord(c[i]) || c[i] == '\''.code
            if (w && start < 0) start = i
            if (!w && start >= 0) { out += str(c, start, i); start = -1 }
        }
        if (start >= 0) out += str(c, start, c.size)
        return out
    }

    fun terms(text: String): List<String> =
        words(normalize(text)).filter { it !in STOP && it.codePointCount(0, it.length) > 1 }.map { stem(it) }

    private val PREDICATE_HINTS: List<Pair<String, List<String>>> = listOf(
        "pet" to listOf("пес", "пса", "собак", "кіт", "кота", "кішк", "тварин", "песик", "цуцен"),
        "car" to listOf("машин", "авто", "автом", "тачк", "їжджу", "їзджу"),
        "sibling" to listOf("брат", "брата", "сестр", "сестра"),
        "parent" to listOf("мама", "мати", "тато", "батьк", "батьки"),
        "child" to listOf("доньк", "дочк", "син", "сина", "дітей", "діти", "дитин"),
        "partner" to listOf("дружи", "чолов", "жінк", "дівчи", "хлопц", "партн"),
        "friend" to listOf("друг", "друга", "подру", "друзі"),
        "dislikes" to listOf("не люблю", "ненав", "не перено", "терпі"),
        "likes" to listOf("люблю", "подоб", "обожн", "улюбл"),
        "goal" to listOf("ціль", "мета", "мету", "хочу", "мрію", "планую"),
        "hobby" to listOf("хобі", "граю", "інстр", "захоп"),
        "plan" to listOf("план", "планую", "суботу", "неділю", "завтра"),
        "works_as" to listOf("ким я", "посад", "профес", "працюю"),
        "works_at" to listOf("де я працюю", "компан", "підпри", "фірм", "завод"),
        "lives_in" to listOf("живу", "живе", "місто", "місті", "мешка"),
        "birthday" to listOf("народж", "днюха", "дн "),
        "allergy" to listOf("алерг"),
        "diet" to listOf("мʼяс", "дієт", "вегет", "їм ", "харчу"),
        "studies_at" to listOf("навча", "вчуся", "вчусь", "універ", "інстит"),
        "habit" to listOf("нагаду", "звичк", "щодня", "завжди"),
        "visited" to listOf("був", "була", "ходив", "ходила", "їздив", "вчора", "учора"),
        "age" to listOf("років", "рік", "вік"),
        "nickname" to listOf("кличуть", "прізвиськ"),
        "name" to listOf("мене звати", "моє імʼя"),
    ).map { (predicate, keys) -> predicate to keys.map { foldApostrophes(it) } }

    fun hintedPredicates(query: String): Set<String> {
        val q = " " + normalize(query) + " "
        return PREDICATE_HINTS.filter { (_, keys) -> keys.any { it in q } }.mapTo(LinkedHashSet()) { it.first }
    }

    private fun h(token: String): Int {
        val d = Blake2b.digest(token.toByteArray(Charsets.UTF_8), 4)
        val n = (d[0].toLong() and 0xff) or ((d[1].toLong() and 0xff) shl 8) or
            ((d[2].toLong() and 0xff) shl 16) or ((d[3].toLong() and 0xff) shl 24)
        return (n % DIM).toInt()
    }

    /** `index.py::embed`: основи слів ×2.0 + символьні триграми ×0.5, нормовано. */
    fun embed(text: String): DoubleArray {
        val vec = DoubleArray(DIM)
        val norm = normalize(text)
        for (t in terms(text)) vec[h("w:$t")] += 2.0
        val padded = cps(" $norm ")
        for (i in 0 until padded.size - 2) vec[h("c:" + str(padded, i, i + 3))] += 0.5
        val n = sqrt(pySum(DoubleArray(DIM) { vec[it] * vec[it] })).let { if (it == 0.0) 1.0 else it }
        for (i in vec.indices) vec[i] = vec[i] / n
        return vec
    }

    fun cosine(a: DoubleArray, b: DoubleArray): Double = pySum(DoubleArray(a.size) { a[it] * b[it] })

    /**
     * `sum()` CPython 3.12+ над float — компенсоване додавання Ноймаєра, не наївне.
     * Без цього останні біти косинусів розходились би з ПК, а з ними — порядок записів.
     */
    fun pySum(xs: DoubleArray): Double {
        var f = 0.0
        var c = 0.0
        for (x in xs) {
            val t = f + x
            c += if (kotlin.math.abs(f) >= kotlin.math.abs(x)) (f - t) + x else (x - t) + f
            f = t
        }
        if (c != 0.0 && c.isFinite()) f += c
        return f
    }
}
