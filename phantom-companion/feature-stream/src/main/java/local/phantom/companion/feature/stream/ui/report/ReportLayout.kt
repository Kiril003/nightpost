package local.phantom.companion.feature.stream.ui.report

/**
 * Модель верстки документа — **плоский список рядків, а не дерево**.
 *
 * ## Навіщо розпліскувати
 *
 * Документ природно дерево: звіт → розділи → блоки. Але `LazyColumn`
 * лінивий лише по своїх `item`-ах: якщо розділ — це один `item` із
 * колонкою всередині, то весь розділ (мапа + дві діаграми + таблиця)
 * складається одним кадром, щойно зʼявиться його перший піксель. Саме так
 * і виникає «один гігантський блокуючий рендер», якого бриф забороняє.
 *
 * Тому кожен блок — власний рядок `LazyColumn`. Тоді Compose сам композує
 * лише те, що видно, а [ReportHydration] додає другий рівень: важкий блок
 * спершу тримає скелет своєї висоти й малює вміст наступним кадром.
 *
 * ## Навіщо чиста функція
 *
 * Зі списку рядків рахується все, що показує шапка: який розділ зараз під
 * пальцем, скільки документа пройдено, який пункт змісту підсвітити. Це
 * арифметика, і вона має бути перевірена тестом, а не «здаватися
 * правильною» всередині `@Composable`.
 */
object ReportLayout {

    /** Один рядок повноекранної поверхні звіту. */
    sealed interface Row {
        /** Стабільний ключ рядка для `LazyColumn` (переживає рекомпозицію). */
        val key: String

        /** Шапка документа: кікер, назва, період, ліда. */
        data object Masthead : Row {
            override val key: String get() = "masthead"
        }

        /** Смуга ключових чисел одразу під шапкою — рендериться негайно. */
        data object Headline : Row {
            override val key: String get() = "headline"
        }

        /** Заголовок розділу. [index] — позиція в змісті. */
        data class SectionHead(val index: Int, val section: ReportSection) : Row {
            override val key: String get() = "sec:${section.id}:$index"
        }

        /** Блок усередині розділу. */
        data class BlockRow(
            val sectionIndex: Int,
            val block: ReportBlock,
        ) : Row {
            override val key: String get() = "blk:$sectionIndex:${block.key}"
        }

        /**
         * Чесний порожній стан. Окремий рядок, а не «шапка без чисел»:
         * відсутність даних — це повідомлення, і воно має власне місце в
         * документі, інакше порожній звіт виглядав би як зламаний.
         */
        data object Empty : Row {
            override val key: String get() = "empty"
        }

        /** Колофон: джерела, методологія, час генерації. */
        data object Colophon : Row {
            override val key: String get() = "colophon"
        }
    }

    /**
     * Розпліскує документ у рядки. Порядок фіксований і навмисний:
     * шапка → числа → (розділ → блоки)* → колофон. Перші два рядки —
     * легкі за визначенням, тому поверхня ніколи не відкривається порожньою.
     */
    fun flatten(doc: ReportDoc): List<Row> = buildList {
        add(Row.Masthead)
        if (doc.headline.isNotEmpty()) add(Row.Headline)
        if (doc.isEmpty) add(Row.Empty)
        doc.sections.forEachIndexed { i, section ->
            add(Row.SectionHead(i, section))
            section.blocks.forEach { add(Row.BlockRow(i, it)) }
        }
        if (doc.sources.isNotEmpty() || doc.methodology.isNotEmpty() ||
            doc.generatedAt.isNotBlank()
        ) {
            add(Row.Colophon)
        }
    }

    /** Пункт рейки змісту. */
    data class OutlineEntry(
        val index: Int,
        val id: String,
        val kicker: String,
        val title: String,
        /** Індекс рядка у [flatten] — куди скролити по тапу. */
        val rowIndex: Int,
        /** Скільки блоків усередині — підказка про вагу розділу. */
        val blockCount: Int,
    )

    /** Зміст документа. Порожній документ дає порожній зміст, не заглушку. */
    fun outline(doc: ReportDoc): List<OutlineEntry> {
        val rows = flatten(doc)
        return doc.sections.mapIndexed { i, section ->
            val rowIndex = rows.indexOfFirst { it is Row.SectionHead && it.index == i }
            OutlineEntry(
                index = i,
                id = section.id,
                kicker = section.kicker,
                title = section.title,
                rowIndex = if (rowIndex >= 0) rowIndex else 0,
                blockCount = section.blocks.size,
            )
        }
    }

    /**
     * Який розділ вважати «поточним» для липкої шапки.
     *
     * Правило — **останній заголовок, що вже пройшов повз**, а не
     * найближчий видимий. Інакше шапка перемикається на наступний розділ,
     * коли той лише визирнув знизу, і оператор читає підпис до тексту,
     * якого ще не бачить. `-1` = ще в шапці документа.
     */
    fun currentSection(rows: List<Row>, firstVisibleIndex: Int): Int {
        var current = -1
        for (i in 0..firstVisibleIndex.coerceAtMost(rows.lastIndex)) {
            val row = rows.getOrNull(i)
            if (row is Row.SectionHead) current = row.index
        }
        return current
    }

    /**
     * Прогрес читання у [0..1] по рядках, не по пікселях: піксельний
     * прогрес стрибав би на кожному важкому блоці, який щойно
     * гідратувався і змінив свою висоту.
     *
     * [visibleCount] враховано навмисно — коли останній рядок уже на
     * екрані, документ прочитано, навіть якщо скрол не в самому низу.
     */
    fun progress(total: Int, firstVisibleIndex: Int, visibleCount: Int): Float {
        if (total <= 1) return 1f
        val reached = (firstVisibleIndex + visibleCount).coerceIn(0, total)
        return (reached.toFloat() / total).coerceIn(0f, 1f)
    }

    /** Скільки важких блоків у документі — для діагностики та тестів. */
    fun heavyBlockCount(doc: ReportDoc): Int =
        doc.sections.sumOf { s -> s.blocks.count { it.weight == ReportWeight.HEAVY } }
}
