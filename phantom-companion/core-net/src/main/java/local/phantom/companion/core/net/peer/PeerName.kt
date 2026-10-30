package local.phantom.companion.core.net.peer

/**
 * Імʼя людини на вході в застосунок — одна межа на весь продукт.
 *
 * Меж було три й жодної чесної: [PeerInvite] різав на 32 при КОДУВАННІ, ростер
 * на 40 у своєму `cleanName`, група на 40 у своєму. Кадр зі скла 04.09 показав,
 * чого це варте: імʼя на 45 знаків, складене стороннім кодувальником, пройшло
 * розкодування без заперечень і лягло в ростер обрізаним до 40 — посеред слова,
 * без еліпсиса й без жодного слова людині. Саме за іменем людина звіряє, з ким
 * говорить, тож мовчазний обріз — це неправда про людину.
 *
 * Звідси два правила, і обидва тримаються тут.
 *
 * ПЕРШЕ. Межа стоїть на ПРИЙМАННІ, не на кодуванні. Рядок запрошення складає
 * чужий пристрій; обмеження при кодуванні обмежує лише нас, а того, хто хоче
 * прислати 4000 знаків, не стосується взагалі. Тому [accept] викликають усі,
 * до кого імʼя приходить ззовні, і саме вони, а не відправник, вирішують.
 *
 * ДРУГЕ. Скорочення ніколи не мовчить. Довше за [MAX] — обрізається до МЕЖІ
 * ГРАФЕМИ, дістає «…» і піднімає [Accepted.shortened], який їде на диск поруч
 * з іменем, щоб картка людини могла сказати про це словом.
 *
 * ПОКАЗ сюди не належить. Кожна поверхня ріже під СВОЮ ширину штатним
 * `TextOverflow.Ellipsis` з `maxLines`; власна арифметика на екрані — це та
 * сама мовчазна неправда, тільки без диска.
 */
object PeerName {

    /**
     * Скільки графем приймається. Не байтів і не кодових одиниць: межа, названа
     * в байтах, ріже кирилицю вдвічі раніше за латиницю, а названа в кодових
     * одиницях — рве емодзі навпіл.
     *
     * Рядок, що вертається з [accept], НІКОЛИ не довший за це разом з «…».
     */
    const val MAX = 64

    /** Бюджет власного імені в коді запрошення (QR версії 12; див. PeerInviteQrTest). */
    const val CAP_QR_NAME = 32

    const val ELLIPSIS = "…"

    /**
     * Скільки кодових одиниць взагалі читається з чужого рядка.
     *
     * Верхня межа роботи, а не змісту: 64 графеми не бувають довшими за це
     * навіть у найоздобленішому вигляді, тож усе понад — це або сміття, або
     * навмисне. Обріз тут теж вважається скороченням, інакше рядок на 100 тисяч
     * знаків, у якому перші 64 графеми короткі, повернувся б без «…».
     */
    private const val RAW_CAP = 4096

    private const val ZWJ = 0x200D

    /**
     * Що з імені лишилось і чи довелось його для цього різати.
     *
     * [unreadable] — надіслане імʼя було, але після вичищення від нього не
     * лишилось нічого: самі керівні символи, самі невидимі знаки, або вони
     * заповнили всю [RAW_CAP] і справжні літери починались за читаною межею.
     * Порожній результат сам по собі про це не каже, і людина бачила б лише
     * коротку форму ключа — тобто відмову, яка мовчить про причину.
     */
    data class Accepted(
        val name: String,
        val shortened: Boolean,
        val unreadable: Boolean = false,
    )

    /**
     * Чуже імʼя, приведене до того, що можна показати людині.
     *
     * Прибирає керівні символи (U+0000–U+001F, U+007F) і символи формату —
     * серед них U+200B, U+200E і U+202E, яким рядок перевертають задом наперед,
     * щоб «Петро» виглядав як хтось інший. ZWJ (U+200D) лишається: без нього
     * розсипається сімʼя з емодзі, а перевернути рядок ним не можна. Переноси й
     * будь-які пробіли стають одним пробілом, краї обтинаються.
     *
     * Порожнє на виході означає «імені не було» — не «імʼя порожнє»: викликач
     * (ростер) підставляє коротку форму ключа, бо рядок мусить лишитись рядком.
     */
    fun accept(raw: String?): Accepted {
        val clean = sanitise(raw.orEmpty())
        // Надіслали щось, а лишилось ніщо — це не «імені не було», і сказати
        // про це має той, хто показує запис.
        if (clean.text.isEmpty()) return Accepted("", false, unreadable = !raw.isNullOrBlank())
        val units = graphemes(clean.text)
        if (units.size <= MAX && !clean.cut) return Accepted(clean.text, false)
        // MAX − 1, а не MAX: «…» теж займає місце, і межа має триматись разом з
        // ним, інакше «не довше за 64» перестає бути правдою рівно на межі.
        val kept = units.take(minOf(units.size, MAX - 1)).joinToString("").trimEnd()
        return Accepted(kept + ELLIPSIS, true)
    }

