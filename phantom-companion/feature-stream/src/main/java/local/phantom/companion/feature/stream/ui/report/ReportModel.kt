package local.phantom.companion.feature.stream.ui.report

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * # Модель звіту — типізований документ, а не «ще одна картка»
 *
 * ## Навіщо окрема модель
 *
 * У Stream уже є 42 типи віджетів, і чотири з них удають зі себе звіт:
 * `briefing`, `digest`, `trip-pack`, `stat-hero`. Усі чотири — плоскі:
 * заголовок, есенція, список рядків. Вони чудово працюють як **репліка в
 * діалозі**, і саме тому не можуть бути звітом: звіт — це не репліка, це
 * **документ**. У документа є розділи, наскрізна нумерація, зміст,
 * методологія, посилання на джерела і власна навігація. Спроба вкласти це
 * в бульбашку чату дає або обрізаний зміст, або стрічку на два екрани,
 * крізь яку неможливо повернутися до розмови.
 *
 * Тому модель тут навмисно дволанкова:
 *
 *  1. **Передача** ([ReportDoc.headline] + [ReportDoc.spark]) — те, що
 *     живе в потоці чату: назва, період, 2–3 числа, спарклайн. Читається
 *     за секунду, не ламає ритм діалогу.
 *  2. **Документ** ([ReportDoc.sections]) — те, що відкривається окремою
 *     повноекранною поверхнею з власним скролом і змістом.
 *
 * ## Чому блоки — sealed-ієрархія, а не HTML/Markdown
 *
 * Звіт має бути **перевіреним**: кожне число в ньому — це агрегат, який
 * можна протестувати ([ReportAggregate]), і формат, який можна пришпилити
 * ([ReportFormat]). Markdown-рядок цього не дає — він дає текст, який ШІ
 * вигадав і ніхто не перевірив. Типізований блок дає компілятору й тестам
 * шанс сказати «ні»: [Gauges] без діапазону не збереться, [HeatMatrix] з
 * рваною матрицею відсіється на розборі.
 *
 * ## Чому та сама модель — і дротовий формат
 *
 * Блоки серіалізуються напряму (`@SerialName` = дискримінатор `block`),
 * без окремого шару DTO. Дублювати 26 типів у дві паралельні ієрархії —
 * це 26 місць, де вони розійдуться. Розбір лишається мʼяким: невідомий
 * або побитий блок **випадає**, а не валить увесь звіт
 * (див. [ReportPayload.parse]).
 *
 * ## Чесність даних
 *
 * У цьому файлі немає жодного зразка «історії оператора». Звіт малює лише
 * те, що прийшло в payload; порожній звіт малює чесний порожній стан
 * ([ReportDoc.isEmpty]), а не правдоподібні вигадані цифри.
 */
@Serializable
data class ReportDoc(
    /** Стабільний ідентифікатор — ключ для стану гідратації та скролу. */
    val id: String = "",
    /** Родина звіту: керує гліфом та акцентом, не змінює верстку. */
    val kind: ReportKind = ReportKind.GENERIC,
    /** Мікрозаголовок над назвою: «ЗВІТ ПРО ПОЇЗДКУ», «ІНЦИДЕНТ». */
    val kicker: String = "",
    /** Назва документа. Єдиний рядок, який оператор запамʼятає. */
    val title: String = "",
    /** Період спостереження — те, за що документ відповідає. */
    val period: String = "",
    /** Ліда: 1–3 речення, які роблять решту документа необовʼязковою. */
    val lede: String = "",
    /** 2–3 числа для картки-передачі. Більше — і картка стає дашбордом. */
    val headline: List<ReportStat> = emptyList(),
    /** Спарклайн картки-передачі: сирі значення, нормалізує рендер. */
    val spark: List<Float> = emptyList(),
    val sections: List<ReportSection> = emptyList(),
    /** Звідки взято дані — читається у колофоні наприкінці документа. */
    val sources: List<String> = emptyList(),
    /** Як рахувалося. Звіт без методології — це думка, а не звіт. */
    val methodology: List<String> = emptyList(),
    /** Коли згенеровано, вже у людському вигляді (форматує постачальник). */
    @SerialName("generated_at")
    val generatedAt: String = "",
) {
    /**
     * Порожній звіт — не помилка. Це нормальний стан «даних ще немає»
     * (новий пристрій, період без поїздок, вимкнена телеметрія), і він
     * має бути видимим, а не замаскованим під нулі.
     */
    val isEmpty: Boolean
        get() = sections.none { it.blocks.isNotEmpty() } && headline.isEmpty()
}

