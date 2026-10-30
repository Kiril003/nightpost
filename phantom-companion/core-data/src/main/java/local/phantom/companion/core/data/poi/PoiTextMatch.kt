package local.phantom.companion.core.data.poi

/**
 * Чи це те саме слово — вирішується тут, у Kotlin, а не в SQLite.
 *
 * FTS4 з `unicode61` уміє рівно одне: скласти регістр і порізати рядок на
 * токени. Цього замало для української, і замало саме там, де людина шукає:
 *
 *  - **апостроф.** «Кавʼярня» пишуть чотирма різними символами, а клавіатура
 *    телефона дає пʼятий. Токенізатор їх викидає — але [OfflinePoiSearch.rank]
 *    донедавна порівнював сирий `contains`, і рядок із «’» не рахувався збігом
 *    із запитом через «'». Місце знаходилось і тут же падало в кінець видачі.
 *  - **и/і/ї/й, е/є/ё/э, ь, ґ.** Це не одна літера в Unicode, але для пошуку
 *    це одна позиція: «Львівська» і «Львивска» — те саме місце.
 *  - **одна помилкова літера.** «Крещатик» замість «Хрещатик» — не помилка
 *    користувача, а інша орфографія тієї самої назви; на 90 км/год їх буде
 *    більше, ніж правильних слів.
 *  - **дві абетки.** У даних OSM назва буває латиницею («Lviv», «WOG»), а
 *    людина пише кирилицею — і навпаки.
 *
 * Усе це — чистий Kotlin без Android і без SQLite, тож кожне правило нижче є
 * звичайним JVM-тестом.
 *
 * ## МЕЖІ (за побудовою, не через недороблення)
 *
 *  - **немає морфології.** «аптеки», «аптекою», «до аптеки» не зводяться до
 *    «аптека». Працює лише збіг за ПОЧАТКОМ слова, тож «аптек» знайде, а
 *    «аптеці» — ні. Стемер української мови сюди не возять: він важчий за
 *    весь цей файл і помиляється на власних назвах, яких у довіднику більшість.
 *  - **немає синонімів.** «заправка» не знаходить «АЗС» ЦИМ файлом; за це
 *    відповідає [PoiVocabulary], який пише слова-синоніми в колонку
 *    `keywords` ще на запису. Тут вони лише зіставляються.
 *  - **немає пошуку за адресою як за адресою.** Вулиця й номер потрапляють у
 *    `keywords` ([PoiVocabulary]) звичайними словами; «Хрещатик 1» шукається
 *    як два слова, а не як структурована адреса. Геокодування «вулиця N,
 *    будинок M, місто K» тут немає й не планується.
 *  - **скелет двох абеток — грубий.** Він свідомо зливає х із к, ґ із г і
 *    зʼїдає «h», тож «Харків»≈«Karkiv», а «Гоголь»≈«Gogol». Це достатньо для
 *    географічних назв і НЕ достатньо для брендів, чию латинку вигадав
 *    маркетолог: «Розетка»/«Rozetka» зійдеться, «Цитрус»/«Citrus» — ні.
 *    Тому скелет має НИЖЧИЙ ранг за прямий збіг і ніколи не перебиває його у
 *    видачі — він рятує запит, а не виграє його.
 *  - **одна помилка, не дві.** Відстань редагування обмежена одиницею і лише
 *    для слів від пʼяти літер. Дві помилки — це вже інше слово, і рятувати
 *    його означало б показати людині випадкове місце з упевненим виглядом.
 */
object PoiTextMatch {

    /** Уся назва збіглася цілком. */
    const val EXACT = 0

    /** Слово в назві починається із запиту — «політех» у «Політехнічний». */
    const val WORD_PREFIX = 1

    /** Запит трапляється всередині назви, але не з початку слова. */
    const val CONTAINS = 2

    /** Зійшлися лише скелети двох абеток — «Lviv» і «Львів». */
    const val CROSS_SCRIPT = 3

