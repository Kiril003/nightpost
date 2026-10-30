package local.phantom.companion.feature.stream.ui.map.dossier

import local.phantom.companion.feature.stream.ui.map.source.BatchCSource

/**
 * P0 — ЧИСТЕ ядро виправлення: «чи ця стаття взагалі про це місце?».
 *
 * Баг, з якого все почалося: бібліотека показувала розділ «ДОВІДКА» зі
 * статтею «Проспект Георгія Гонгадзе». Геопошук Вікіпедії не бреше — він
 * віддає найближчу статтю в радіусі 400 м, і на широкому проспекті це
 * майже завжди сам проспект. Бреше картка, яка підписує цей текст як
 * довідку про сутність.
 *
 * Тому рішення приймається ТУТ, над сутністю ([PoiEntityRef]), а не в
 * мережевому клієнті, і має рівно три вихідні стани:
 *
 *  • [WikiVerdict.ENTITY] — стаття доведено про це місце. Тільки такий
 *    текст має право стояти під заголовком «ДОВІДКА».
 *  • [WikiVerdict.NEARBY] — стаття про щось інше поруч (вулиця, район,
 *    сусідній обʼєкт). Показуємо чесно, під «ПОРУЧ», дрібно.
 *  • [WikiVerdict.NONE] — показувати нічого.
 *
 * Сходинки доказовості, згори вниз (перша, що спрацювала, — і є відповідь):
 *
 *  1. `wikidata` з обох боків. Q-ідентифікатор — це і є сутність; збіг
 *     доводить тотожність, розбіжність її спростовує. Жодна евристика вище
 *     цього не потрібна.
 *  2. Стаття, витягнута за власним ідентифікатором сутності
 *     ([WikiCandidate.fromEntityTag]).
 *  3. Тег `wikipedia` на OSM-елементі. Мапа сама назвала статтю — інша
 *     стаття за визначенням не про це місце.
 *  4. Точний збіг нормалізованих назв.
 *  5. ВЕТО: стаття-визначення про вулицю / площу / район, тоді як сутність
 *     не є вулицею. Стоїть ПЕРЕД нечітким збігом навмисно — інакше «Кафе
 *     Шевченка» зчепилося б із «Шевченківський район».
 *  6. Нечіткий збіг за ВЛАСНИМИ назвами (стоп-слова категорій відкинуто).
 *
 * Усе решта — [WikiVerdict.NEARBY]. За замовчуванням досьє НЕ вважає статтю
 * своєю: обмовитись «поруч» дешево, збрехати «це про вас» — ні.
 */
internal enum class WikiVerdict { ENTITY, NEARBY, NONE }

/**
 * Кандидат у довідку, зведений до того, що потрібно для рішення. Окремий
 * тип (а не `WikiCard`), щоб ядро не залежало від мережевого шару й
 * лишалося тестованим на JVM.
 */
internal data class WikiCandidate(
    val title: String,
    val extract: String,
    /** `wikibase_item` статті, коли джерело його віддало. */
    val wikidataId: String? = null,
    /** Стаття прийшла за прямим запитом назви з тегу `wikipedia`. */
    val fromEntityTag: Boolean = false,
)

/**
 * Вердикт для одного кандидата. [entity] — сутність, з якою звіряємось.
 */
internal fun judgeWikiCandidate(entity: PoiEntityRef, candidate: WikiCandidate): WikiVerdict {
    if (candidate.title.isBlank() || candidate.extract.isBlank()) return WikiVerdict.NONE

    // 1. Q-ідентифікатори — найтвердіший доказ, який взагалі буває.
    val entityQ = entity.wikidataId
    val candidateQ = candidate.wikidataId
    if (entityQ != null && candidateQ != null) {
        return if (entityQ == candidateQ) WikiVerdict.ENTITY else WikiVerdict.NEARBY
    }

    // 2. Стаття прийшла за прямим запитом ідентифікатора сутності — і Q вище
    //    її не спростував. Окрема сходинка, бо назва статті після редиректу
    //    майже завжди інша, ніж у тезі («uk:Хрещатик (вулиця)» → «Хрещатик»),
    //    і порівняння назв тут дало б хибне «не збіглося».
    if (candidate.fromEntityTag) return WikiVerdict.ENTITY

    // 3. Мапа сама назвала статтю.
    val tagged = entity.wikipedia
    if (tagged != null) {
        return if (titlesMatch(tagged.title, candidate.title)) WikiVerdict.ENTITY else WikiVerdict.NEARBY
    }

    // Стаття прийшла за прямим запитом назви сутності і жоден авторитетний
    // ідентифікатор її не спростував — далі вирішують ті самі назви.
    val name = entity.name?.takeIf { it.isNotBlank() }
        // Тап у порожнє місце: сутності нема, отже нічия стаття не «про неї».
        ?: return WikiVerdict.NEARBY

    // 4. Точний збіг назв.
    if (titlesMatch(name, candidate.title)) return WikiVerdict.ENTITY

    // 5. Вето вулиці/району — власне той випадок, з якого почався цей файл.
    if (!entity.isThoroughfare && describesThoroughfareOrArea(candidate)) return WikiVerdict.NEARBY

    // 6. Нечіткий збіг за власними назвами.
    if (sharesDistinctiveName(name, candidate.title)) return WikiVerdict.ENTITY

    return WikiVerdict.NEARBY
}