/** Родина звіту. Впливає лише на гліф і мікрокопі, не на верстку. */
@Serializable
enum class ReportKind {
    @SerialName("trip") TRIP,
    @SerialName("period") PERIOD,
    @SerialName("fuel") FUEL,
    @SerialName("vehicle") VEHICLE,
    @SerialName("incident") INCIDENT,
    @SerialName("safety") SAFETY,
    @SerialName("motion") MOTION,
    @SerialName("agent") AGENT,
    @SerialName("voice") VOICE,
    @SerialName("places") PLACES,
    @SerialName("generic") GENERIC,
}

/**
 * Семантичний тон. Свідомо **окремий** від акценту теми: бурштин — це
 * бренд, а не «попередження». Коли обидва значення несе один колір,
 * оператор перестає бачити тривогу.
 */
@Serializable
enum class ReportTone {
    @SerialName("neutral") NEUTRAL,
    @SerialName("good") GOOD,
    @SerialName("warn") WARN,
    @SerialName("crit") CRIT,
    @SerialName("accent") ACCENT,
}

/**
 * Одне число з підписом. [delta] — це вже відформатований рядок («+12 %»,
 * «−1,4 л»), бо порівняння задає постачальник даних: тільки він знає, з
 * чим саме порівнював.
 *
 * [deltaGood] відвʼязує знак від оцінки. «+8 %» витрати пального — погано,
 * «+8 %» економії — добре; сам знак цього не знає.
 */
@Serializable
data class ReportStat(
    val label: String = "",
    val value: String = "",
    val unit: String = "",
    val delta: String = "",
    @SerialName("delta_good")
    val deltaGood: Boolean? = null,
    /** Другий рядок дрібним: контекст, база порівняння, застереження. */
    val note: String = "",
    val tone: ReportTone = ReportTone.NEUTRAL,
)

/**
 * Розділ документа. [id] — якір для рейки змісту; [kicker] — нумерація
 * або рубрика; [lede] — абзац, що вводить у розділ до першої діаграми.
 */
@Serializable
data class ReportSection(
    val id: String = "",
    val kicker: String = "",
    val title: String = "",
    val lede: String = "",
    val blocks: List<ReportBlock> = emptyList(),
)

/**
 * Вага блока для прогресивної гідратації.
 *
 * [LIGHT] малюється одразу разом із розділом — це текст і числа, вони
 * коштують кадру нічого. [HEAVY] (Canvas, таблиці, дерева) чекає, доки
 * рядок реально не зʼявиться на екрані, і до того часу тримає скелет
 * своєї точної висоти. Це і є «ніколи не блимає порожнім»: місце вже
 * зайняте, стрибка верстки не буде.
 */
enum class ReportWeight { LIGHT, HEAVY }

/**
 * Комплект блоків звіту. Кожен — окремий інформаційний жанр, а не
 * варіація «картки з текстом».
 */
@Serializable
sealed interface ReportBlock {
    /** Ключ для стану гідратації; має бути унікальним у межах розділу. */
    val key: String
    val weight: ReportWeight get() = ReportWeight.LIGHT
    /** Приблизна висота у dp — саме її тримає скелет до гідратації. */
    val reservedHeightDp: Int get() = 0

    /* ── Наратив ─────────────────────────────────────────────────── */

    /** Абзац читомого тексту. Основна тканина документа. */
    @Serializable
    @SerialName("prose")
    data class Prose(
        override val key: String = "prose",
        val text: String = "",
    ) : ReportBlock

    /** Врізка «PHANTOM помітив…» — спостереження, а не дані. */
    @Serializable
    @SerialName("insight")
    data class Insight(
        override val key: String = "insight",
        val kicker: String = "",
        val text: String = "",
        val tone: ReportTone = ReportTone.ACCENT,
    ) : ReportBlock