    /** Одна літера різниці — «крещатик» проти «Хрещатик». */
    const val TYPO = 4

    /** Назва не збіглася; збіглося те, ЧИМ це місце є (колонка `keywords`). */
    const val KEYWORD_ONLY = 5

    /** Вирок про один рядок довідника. */
    data class Verdict(
        /** Найгірший ранг серед слів запиту — менше означає ближче до істини. */
        val rank: Int,
        /**
         * Чи є доказ у самій НАЗВІ. False означає «збіглося лише по суті
         * місця»: «аптека» знайшла «Подорожник». Це різні речі для ока.
         */
        val matchedName: Boolean,
    )

    /**
     * Згортання: усе, що для пошуку є однією літерою, стає однією літерою.
     *
     * Свідомо викидаються мʼякий і твердий знаки: «Львівська» і «Львівска» —
     * одне місце, і жоден водій не думає про знак, коли набирає назву.
     */
    fun fold(raw: String): String {
        val out = StringBuilder(raw.length)
        for (ch in raw.lowercase()) {
            when (ch) {
                // Пʼять способів написати апостроф плюс два схожі штрихи.
                '\'', '’', 'ʼ', '‘', '`', '´', '′' -> Unit
                // Знаки не несуть звуку і лише розводять однакові назви.
                'ь', 'ъ' -> Unit
                'ё', 'э', 'є' -> out.append('е')
                'ы', 'і', 'ї', 'й' -> out.append('и')
                'ґ' -> out.append('г')
                else -> if (ch.isLetterOrDigit()) out.append(ch) else out.append(' ')
            }
        }
        // Дефіс, коса, кома — усе вже стало пробілом; лишилося злити пробіли.
        return out.toString().trim().replace(WHITESPACE, " ")
    }

    /** Згорнуті слова рядка. Порожній рядок дає порожній список, не список із «». */
    fun words(raw: String): List<String> =
        fold(raw).split(' ').filter { it.isNotEmpty() }

    /**
     * Скелет для порівняння кирилиці з латиницею.
     *
     * Кирилиця транслітерується диграфами (ч→ch, щ→shch), латиниця зводиться
     * до тих самих літер, а потім з обох боків викидається «h» і схлопуються
     * подвоєння. Саме викидання «h» і робить диграфи взаємними: «ш»→sh→s і
     * англійське «sh»→s сходяться без жодного окремого правила.
     */
    fun skeleton(folded: String): String {
        val latin = StringBuilder(folded.length + 4)
        for (ch in folded) {
            val piece = CYRILLIC_TO_LATIN[ch]
            if (piece != null) latin.append(piece) else latin.append(ch)
        }
        val out = StringBuilder(latin.length)
        var i = 0
        while (i < latin.length) {
            val ch = latin[i]
            val mapped: Char = when {
                // «ch» з обох боків має дати те саме, що кирилична «ч».
                ch == 'c' && i + 1 < latin.length && latin[i + 1] == 'h' -> { i++; 'c' }
                ch == 'c' -> 'k'
                ch == 'g' -> 'h'
                ch == 'y' || ch == 'j' -> 'i'
                ch == 'w' -> 'v'
                ch == 'q' -> 'k'
                else -> ch
            }
            i++
            if (mapped == 'h') continue
            if (out.isNotEmpty() && out.last() == mapped) continue
            out.append(mapped)
        }
        return out.toString()
    }

    /**
     * Та сама назва латиницею — тією ж таблицею, що годує [skeleton].
     *
     * Потрібне НЕ для порівняння (для нього є скелет), а щоб скласти ЗАПИТ до
     * FTS: індекс телефона тримає сирі назви, тож кирилиця не дає жодного
     * кандидата серед латинських («Le Silpo», «WOG»), а найкращий зіставляч
     * світу не має чого зіставляти.
     */
    fun latinize(folded: String): String = buildString(folded.length + 4) {
        for (ch in folded) append(CYRILLIC_TO_LATIN[ch] ?: ch)
    }

