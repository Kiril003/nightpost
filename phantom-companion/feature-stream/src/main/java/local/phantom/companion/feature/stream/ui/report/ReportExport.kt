package local.phantom.companion.feature.stream.ui.report

/**
 * Текстовий експорт документа.
 *
 * ## Чому це є, і чому це справжнє
 *
 * У брифі кнопка «поділитися» була декоративною. Декоративна кнопка —
 * найгірший вид брехні в інтерфейсі: вона обіцяє дію і не робить нічого,
 * і оператор дізнається про це тоді, коли справді потребував результату.
 *
 * Тому експорт тут реальний і навмисно найпростіший з можливих: документ
 * розкладається у плаский текст і кладеться в буфер обміну. Ніяких нових
 * залежностей, жодного PDF-рушія, жодного мережевого виклику — і при
 * цьому те, що можна вставити в месенджер, у нотатку або у звернення до
 * сервісу.
 *
 * ## Що навмисно не експортується
 *
 * Геометрія. Слід маршруту, теплова матриця й кільце часток
 * перетворюються на **опис** («слід маршруту, 412 точок»), а не на
 * псевдографіку. ASCII-діаграма в буфері обміну виглядає як дані, але
 * нею не є, і зіставити її з оригіналом неможливо.
 *
 * Чиста функція від документа — тому вона перевірена тестом, а не
 * «здається правильною».
 */
object ReportExport {

    /**
     * Єдина двері з документа назовні. Під замком повертає null — і жоден
     * рядок звіту не потрапляє в буфер обміну. Сам звіт при цьому лишається
     * на екрані: платимо за винесення, не за читання.
     */
    fun forClipboard(doc: ReportDoc, entitled: Boolean): String? =
        if (entitled) asText(doc) else null

    fun asText(doc: ReportDoc): String = buildString {
        if (doc.kicker.isNotBlank()) appendLine(doc.kicker.uppercase())
        appendLine(doc.title)
        if (doc.period.isNotBlank()) appendLine(doc.period)
        appendLine(RULE)
        if (doc.lede.isNotBlank()) {
            appendLine(doc.lede)
            appendLine()
        }

        if (doc.headline.isNotEmpty()) {
            doc.headline.forEach { s ->
                appendLine("${s.label}: ${s.value}${unitSuffix(s.unit)}${deltaSuffix(s.delta)}")
            }
            appendLine()
        }

        doc.sections.forEachIndexed { i, section ->
            appendLine("${i + 1}. ${section.title}")
            if (section.lede.isNotBlank()) appendLine(section.lede)
            section.blocks.forEach { block ->
                blockText(block).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            }
            appendLine()
        }

        if (doc.sources.isNotEmpty()) {
            appendLine("ДЖЕРЕЛА")
            doc.sources.forEach { appendLine("· $it") }
        }
        if (doc.methodology.isNotEmpty()) {
            appendLine("МЕТОДОЛОГІЯ")
            doc.methodology.forEach { appendLine("· $it") }
        }
        if (doc.generatedAt.isNotBlank()) appendLine("Згенеровано: ${doc.generatedAt}")
    }.trimEnd()