    /**
     * Межа для поля, у яке імʼя НАБИРАЮТЬ.
     *
     * Без «…» і без вичищення: людина бачить, що набір спинився, а забирати в
     * неї пробіл, який вона щойно набрала перед другим словом, — це поле, яке
     * бореться з тим, хто в нього пише. Ріже так само по графемі, тож емодзі не
     * розвалюється на півсимвола від одного зайвого дотику.
     */
    fun cap(raw: String): String {
        val units = graphemes(raw)
        return if (units.size <= MAX) raw else units.take(MAX).joinToString("")
    }

    /** Довжина в тому, що людина називає знаками. */
    fun length(text: String): Int = graphemes(text).size

    /**
     * Скільки з бюджету вже витрачено і чи довелось відмовити.
     *
     * Для ВЛАСНОГО імені, у якого є свій, вужчий бюджет — щільність QR. Межа
     * приймання [MAX] тут ні до чого: вона про чуже імʼя, а це про наше.
     *
     * Окремо від [cap] саме заради [refused]: поле, яке мовчки не приймає
     * наступну літеру, — це той самий мовчазний обріз, що й у ростері, тільки
     * людина стоїть перед ним і не розуміє, чому нічого не набирається.
     * Поверхня бере звідси і лічильник, і привід сказати слово.
     */
    data class Fits(val text: String, val used: Int, val budget: Int, val refused: Boolean)

    fun fit(raw: String, budget: Int): Fits {
        val units = graphemes(raw)
        val over = units.size > budget
        return Fits(
            text = if (over) units.take(budget).joinToString("") else raw,
            used = minOf(units.size, budget),
            budget = budget,
            refused = over,
        )
    }

    /**
     * Розбиття на графеми — те, що людина бачить одним знаком.
     *
     * Своє, а не `BreakIterator.getCharacterInstance()`: заміряно на JDK 17,
     * якою йдуть модульні тести, — «👨‍👩‍👧» він ділить на 5, «🇺🇦» на 2,
     * «👍🏽» на 2. Тобто сторож на розрив емодзі був би зеленим на розірваному
     * емодзі. На апараті цей самий клас підпирає ICU і поводиться інакше, тож
     * межа залежала б ще й від того, де її міряти.
     */
    fun graphemes(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        var prev = -1
        // Прапорці складаються рівно по двоє: третя літера підряд починає новий
        // прапор, а не доліплюється до попереднього.
        var regionalRun = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val joins = when {
                prev < 0 -> true
                prev == '\r'.code && cp == '\n'.code -> true
                isControl(prev) || isControl(cp) -> false
                cp == ZWJ || prev == ZWJ -> true
                isMark(cp) || isVariationSelector(cp) || isTag(cp) || isSkinTone(cp) -> true
                isRegional(cp) && isRegional(prev) && regionalRun % 2 == 1 -> true
                else -> false
            }
            if (!joins) {
                out.add(text.substring(start, i))
                start = i
                regionalRun = 0
            }
            regionalRun = if (isRegional(cp)) regionalRun + 1 else 0
            prev = cp
            i += Character.charCount(cp)
        }
        out.add(text.substring(start))
        return out
    }

    private data class Clean(val text: String, val cut: Boolean)

    private fun sanitise(raw: String): Clean {
        val limit = minOf(raw.length, RAW_CAP)
        val sb = StringBuilder(limit)
        var i = 0
        while (i < limit) {
            val cp = raw.codePointAt(i)
            val width = Character.charCount(cp)
            // Обрив рівно на сурогатній парі: половина пари — не символ, і в
            // імені вона стає порожнім квадратиком назавжди.
            if (i + width > limit) break
            when {
                Character.isSpaceChar(cp) || Character.isWhitespace(cp) -> sb.append(' ')
                isControl(cp) -> Unit
                cp in 0xD800..0xDFFF -> Unit
                // Зʼєднувач і знак БЕЗ основи — не знак, а порожнє місце. Імʼя
                // з самих таких лягло б у ростер видимо порожнім рядком, який
                // не є ані іменем, ані коротким ключем.
                cp == ZWJ || isMark(cp) || isVariationSelector(cp) ->
                    if (sb.isNotEmpty() && sb.last() != ' ') sb.appendCodePoint(cp)
                Character.getType(cp) == Character.FORMAT.toInt() -> Unit
                else -> sb.appendCodePoint(cp)
            }
            i += width
        }
        val text = sb.toString().replace(SPACES, " ")
            .trim { it == ' ' || it.code == ZWJ }
        return Clean(text, cut = raw.length > limit)
    }

    private val SPACES = Regex(" +")

    private fun isControl(cp: Int): Boolean = cp < 0x20 || cp == 0x7F

    private fun isMark(cp: Int): Boolean {
        val type = Character.getType(cp).toByte()
        return type == Character.NON_SPACING_MARK ||
            type == Character.ENCLOSING_MARK ||
            type == Character.COMBINING_SPACING_MARK
    }

    private fun isVariationSelector(cp: Int): Boolean =
        cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF

    private fun isTag(cp: Int): Boolean = cp in 0xE0020..0xE007F

    private fun isSkinTone(cp: Int): Boolean = cp in 0x1F3FB..0x1F3FF

    private fun isRegional(cp: Int): Boolean = cp in 0x1F1E6..0x1F1FF
}