    /**
     * Латиниця → кирилиця, диграфи від найдовшого.
     *
     * Дзеркало [latinize] і з тієї самої причини. Точність тут навмисно не
     * потрібна: «Silpo» дає «сілпо», а не «Сільпо», бо мʼякий знак нізвідки
     * взяти. Викликач бере від цього рядка лише КОРІНЬ, і далі порівнює
     * [skeleton]-ами — тобто ця функція здобуває кандидатів, а не вирок.
     */
    fun cyrillize(folded: String): String {
        val out = StringBuilder(folded.length)
        var i = 0
        while (i < folded.length) {
            var matched = false
            for (len in LATIN_DIGRAPH_MAX downTo 1) {
                if (i + len > folded.length) continue
                val piece = LATIN_TO_CYRILLIC[folded.substring(i, i + len)] ?: continue
                out.append(piece)
                i += len
                matched = true
                break
            }
            if (!matched) {
                out.append(folded[i])
                i++
            }
        }
        return out.toString()
    }

    /**
     * Зіставити один рядок довідника із запитом людини.
     *
     * Слова запиту зʼєднані через І: «окко азс» означає обидва, бо АБО
     * поховало б потрібну заправку під кожною, що просто зветься «АЗС».
     *
     * ВИНЯТОК — короткі числа. «Хрещатик 1» від людини, у чиїх даних немає
     * номера будинку, мусить дати «Хрещатик», а не порожнечу: показати вулицю
     * незрівнянно корисніше, ніж нічого. Тому число з однієї-двох цифр піднімає
     * місце у видачі, коли збігається, і НЕ викидає його, коли ні.
     *
     * Повертає null, коли доказів немає взагалі.
     */
    fun match(name: String, keywords: String, query: String): Verdict? {
        val queryWords = words(query)
        if (queryWords.isEmpty()) return null

        val nameFolded = fold(name)
        if (nameFolded == fold(query)) return Verdict(EXACT, matchedName = true)

        // Той самий точний збіг, але без номера будинку.
        //
        // «Хрещатик» давав EXACT, а «Хрещатик 1» — уже WORD_PREFIX: щойно
        // людина дописувала номер, сама вулиця падала в гірший ранг і могла
        // опинитись нижче випадкового «Хрещатицький провулок». Номер будинку
        // не зʼїдав вулицю (це вже було полагоджено), але псував їй місце в
        // списку — а для ока це те саме.
        //
        // Ранг тут вирішує ПОРЯДОК, тож він і є відповідь на скаргу «пошук не
        // спроможний ні на що»: знайти й показати шостим — майже те саме, що
        // не знайти.
        val hardQuery = queryWords.filterNot { isSoftNumber(it) }
        if (hardQuery.isNotEmpty() && hardQuery.joinToString(" ") == nameFolded) {
            return Verdict(EXACT, matchedName = true)
        }

        val nameWords = nameFolded.split(' ').filter { it.isNotEmpty() }
        val keywordWords = words(keywords)

        var worst = EXACT
        var nameEvidence = false
        var hardTokens = 0
        for (token in queryWords) {
            val soft = isSoftNumber(token)
            val rank = tokenRank(token, nameFolded, nameWords)
            if (rank != null) {
                nameEvidence = true
                hardTokens++
                if (rank > worst) worst = rank
                continue
            }
            if (keywordWords.any { it.startsWith(token) }) {
                hardTokens++
                // Число, знайдене серед слів місця (номер будинку), рядок
                // ПІДТВЕРДЖУЄ, а не описує — тож і ранг йому не псує. А от
                // «аптека», знайдена лише в keywords, — це саме опис, і рядок
                // чесно падає в найгірший ранг.
                if (!soft && KEYWORD_ONLY > worst) worst = KEYWORD_ONLY
                continue
            }
            // Мʼяке число не знайшлось — і не біда: «Хрещатик 1» мусить дати
            // «Хрещатик», а не порожнечу.
            if (soft) continue
            return null
        }
        // Запит із самих мʼяких чисел («1») нікого не описує.
        if (hardTokens == 0) return null
        return Verdict(worst, nameEvidence)
    }