    /** Вердикт/цитата — одна велика фраза, що закриває розділ. */
    @Serializable
    @SerialName("verdict")
    data class Verdict(
        override val key: String = "verdict",
        val text: String = "",
        val attribution: String = "",
        val tone: ReportTone = ReportTone.NEUTRAL,
    ) : ReportBlock

    /** Виноски з джерелами. Маркер зіставляється з текстом розділу. */
    @Serializable
    @SerialName("footnotes")
    data class Footnotes(
        override val key: String = "footnotes",
        val items: List<Footnote> = emptyList(),
    ) : ReportBlock

    /** Як саме рахувалося те, що вище. */
    @Serializable
    @SerialName("methodology")
    data class Methodology(
        override val key: String = "methodology",
        val title: String = "",
        val lines: List<String> = emptyList(),
    ) : ReportBlock

    /** Двоколонковий реєстр «ключ → значення»: паспорт розділу. */
    @Serializable
    @SerialName("key-values")
    data class KeyValues(
        override val key: String = "key-values",
        val title: String = "",
        val rows: List<KeyValue> = emptyList(),
    ) : ReportBlock

    /* ── Числа ───────────────────────────────────────────────────── */

    /** Сітка показників — 2–6 штук. Далі це вже не сітка, а шум. */
    @Serializable
    @SerialName("kpis")
    data class Kpis(
        override val key: String = "kpis",
        val items: List<ReportStat> = emptyList(),
    ) : ReportBlock

    /** Одне число на всю ширину — редакційний акцент розділу. */
    @Serializable
    @SerialName("big-stat")
    data class BigStat(
        override val key: String = "big-stat",
        val stat: ReportStat = ReportStat(),
        val caption: String = "",
    ) : ReportBlock

    /** Ряд мінідіаграм: показник + його форма за період. */
    @Serializable
    @SerialName("spark-row")
    data class SparkRow(
        override val key: String = "spark-row",
        val items: List<SparkItem> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 84
    }

    /* ── Діаграми (Canvas) ───────────────────────────────────────── */

    /** Кілька рядів на одній осі: тренд цін, споживання, вартість км. */
    @Serializable
    @SerialName("line-series")
    data class LineSeries(
        override val key: String = "line-series",
        val title: String = "",
        val series: List<Series> = emptyList(),
        @SerialName("x_labels")
        val xLabels: List<String> = emptyList(),
        val unit: String = "",
        val markers: List<SeriesMarker> = emptyList(),
        /** Заливка під лінією. Для одного ряду — так, для пʼяти — ні. */
        val filled: Boolean = false,
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 208
    }

    /** Профіль швидкості: колір — стан щодо обмеження, шпильки — події. */
    @Serializable
    @SerialName("speed-profile")
    data class SpeedProfile(
        override val key: String = "speed-profile",
        val title: String = "",
        val samples: List<SpeedSample> = emptyList(),
        @SerialName("limit_kmh")
        val limitKmh: Float = 0f,
        val events: List<TraceEvent> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 224
    }

    /** Стовпці, за потреби складені — розклад витрат по категоріях. */
    @Serializable
    @SerialName("bar-series")
    data class BarSeries(
        override val key: String = "bar-series",
        val title: String = "",
        val groups: List<BarGroup> = emptyList(),
        @SerialName("segment_labels")
        val segmentLabels: List<String> = emptyList(),
        val unit: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 216
    }

    /** Кільце часток. Легенда обовʼязкова — кільце без неї нечитабельне. */
    @Serializable
    @SerialName("donut")
    data class Donut(
        override val key: String = "donut",
        val title: String = "",
        val slices: List<Slice> = emptyList(),
        val center: String = "",
        @SerialName("center_label")
        val centerLabel: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 196
    }

    /** Розподіл: де саме сидить маса значень, а не тільки середнє. */
    @Serializable
    @SerialName("histogram")
    data class Histogram(
        override val key: String = "histogram",
        val title: String = "",
        val values: List<Float> = emptyList(),
        val bins: Int = 12,
        val unit: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 188
    }

    /** Водоспад: з чого склалася підсумкова різниця. */
    @Serializable
    @SerialName("waterfall")
    data class Waterfall(
        override val key: String = "waterfall",
        val title: String = "",
        val steps: List<WaterfallStep> = emptyList(),
        val unit: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 212
    }