/**
 * Вибір із кількох кандидатів: сутнісна стаття завжди виграє, і НІКОЛИ не
 * підміняється найближчою. Якщо жодна не сутнісна — віддаємо першу як
 * контекст «поруч» (джерела віддають кандидатів за зростанням відстані).
 */
internal fun pickWikiForEntity(
    entity: PoiEntityRef,
    candidates: List<WikiCandidate>,
): Pair<WikiCandidate, WikiVerdict>? {
    var nearby: WikiCandidate? = null
    for (candidate in candidates) {
        when (judgeWikiCandidate(entity, candidate)) {
            WikiVerdict.ENTITY -> return candidate to WikiVerdict.ENTITY
            WikiVerdict.NEARBY -> if (nearby == null) nearby = candidate
            WikiVerdict.NONE -> Unit
        }
    }
    return nearby?.let { it to WikiVerdict.NEARBY }
}

// ── Назви ────────────────────────────────────────────────────────────────

/**
 * Нормалізація назв під порівняння: регістр, наголоси, три різні апострофи,
 * лапки, «імені»→«ім», уточнення в дужках («Хрещатик (вулиця)») і пунктуація.
 *
 * Наголоси знімаються не для краси: українська Вікіпедія ставить їх у
 * першому рядку статті («Гонга́дзе»), і без NFD-розкладу такий токен ніколи
 * не збігся б із назвою з OSM. Дужкове уточнення прибирається тому, що
 * Вікіпедія додає його для розрізнення омонімів — до самої назви воно не
 * належить.
 */
internal fun normalizeName(raw: String): String = stripAccents(raw.lowercase())
    .replace(PARENTHETICAL, " ")
    .replace('\'', 'ʼ').replace('’', 'ʼ').replace('`', 'ʼ')
    .replace(BatchCSource.IMENI_FULL, BatchCSource.IMENI_ABBR)
    .replace(PUNCTUATION, " ")
    .replace(WHITESPACE, " ")
    .trim()

/**
 * Знімає ЛИШЕ комбіновані знаки наголосу. Повний NFD-розклад тут був би
 * помилкою: він розібрав би українські «й» і «ї» на «и»/«і» з діакритикою
 * і зіпсував би самі словники нижче.
 */
private fun stripAccents(text: String): String = text.replace(STRESS_MARKS, "")

internal fun titlesMatch(a: String, b: String): Boolean {
    val na = normalizeName(a)
    val nb = normalizeName(b)
    return na.isNotEmpty() && na == nb
}

/**
 * Чи ділять дві назви ВЛАСНУ частину. Родові слова («бібліотека»,
 * «проспект», «центр») викинуто — інакше будь-яка бібліотека збігалася б
 * із будь-якою іншою.
 *
 * Порівняння за спільним префіксом у [STEM_PREFIX] символів, а не за
 * рівністю: українська відмінює («Головка» ↔ «Головко», «Шевченка» ↔
 * «Шевченко»), і точний збіг тут програвав би саме на правильних випадках.
 */
internal fun sharesDistinctiveName(a: String, b: String): Boolean {
    val ta = distinctiveTokens(a)
    val tb = distinctiveTokens(b)
    if (ta.isEmpty() || tb.isEmpty()) return false
    return ta.any { x -> tb.any { y -> sameStem(x, y) } }
}