    /** Чи є доказ у назві — те, що [PoiHit.matchedName] обіцяє оку. */
    fun matchedName(name: String, query: String): Boolean =
        match(name, keywords = "", query = query)?.matchedName == true

    private fun tokenRank(token: String, nameFolded: String, nameWords: List<String>): Int? {
        // Початок будь-якого слова — не лише першого. Саме цього бракувало:
        // «політех» мусить знаходити «Київський політехнічний інститут».
        if (nameWords.any { it.startsWith(token) }) return WORD_PREFIX
        if (nameFolded.contains(token)) return CONTAINS
        val skeleton = skeleton(token)
        if (skeleton.length >= MIN_SKELETON_LEN &&
            nameWords.any { skeleton(it).startsWith(skeleton) }
        ) {
            return CROSS_SCRIPT
        }
        if (token.length >= MIN_TYPO_LEN && nameWords.any { withinOneEdit(it, token) }) return TYPO
        return null
    }

    private fun isSoftNumber(token: String): Boolean =
        token.length <= MAX_SOFT_NUMBER_LEN && token.all { it.isDigit() }

    /**
     * Відстань редагування не більша за одиницю — заміна, вставка або
     * викидання однієї літери. Обмежено навмисно: повний Левенштейн на кожен
     * рядок довідника на кожну літеру запиту — це робота, якої водій не бачить,
     * а два виправлення вже дають чуже слово з упевненим виглядом.
     */
    fun withinOneEdit(a: String, b: String): Boolean {
        if (a == b) return true
        val longer = if (a.length >= b.length) a else b
        val shorter = if (a.length >= b.length) b else a
        if (longer.length - shorter.length > 1) return false
        var i = 0
        var j = 0
        var edited = false
        while (i < longer.length && j < shorter.length) {
            if (longer[i] == shorter[j]) {
                i++
                j++
                continue
            }
            if (edited) return false
            edited = true
            if (longer.length == shorter.length) {
                i++
                j++
            } else {
                i++
            }
        }
        return true
    }

    /** Коротший скелет — це вже не слово, а збіг випадкових літер. */
    private const val MIN_SKELETON_LEN = 3

    /** Нижче цієї довжини одна літера різниці — це інше слово, а не описка. */
    private const val MIN_TYPO_LEN = 5

    /** «1», «14» — номер будинку чи заправки; довше вже схоже на назву. */
    private const val MAX_SOFT_NUMBER_LEN = 2

    private val WHITESPACE = Regex("\\s+")

    private val CYRILLIC_TO_LATIN: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'д' to "d", 'е' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t",
        'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ю' to "iu", 'я' to "ia",
    )

    private val LATIN_TO_CYRILLIC: Map<String, String> = mapOf(
        "shch" to "щ", "sch" to "щ",
        "kh" to "х", "zh" to "ж", "ch" to "ч", "sh" to "ш", "ts" to "ц",
        "ya" to "я", "yu" to "ю", "ye" to "є", "yi" to "ї",
        "ia" to "я", "iu" to "ю", "ie" to "є",
        "a" to "а", "b" to "б", "v" to "в", "h" to "г", "g" to "ґ", "d" to "д",
        "e" to "е", "z" to "з", "i" to "і", "y" to "и", "k" to "к", "l" to "л",
        "m" to "м", "n" to "н", "o" to "о", "p" to "п", "r" to "р", "s" to "с",
        "t" to "т", "u" to "у", "f" to "ф", "c" to "ц", "j" to "й", "q" to "к",
        "w" to "в", "x" to "кс",
    )

    private val LATIN_DIGRAPH_MAX: Int = LATIN_TO_CYRILLIC.keys.maxOf { it.length }
}
