package local.phantom.companion.feature.stream.ui.report

import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Розбір payload звіту — **мʼякий за побудовою**.
 *
 * ## Чому не `decodeFromString<ReportDoc>()` одним рядком
 *
 * Звіт складає модель. Модель помиляється: вигадує тип блока, якого немає,
 * шле рвану матрицю в теплову карту, забуває поле. Строгий розбір sealed-
 * ієрархії на будь-якій із цих дрібниць кидає `SerializationException` і
 * оператор бачить… нічого. Втратити восьмисторінковий документ через один
 * зіпсований блок — неприйнятна ціна.
 *
 * Тому розбір іде **поблочно**: кожен блок декодується окремо, і невдача
 * коштує рівно один блок. Розділ без жодного вцілілого блока випадає весь;
 * документ без жодного вцілілого розділу лишається з шапкою і чесним
 * порожнім станом. Жоден шар не показує вигаданих чисел замість втрачених.
 *
 * ## Дискримінатор
 *
 * Блоки розрізняються полем `block` («kpis», «speed-profile», …), а не
 * стандартним `type`: `type` у payload віджета вже зайнятий самим типом
 * віджета, і два різні `type` в одному дереві — гарантована плутанина на
 * боці моделі.
 *
 * ## Санітизація
 *
 * Розбір не лише декодує, а й **вибраковує безглузде**: матриця з рваними
 * рядками, кільце з відʼємними частками, шкала з `min >= max` — це не дані,
 * це шум, який вміє намалюватись і збрехати. Такий блок випадає так само,
 * як і той, що не розібрався.
 */
object ReportPayload {

    /**
     * Окремий інстанс Json: інший дискримінатор (`block`), інша політика
     * невідомих ключів. Спільний інстанс віджетів чіпати не можна — на
     * ньому тримається розбір решти 42 типів.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
        classDiscriminator = "block"
    }

    /** Every `block` value the parser accepts, straight off the serializer. */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    fun blockDiscriminators(): Set<String> =
        ReportBlock.serializer().descriptor
            .getElementDescriptor(1)
            .elementNames
            .toSet()