    /** Матриця година × день — теплова карта звички. */
    @Serializable
    @SerialName("heat-matrix")
    data class HeatMatrix(
        override val key: String = "heat-matrix",
        val title: String = "",
        @SerialName("row_labels")
        val rowLabels: List<String> = emptyList(),
        @SerialName("col_labels")
        val colLabels: List<String> = emptyList(),
        /** Рядки матриці; рвані рядки відсіює [ReportPayload.parse]. */
        val values: List<List<Float>> = emptyList(),
        val unit: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 224
    }

    /** Кластер шкал: двигун, охолодження, батарея — стан у діапазонах. */
    @Serializable
    @SerialName("gauges")
    data class Gauges(
        override val key: String = "gauges",
        val title: String = "",
        val items: List<Gauge> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 172
    }

    /** Схема чотирьох коліс (TPMS): тиск і температура на своїх місцях. */
    @Serializable
    @SerialName("tires")
    data class Tires(
        override val key: String = "tires",
        val title: String = "",
        val items: List<Tire> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 232
    }

    /** Слід маршруту, розфарбований швидкістю, з пінами подій. */
    @Serializable
    @SerialName("route-trace")
    data class RouteTrace(
        override val key: String = "route-trace",
        val title: String = "",
        val points: List<GeoPoint> = emptyList(),
        @SerialName("limit_kmh")
        val limitKmh: Float = 0f,
        val events: List<TraceEvent> = emptyList(),
        val from: String = "",
        val to: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 268
    }

    /**
     * Дві траси на одному полі — супутникове рішення проти мережевого.
     * Це головний доказовий кадр звіту про підміну GPS: розбіжність видно
     * не з числа, а з того, що сліди більше не одне й те саме.
     */
    @Serializable
    @SerialName("divergence")
    data class Divergence(
        override val key: String = "divergence",
        val title: String = "",
        @SerialName("track_a")
        val trackA: List<GeoPoint> = emptyList(),
        @SerialName("track_b")
        val trackB: List<GeoPoint> = emptyList(),
        @SerialName("label_a")
        val labelA: String = "",
        @SerialName("label_b")
        val labelB: String = "",
        val note: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 272
    }

    /* ── Таблиці та списки ───────────────────────────────────────── */

    /** Щільна таблиця даних із сортуванням по колонці. */
    @Serializable
    @SerialName("data-table")
    data class DataTable(
        override val key: String = "data-table",
        val title: String = "",
        val columns: List<Column> = emptyList(),
        val rows: List<List<String>> = emptyList(),
        val footer: String = "",
        val sortable: Boolean = true,
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 240
    }

    /** Рейтинг із часткою: перші рядки мають бути видно без зусиль. */
    @Serializable
    @SerialName("ranking")
    data class Ranking(
        override val key: String = "ranking",
        val title: String = "",
        val entries: List<RankEntry> = emptyList(),
        val unit: String = "",
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 200
    }

    /** Порівняння двох станів по спільних рядках: було / стало. */
    @Serializable
    @SerialName("comparison")
    data class Comparison(
        override val key: String = "comparison",
        val title: String = "",
        @SerialName("left_label")
        val leftLabel: String = "",
        @SerialName("right_label")
        val rightLabel: String = "",
        val rows: List<CompareRow> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 208
    }

    /** Хронологія з пінами. Для інциденту — з точністю до секунди. */
    @Serializable
    @SerialName("timeline")
    data class Timeline(
        override val key: String = "timeline",
        val title: String = "",
        val events: List<TimelineEvent> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 260
    }

    /** Зворотні відліки ТО: скільки лишилось до наступного обслуговування. */
    @Serializable
    @SerialName("countdowns")
    data class Countdowns(
        override val key: String = "countdowns",
        val title: String = "",
        val items: List<Countdown> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 196
    }

    /** Дерево дій агента: інструмент, час, вартість, результат. */
    @Serializable
    @SerialName("agent-trace")
    data class AgentTrace(
        override val key: String = "agent-trace",
        val title: String = "",
        val nodes: List<TraceNode> = emptyList(),
    ) : ReportBlock {
        override val weight: ReportWeight get() = ReportWeight.HEAVY
        override val reservedHeightDp: Int get() = 252
    }
}

/* ── Допоміжні структури блоків ──────────────────────────────────── */