    /** Один блок у текст. Геометрія стає описом, дані — рядками. */
    fun blockText(block: ReportBlock): String = when (block) {
        is ReportBlock.Prose -> block.text
        is ReportBlock.Insight -> "[${block.kicker.ifBlank { "спостереження" }}] ${block.text}"
        is ReportBlock.Verdict -> "«${block.text}»" +
            if (block.attribution.isBlank()) "" else " — ${block.attribution}"
        is ReportBlock.Footnotes ->
            block.items.joinToString("\n") { "${it.marker} ${it.text}" }
        is ReportBlock.Methodology -> block.lines.joinToString("\n") { "· $it" }
        is ReportBlock.KeyValues -> block.rows.joinToString("\n") { "${it.key}: ${it.value}" }
        is ReportBlock.Kpis -> block.items.joinToString("\n") {
            "${it.label}: ${it.value}${unitSuffix(it.unit)}${deltaSuffix(it.delta)}"
        }
        is ReportBlock.BigStat ->
            "${block.stat.label}: ${block.stat.value}${unitSuffix(block.stat.unit)}"
        is ReportBlock.SparkRow -> block.items.joinToString("\n") { "${it.label}: ${it.value}" }
        is ReportBlock.DataTable -> tableText(block)
        is ReportBlock.Ranking -> ReportAggregate.ranked(block.entries)
            .mapIndexed { i, (e, _) ->
                "${i + 1}. ${e.label} — ${e.display.ifBlank {
                    ReportFormat.withUnit(e.value.toDouble(), block.unit, 1)
                }}"
            }
            .joinToString("\n")
        is ReportBlock.Comparison -> block.rows.joinToString("\n") {
            "${it.label}: ${it.left} → ${it.right}"
        }
        is ReportBlock.Timeline -> block.events.joinToString("\n") {
            "${it.time}  ${it.title}${if (it.detail.isBlank()) "" else " — ${it.detail}"}"
        }
        is ReportBlock.Countdowns -> block.items.joinToString("\n") {
            "${it.label}: лишилось ${ReportFormat.withUnit(it.remaining.toDouble(), it.unit, 0)}"
        }
        is ReportBlock.AgentTrace -> block.nodes.joinToString("\n") {
            "${"  ".repeat(it.depth.coerceIn(0, 4))}${it.tool} · ${ReportFormat.millis(it.ms)}" +
                if (it.status.isBlank()) "" else " · ${it.status}"
        }
        is ReportBlock.Donut -> {
            val shares = ReportAggregate.shares(block.slices)
            block.slices.mapIndexed { i, s ->
                "${s.label}: ${ReportFormat.share(shares.getOrElse(i) { 0f }.toDouble())}"
            }.joinToString("\n")
        }
        is ReportBlock.Gauges -> block.items.joinToString("\n") {
            "${it.label}: ${ReportFormat.withUnit(it.value.toDouble(), it.unit, 1)}"
        }
        is ReportBlock.Tires -> block.items.joinToString("\n") {
            "${it.label.ifBlank { it.pos.uppercase() }}: ${ReportFormat.number(it.bar, 1)} бар"
        }
        // Геометрія — описом, не псевдографікою (див. док класу).
        is ReportBlock.LineSeries -> describe(block.title, "ряди", block.series.size)
        is ReportBlock.SpeedProfile -> describe(block.title, "вибірки швидкості", block.samples.size)
        is ReportBlock.BarSeries -> describe(block.title, "групи", block.groups.size)
        is ReportBlock.Histogram -> describe(block.title, "спостереження", block.values.size)
        is ReportBlock.Waterfall -> block.steps.joinToString("\n") {
            "${it.label}: ${ReportFormat.signed(it.delta.toDouble(), block.unit)}"
        }
        is ReportBlock.HeatMatrix -> describe(block.title, "рядки матриці", block.values.size)
        is ReportBlock.RouteTrace -> describe(block.title, "точки сліду", block.points.size)
        is ReportBlock.Divergence -> describe(
            block.title,
            "точки двох трас",
            block.trackA.size + block.trackB.size,
        )
    }

    private fun tableText(block: ReportBlock.DataTable): String = buildString {
        appendLine(block.columns.joinToString(" | ") { it.label })
        block.rows.forEach { appendLine(it.joinToString(" | ")) }
        if (block.footer.isNotBlank()) append(block.footer)
    }.trimEnd()

    private fun describe(title: String, noun: String, count: Int): String =
        "[${title.ifBlank { "діаграма" }} — $noun: $count]"

    private fun unitSuffix(unit: String) =
        if (unit.isBlank()) "" else "${ReportFormat.THIN_NBSP}$unit"

    private fun deltaSuffix(delta: String) = if (delta.isBlank()) "" else " ($delta)"

    private const val RULE = "────────────────────"
}