private fun sameStem(a: String, b: String): Boolean {
    if (a == b) return true
    val n = minOf(a.length, b.length)
    if (n < STEM_PREFIX) return false
    for (i in 0 until STEM_PREFIX) if (a[i] != b[i]) return false
    return true
}

internal fun distinctiveTokens(raw: String): List<String> =
    normalizeName(raw)
        .split(' ')
        .map { it.trim() }
        .filter { it.length >= MIN_TOKEN && it.none(Char::isDigit) && it !in GENERIC_TOKENS }

// ── Вулиці та адміністративні одиниці ────────────────────────────────────

/**
 * Чи є стаття статтею ПРО вулицю / площу / район / населений пункт.
 *
 * Два незалежні сигнали:
 *  • назва — «Проспект Георгія Гонгадзе», «Вулиця Хрещатик»;
 *  • ГОЛОВА визначення — текст після першого тире й до першої коми:
 *    «Проспект Георгія Гонгадзе — **проспект у Подільському районі**…».
 *
 * Саме голова, а не весь текст: у статті про бібліотеку майже напевно
 * зустрінеться «розташована на проспекті…», і пошук по всьому абзацу
 * відкидав би правильні статті.
 */
internal fun describesThoroughfareOrArea(candidate: WikiCandidate): Boolean {
    if (titleLooksThoroughfare(candidate.title)) return true
    return headLooksThoroughfare(definitionHead(candidate.extract))
}

private fun titleLooksThoroughfare(title: String): Boolean {
    val tokens = normalizeName(title).split(' ').filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return false
    // «Проспект Гонгадзе» / «Хрещатик вулиця» — родове слово стоїть скраю.
    return isThoroughfareWord(tokens.first()) || isThoroughfareWord(tokens.last())
}

/**
 * Текст після першого тире у першому реченні, до першої коми — і порожньо,
 * якщо тире нема.
 *
 * Тире тут обовʼязкове: українська Вікіпедія відкриває статтю саме
 * означенням «X — <родове слово> …», і лише в цій позиції родове слово
 * справді називає предмет статті. Без тире («Парк у центрі міста.») те саме
 * слово стоїть у локативі й нічого не означує — на цьому місці евристика
 * без вимоги тире відкидала б правильні статті.
 */
internal fun definitionHead(extract: String): String {
    val firstSentence = extract.substringBefore(". ").take(DEFINITION_SCAN)
    val parts = DASH.split(firstSentence, limit = 2)
    if (parts.size < 2) return ""
    return parts[1].substringBefore(',').take(DEFINITION_HEAD)
}

private fun headLooksThoroughfare(head: String): Boolean =
    normalizeName(head).split(' ').any { isThoroughfareWord(it) }

private fun isThoroughfareWord(token: String): Boolean =
    THOROUGHFARE_EXACT.contains(token) || THOROUGHFARE_STEMS.any { token.startsWith(it) }

private val THOROUGHFARE_STEMS = BatchCSource.THOROUGHFARE_STEMS

private val THOROUGHFARE_EXACT = BatchCSource.THOROUGHFARE_EXACT

private val GENERIC_TOKENS = BatchCSource.GENERIC_TOKENS

/** Скільки символів спільного початку вважаємо однією основою. */
private const val STEM_PREFIX = 5

/** Коротші токени в українській майже завжди службові. */
private const val MIN_TOKEN = 4

/** Скільки символів витягу взагалі читаємо, шукаючи визначення. */
private const val DEFINITION_SCAN = 240

/** Скільки символів голови визначення перевіряємо. */
private const val DEFINITION_HEAD = 80

private val PARENTHETICAL = Regex("""\([^)]*\)""")
private val PUNCTUATION = Regex("""[.,;:!?«»"„“()\[\]/\\—–-]""")
private val WHITESPACE = Regex("""\s+""")
private val DASH = Regex("""\s[—–-]\s""")

/**
 * Комбіновані знаки наголосу, якими Вікіпедія розмічає перший рядок статті:
 * U+0300 (гравіс), U+0301 (акут), U+0341 (тоновий акут) і самостійний U+00B4.
 * Записані кодами навмисно — у тексті файлу вони були б невидимі.
 */
private val STRESS_MARKS = Regex("[\\u0300\\u0301\\u0341\\u00B4]")