@Serializable
data class Footnote(
    val marker: String = "",
    val text: String = "",
    val source: String = "",
)

@Serializable
data class KeyValue(
    val key: String = "",
    val value: String = "",
    val tone: ReportTone = ReportTone.NEUTRAL,
)

@Serializable
data class SparkItem(
    val label: String = "",
    val value: String = "",
    val points: List<Float> = emptyList(),
    val tone: ReportTone = ReportTone.NEUTRAL,
)

@Serializable
data class Series(
    val name: String = "",
    val points: List<Float> = emptyList(),
    val tone: ReportTone = ReportTone.NEUTRAL,
)

/** Позначка на осі X: подія, що пояснює злам ряду. */
@Serializable
data class SeriesMarker(
    val index: Int = 0,
    val label: String = "",
    val tone: ReportTone = ReportTone.WARN,
)

/**
 * Одна вибірка профілю швидкості. [t] — підпис моменту («08:26»),
 * потрібен лише для підписів осі; сама геометрія рахується за індексом,
 * бо телеметрія йде рівним кроком.
 */
@Serializable
data class SpeedSample(
    val t: String = "",
    val kmh: Float = 0f,
)

/** Подія на сліді/профілі: різке гальмування, розгін, зупинка. */
@Serializable
data class TraceEvent(
    /** Індекс у ряді вибірок або точок сліду. */
    val at: Int = 0,
    val label: String = "",
    val tone: ReportTone = ReportTone.WARN,
)

@Serializable
data class BarGroup(
    val label: String = "",
    /** Сегменти зверху вниз; один сегмент = звичайний стовпець. */
    val segments: List<Float> = emptyList(),
)

@Serializable
data class Slice(
    val label: String = "",
    val value: Float = 0f,
)

@Serializable
data class WaterfallStep(
    val label: String = "",
    val delta: Float = 0f,
    /** Підсумковий стовпець малюється від нуля, а не від попереднього. */
    val total: Boolean = false,
)

@Serializable
data class Gauge(
    val label: String = "",
    val value: Float = 0f,
    val min: Float = 0f,
    val max: Float = 100f,
    val unit: String = "",
    /** Нормальний коридор — усе поза ним читається як відхилення. */
    @SerialName("ok_from")
    val okFrom: Float = Float.NEGATIVE_INFINITY,
    @SerialName("ok_to")
    val okTo: Float = Float.POSITIVE_INFINITY,
)

/** Колесо: [pos] — `fl`/`fr`/`rl`/`rr`, решта — свідчення TPMS. */
@Serializable
data class Tire(
    val pos: String = "",
    val bar: Float = 0f,
    @SerialName("target_bar")
    val targetBar: Float = 0f,
    val temp: Float = 0f,
    val label: String = "",
)

/** Точка сліду. [kmh] < 0 означає «швидкість невідома». */
@Serializable
data class GeoPoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val kmh: Float = -1f,
)

@Serializable
data class Column(
    val label: String = "",
    /** `start` | `end`; числові колонки завжди по правому краю. */
    val align: String = "start",
    val numeric: Boolean = false,
)

@Serializable
data class RankEntry(
    val label: String = "",
    val value: Float = 0f,
    val display: String = "",
    val note: String = "",
)

@Serializable
data class CompareRow(
    val label: String = "",
    val left: String = "",
    val right: String = "",
    val tone: ReportTone = ReportTone.NEUTRAL,
)

@Serializable
data class TimelineEvent(
    val time: String = "",
    val title: String = "",
    val detail: String = "",
    val tone: ReportTone = ReportTone.NEUTRAL,
    /** Ключова подія малюється більшим піном і не тьмяніє. */
    val pivotal: Boolean = false,
)

@Serializable
data class Countdown(
    val label: String = "",
    /** Скільки лишилось, у власних одиницях (км, дні). */
    val remaining: Float = 0f,
    /** Повний інтервал обслуговування — знаменник прогресу. */
    val interval: Float = 1f,
    val unit: String = "",
    val due: String = "",
)

/** Вузол трасування агента; [depth] — рівень вкладеності у дереві. */
@Serializable
data class TraceNode(
    val depth: Int = 0,
    val tool: String = "",
    val detail: String = "",
    val ms: Long = 0,
    val status: String = "",
    val cost: String = "",
)