    /**
     * Розбирає payload віджета `report` у документ.
     *
     * Ніколи не кидає: цілком нерозбірливий payload дає порожній
     * [ReportDoc], який рендер покаже чесним порожнім станом.
     */
    fun parse(raw: String): ReportDoc {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ReportDoc()

        val sections = (root["sections"] as? JsonArray)
            .orEmpty()
            .mapNotNull { parseSection(it as? JsonObject ?: return@mapNotNull null) }

        return ReportDoc(
            id = root.str("id"),
            kind = parseKind(root.str("kind")),
            kicker = root.str("kicker"),
            title = root.str("title"),
            period = root.str("period"),
            lede = root.str("lede"),
            headline = (root["headline"] as? JsonArray).orEmpty()
                .mapNotNull {
                    runCatching { json.decodeFromJsonElement(ReportStat.serializer(), it) }
                        .getOrNull()
                }
                .take(MAX_HEADLINE),
            spark = (root["spark"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull() },
            sections = sections,
            sources = root.strList("sources"),
            methodology = root.strList("methodology"),
            generatedAt = root.str("generated_at"),
        )
    }

    /**
     * Розділ без жодного вцілілого блока випадає — навіть із назвою.
     * Заголовок, під яким нічого немає, гірший за відсутній розділ: він
     * обіцяє дані, яких документ не має, і зміст рахує його як повноцінний.
     * Виняток — розділ із лідою: суто текстовий вступ має право на життя.
     */
    private fun parseSection(obj: JsonObject): ReportSection? {
        val blocks = parseBlocks(obj["blocks"] as? JsonArray)
        val head = obj.str("title")
        val lede = obj.str("lede")
        if (blocks.isEmpty() && lede.isBlank()) return null
        return ReportSection(
            id = obj.str("id").ifBlank { slug(head) },
            kicker = obj.str("kicker"),
            title = head,
            lede = lede,
            blocks = blocks,
        )
    }

    /**
     * Поблочний розбір: кожен елемент окремо, помилка коштує один блок.
     * Ключі блоків додатково розводяться суфіксом, бо стан гідратації
     * адресується саме ключем — два «kpis» в одному розділі злилися б в
     * один скелет.
     */
    fun parseBlocks(arr: JsonArray?): List<ReportBlock> {
        if (arr == null) return emptyList()
        val used = mutableSetOf<String>()
        return arr.mapIndexedNotNull { i, el ->
            val decoded = runCatching { json.decodeFromJsonElement(ReportBlock.serializer(), el) }
                .getOrNull() ?: return@mapIndexedNotNull null
            val sane = sanitize(decoded) ?: return@mapIndexedNotNull null
            reKey(sane, uniqueKey(sane.key, i, used))
        }
    }

    private fun uniqueKey(base: String, index: Int, used: MutableSet<String>): String {
        val candidate = if (used.add(base)) base else "$base#$index"
        used.add(candidate)
        return candidate
    }

    /**
     * Вибраковування блоків, які вміють намалюватись, але не мають сенсу.
     * `null` = блок відкидається.
     */
    fun sanitize(block: ReportBlock): ReportBlock? = when (block) {
        is ReportBlock.Prose -> block.takeIf { it.text.isNotBlank() }
        is ReportBlock.Insight -> block.takeIf { it.text.isNotBlank() }
        is ReportBlock.Verdict -> block.takeIf { it.text.isNotBlank() }
        is ReportBlock.Footnotes -> block.takeIf { it.items.isNotEmpty() }
        is ReportBlock.Methodology -> block.takeIf { it.lines.isNotEmpty() }
        is ReportBlock.KeyValues -> block.takeIf { it.rows.isNotEmpty() }
        is ReportBlock.Kpis -> block.takeIf { it.items.isNotEmpty() }
            ?.copy(items = block.items.take(MAX_KPIS))
        is ReportBlock.BigStat -> block.takeIf { it.stat.value.isNotBlank() }
        is ReportBlock.SparkRow -> block
            .copy(items = block.items.filter { it.points.size >= 2 })
            .takeIf { it.items.isNotEmpty() }
        is ReportBlock.LineSeries -> block
            .copy(series = block.series.filter { it.points.size >= 2 })
            .takeIf { it.series.isNotEmpty() }
        is ReportBlock.SpeedProfile -> block.takeIf { it.samples.size >= 2 }
        is ReportBlock.BarSeries -> block
            .copy(groups = block.groups.filter { it.segments.isNotEmpty() })
            .takeIf { it.groups.isNotEmpty() }
        is ReportBlock.Donut -> block
            .copy(slices = block.slices.filter { it.value > 0f })
            .takeIf { it.slices.isNotEmpty() }
        is ReportBlock.Histogram -> block.takeIf { it.values.size >= 2 }
        is ReportBlock.Waterfall -> block.takeIf { it.steps.isNotEmpty() }
        // Рвана матриця — найпідступніший випадок: вона малюється, просто
        // бреше про те, де саме гаряче. Вимагаємо прямокутник.
        is ReportBlock.HeatMatrix -> block.takeIf { b ->
            b.values.isNotEmpty() &&
                b.values.all { it.size == b.values.first().size && it.isNotEmpty() }
        }
        is ReportBlock.Gauges -> block
            .copy(items = block.items.filter { it.max > it.min })
            .takeIf { it.items.isNotEmpty() }
        is ReportBlock.Tires -> block
            .copy(items = block.items.filter { it.pos.lowercase() in TIRE_POSITIONS })
            .takeIf { it.items.isNotEmpty() }
        is ReportBlock.RouteTrace -> block.takeIf { it.points.size >= 2 }
        is ReportBlock.Divergence -> block.takeIf {
            it.trackA.size >= 2 && it.trackB.size >= 2
        }
        // Рядок, довший за шапку, зʼїхав би з колонок — рівняємо по шапці.
        is ReportBlock.DataTable -> block.takeIf { it.columns.isNotEmpty() && it.rows.isNotEmpty() }
            ?.copy(rows = block.rows.map { row -> row.take(block.columns.size) })
        is ReportBlock.Ranking -> block.takeIf { it.entries.isNotEmpty() }
        is ReportBlock.Comparison -> block.takeIf { it.rows.isNotEmpty() }
        is ReportBlock.Timeline -> block.takeIf { it.events.isNotEmpty() }
        is ReportBlock.Countdowns -> block
            .copy(items = block.items.filter { it.interval > 0f })
            .takeIf { it.items.isNotEmpty() }
        is ReportBlock.AgentTrace -> block.takeIf { it.nodes.isNotEmpty() }
    }

    /** Перекладає санітизований блок на унікальний ключ гідратації. */
    private fun reKey(block: ReportBlock, key: String): ReportBlock =
        if (block.key == key) block else when (block) {
            is ReportBlock.Prose -> block.copy(key = key)
            is ReportBlock.Insight -> block.copy(key = key)
            is ReportBlock.Verdict -> block.copy(key = key)
            is ReportBlock.Footnotes -> block.copy(key = key)
            is ReportBlock.Methodology -> block.copy(key = key)
            is ReportBlock.KeyValues -> block.copy(key = key)
            is ReportBlock.Kpis -> block.copy(key = key)
            is ReportBlock.BigStat -> block.copy(key = key)
            is ReportBlock.SparkRow -> block.copy(key = key)
            is ReportBlock.LineSeries -> block.copy(key = key)
            is ReportBlock.SpeedProfile -> block.copy(key = key)
            is ReportBlock.BarSeries -> block.copy(key = key)
            is ReportBlock.Donut -> block.copy(key = key)
            is ReportBlock.Histogram -> block.copy(key = key)
            is ReportBlock.Waterfall -> block.copy(key = key)
            is ReportBlock.HeatMatrix -> block.copy(key = key)
            is ReportBlock.Gauges -> block.copy(key = key)
            is ReportBlock.Tires -> block.copy(key = key)
            is ReportBlock.RouteTrace -> block.copy(key = key)
            is ReportBlock.Divergence -> block.copy(key = key)
            is ReportBlock.DataTable -> block.copy(key = key)
            is ReportBlock.Ranking -> block.copy(key = key)
            is ReportBlock.Comparison -> block.copy(key = key)
            is ReportBlock.Timeline -> block.copy(key = key)
            is ReportBlock.Countdowns -> block.copy(key = key)
            is ReportBlock.AgentTrace -> block.copy(key = key)
        }

    private fun parseKind(raw: String): ReportKind =
        ReportKind.entries.firstOrNull { it.wire == raw.trim().lowercase() } ?: ReportKind.GENERIC

    /** Транслітерований якір для розділу без явного `id`. */
    fun slug(title: String): String {
        val cleaned = title.lowercase().map { ch ->
            when {
                ch.isLetterOrDigit() -> ch
                else -> '-'
            }
        }.joinToString("")
        return cleaned.trim('-').replace(Regex("-+"), "-").ifBlank { "section" }
    }

    private const val MAX_HEADLINE = 3
    private const val MAX_KPIS = 6
    private val TIRE_POSITIONS = setOf("fl", "fr", "rl", "rr")

    /* ── Дрібні читачі JSON ──────────────────────────────────────── */

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.strList(key: String): List<String> =
        (this[key] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
}

/** Дротове імʼя родини — те саме, що в `@SerialName`, але читане з коду. */
val ReportKind.wire: String
    get() = when (this) {
        ReportKind.TRIP -> "trip"
        ReportKind.PERIOD -> "period"
        ReportKind.FUEL -> "fuel"
        ReportKind.VEHICLE -> "vehicle"
        ReportKind.INCIDENT -> "incident"
        ReportKind.SAFETY -> "safety"
        ReportKind.MOTION -> "motion"
        ReportKind.AGENT -> "agent"
        ReportKind.VOICE -> "voice"
        ReportKind.PLACES -> "places"
        ReportKind.GENERIC -> "generic"
    }
