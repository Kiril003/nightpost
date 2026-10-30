package local.phantom.companion.feature.stream.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R

/**
 * Блоки звіту, що не малюються на Canvas: наратив, числа, таблиці, списки.
 *
 * ## Чому не «картка на кожен блок»
 *
 * Найпростіший спосіб зверстати двадцять блоків — покласти кожен у
 * `GlassCard`. Саме так і виходить «суп із карток»: двадцять однакових
 * прямокутників, кожен зі своєю рамкою, і жодної ієрархії — око не має за
 * що зачепитися, бо все однаково важливе.
 *
 * Тут навпаки: **рамка — це виняток, а не правило**. Панель отримують лише
 * ті блоки, які справді є вставленим обʼєктом (таблиця, діаграма, врізка).
 * Проза, числа й заголовки живуть просто на сторінці, як у друкованому
 * документі, і ритм задає типографіка, а не бордюри.
 *
 * ## Один диспетчер
 *
 * [ReportBlockView] — єдина точка, де тип блока перетворюється на верстку.
 * `when` над sealed-ієрархією вичерпний, тому новий блок неможливо додати
 * і забути відрендерити: компілятор не дасть.
 */
@Composable
fun ReportBlockView(
    block: ReportBlock,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ReportBlock.Prose -> ProseBlock(block, modifier)
        is ReportBlock.Insight -> InsightBlock(block, modifier)
        is ReportBlock.Verdict -> VerdictBlock(block, modifier)
        is ReportBlock.Footnotes -> FootnotesBlock(block, modifier)
        is ReportBlock.Methodology -> MethodologyBlock(block, modifier)
        is ReportBlock.KeyValues -> KeyValuesBlock(block, modifier)
        is ReportBlock.Kpis -> KpiGrid(block.items, modifier)
        is ReportBlock.BigStat -> BigStatBlock(block, modifier)
        is ReportBlock.SparkRow -> SparkRowBlock(block, modifier)
        is ReportBlock.LineSeries -> Panel(block.title, modifier) { LineSeriesChart(block) }
        is ReportBlock.SpeedProfile -> Panel(block.title, modifier) { SpeedProfileChart(block) }
        is ReportBlock.BarSeries -> Panel(block.title, modifier) { BarSeriesChart(block) }
        is ReportBlock.Donut -> Panel(block.title, modifier) { DonutChart(block) }
        is ReportBlock.Histogram -> Panel(block.title, modifier) { HistogramChart(block) }
        is ReportBlock.Waterfall -> Panel(block.title, modifier) { WaterfallChart(block) }
        is ReportBlock.HeatMatrix -> Panel(block.title, modifier) { HeatMatrixChart(block) }
        is ReportBlock.Gauges -> Panel(block.title, modifier) { GaugeCluster(block) }
        is ReportBlock.Tires -> Panel(block.title, modifier) { TireDiagram(block) }
        is ReportBlock.RouteTrace -> Panel(block.title, modifier) { RouteTraceChart(block) }
        is ReportBlock.Divergence -> Panel(block.title, modifier) { DivergenceChart(block) }
        is ReportBlock.DataTable -> DataTableBlock(block, modifier)
        is ReportBlock.Ranking -> RankingBlock(block, modifier)
        is ReportBlock.Comparison -> ComparisonBlock(block, modifier)
        is ReportBlock.Timeline -> TimelineBlock(block, modifier)
        is ReportBlock.Countdowns -> CountdownsBlock(block, modifier)
        is ReportBlock.AgentTrace -> AgentTraceBlock(block, modifier)
    }
}

/* ── Наратив ─────────────────────────────────────────────────────── */

@Composable
private fun ProseBlock(block: ReportBlock.Prose, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Text(text = block.text, style = ReportType.body, color = theme.ink, modifier = modifier)
}

/**
 * Врізка спостереження. Ліва бурштинова засічка замість рамки: вона
 * виводить блок із потоку тексту, не роблячи з нього ще одну картку.
 */
@Composable
private fun InsightBlock(block: ReportBlock.Insight, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    val accent = toneColor(block.tone)
    // IntrinsicSize.Min: засічка має бути рівно заввишки з текст врізки —
    // фіксована висота або обрізала б довгу думку, або висіла б хвостом
    // під короткою.
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(accent),
        )
        Column(modifier = Modifier.padding(start = 14.dp)) {
            if (block.kicker.isNotBlank()) {
                Text(
                    text = block.kicker.uppercase(),
                    style = ReportType.kicker,
                    color = accent,
                )
                Spacer(Modifier.height(6.dp))
            }
            Text(text = block.text, style = ReportType.body, color = theme.ink)
        }
    }
}

@Composable
private fun VerdictBlock(block: ReportBlock.Verdict, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PhantomRadii.LargeShape)
            .background(theme.glassPanel.copy(alpha = 0.5f))
            .padding(ReportMetrics.panelPad),
    ) {
        Text(text = block.text, style = ReportType.quote, color = theme.inkStrong)
        if (block.attribution.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = block.attribution.uppercase(),
                style = ReportType.kicker,
                color = toneColor(block.tone),
            )
        }
    }
}

@Composable
private fun FootnotesBlock(block: ReportBlock.Footnotes, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Column(modifier = modifier.fillMaxWidth()) {
        block.items.forEach { f ->
            Row(modifier = Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
                Text(
                    text = f.marker,
                    style = ReportType.statSmall,
                    color = theme.accentSharp,
                    modifier = Modifier.width(24.dp),
                )
                Column {
                    Text(text = f.text, style = ReportType.caption, color = theme.inkSecondary)
                    if (f.source.isNotBlank()) {
                        Text(text = f.source, style = ReportType.axis, color = theme.inkSecondary)
                    }
                }
            }
        }
    }
}

/**
 * Методологія. Стоїть окремим блоком навмисно: звіт, який не пояснює, як
 * порахував, — це думка з графіками. Оператор має право знати, що
 * «економія» рахувалася відносно ціни на найближчій, а не найдешевшій АЗС.
 */
@Composable
private fun MethodologyBlock(block: ReportBlock.Methodology, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PhantomRadii.MediumShape)
            .border(0.5.dp, theme.glassBorder, PhantomRadii.MediumShape)
            .padding(ReportMetrics.panelPad),
    ) {
        Text(
            text = block.title.ifBlank { stringResource(R.string.kt_report_how) }.uppercase(),
            style = ReportType.kicker,
            color = theme.inkSecondary,
        )
        Spacer(Modifier.height(10.dp))
        block.lines.forEach { line ->
            Row(modifier = Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                Text(text = "·", style = ReportType.body, color = theme.inkSecondary)
                Spacer(Modifier.width(8.dp))
                Text(text = line, style = ReportType.caption, color = theme.inkSecondary)
            }
        }
    }
}

@Composable
private fun KeyValuesBlock(block: ReportBlock.KeyValues, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (block.title.isNotBlank()) {
            Text(text = block.title.uppercase(), style = ReportType.kicker, color = theme.inkSecondary)
            Spacer(Modifier.height(10.dp))
        }
        block.rows.forEachIndexed { i, kv ->
            if (i > 0) HairLine()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = kv.key,
                    style = ReportType.caption,
                    color = theme.inkSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = kv.value,
                    style = ReportType.statSmall,
                    color = if (kv.tone == ReportTone.NEUTRAL) theme.ink else toneColor(kv.tone),
                )
            }
        }
    }
}

/* ── Числа ───────────────────────────────────────────────────────── */

/**
 * Сітка показників. Два в ряд — не з міркувань смаку: на телефоні третя
 * колонка залишає числу ~90dp, і 24sp моноширинного значення починає
 * переноситись, а перенесене число перестає бути числом.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KpiGrid(items: List<ReportStat>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        maxItemsInEachRow = 2,
    ) {
        items.forEach { stat ->
            StatCell(stat = stat, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatCell(stat: ReportStat, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = modifier
            .clip(PhantomRadii.MediumShape)
            .background(theme.glassPanel.copy(alpha = 0.45f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = stat.label.uppercase(),
            style = ReportType.kicker,
            color = theme.inkSecondary,
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                style = ReportType.stat,
                color = if (stat.tone == ReportTone.NEUTRAL) theme.inkStrong else toneColor(stat.tone),
            )
            if (stat.unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stat.unit,
                    style = ReportType.caption,
                    color = theme.inkSecondary,
                    modifier = Modifier.padding(bottom = 3.dp),
                )
            }
        }
        if (stat.delta.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stat.delta,
                style = ReportType.statSmall,
                color = deltaColor(theme, stat),
            )
        }
        if (stat.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(text = stat.note, style = ReportType.axis, color = theme.inkSecondary)
        }
    }
}

/**
 * Колір дельти. Без явного [ReportStat.deltaGood] лишається нейтральним —
 * вгадувати «плюс це добре» не можна: для витрати пального це погано.
 */
private fun deltaColor(theme: PhantomThemeSpec, stat: ReportStat): Color {
    val good = stat.deltaGood ?: return theme.inkSecondary
    val rising = stat.delta.trimStart().startsWith("+")
    return if (rising == good) theme.success else theme.coral
}

@Composable
private fun BigStatBlock(block: ReportBlock.BigStat, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    val stat = block.stat
    Column(modifier = modifier.fillMaxWidth()) {
        if (stat.label.isNotBlank()) {
            Text(text = stat.label.uppercase(), style = ReportType.kicker, color = theme.inkSecondary)
            Spacer(Modifier.height(10.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = stat.value,
                style = ReportType.statHero,
                color = if (stat.tone == ReportTone.NEUTRAL) theme.inkStrong else toneColor(stat.tone),
            )
            if (stat.unit.isNotBlank()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stat.unit,
                    style = ReportType.blockTitle,
                    color = theme.inkSecondary,
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
        }
        if (stat.delta.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(text = stat.delta, style = ReportType.statSmall, color = deltaColor(theme, stat))
        }
        if (block.caption.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(text = block.caption, style = ReportType.caption, color = theme.inkSecondary)
        }
    }
}

@Composable
private fun SparkRowBlock(block: ReportBlock.SparkRow, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        block.items.forEach { item ->
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.label.uppercase(),
                    style = ReportType.kicker,
                    color = theme.inkSecondary,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = item.value,
                    style = ReportType.statSmall,
                    color = if (item.tone == ReportTone.NEUTRAL) theme.ink else toneColor(item.tone),
                )
                Spacer(Modifier.height(6.dp))
                Sparkline(
                    values = item.points,
                    color = if (item.tone == ReportTone.NEUTRAL) theme.primary else toneColor(item.tone),
                    modifier = Modifier.fillMaxWidth().height(30.dp),
                )
            }
        }
    }
}

/* ── Таблиці та списки ───────────────────────────────────────────── */

/**
 * Щільна таблиця з сортуванням. Сортування — не прикраса: журнал заправок
 * читають то за датою, то за ціною, і змушувати оператора шукати рядок
 * очима означає віддати йому роботу, яку мав зробити звіт.
 */
@Composable
private fun DataTableBlock(block: ReportBlock.DataTable, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    var sortCol by remember(block.key) { mutableIntStateOf(-1) }
    var ascending by remember(block.key) { androidx.compose.runtime.mutableStateOf(false) }

    val rows = remember(block.key, sortCol, ascending) {
        ReportTableSort.sort(block.rows, sortCol, ascending, block.columns)
    }

    Panel(block.title, modifier) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                block.columns.forEachIndexed { i, col ->
                    Text(
                        text = col.label.uppercase(),
                        style = ReportType.kicker,
                        color = if (sortCol == i) theme.accentSharp else theme.inkSecondary,
                        textAlign = if (col.align == "end") TextAlign.End else TextAlign.Start,
                        modifier = Modifier
                            .width(cellWidth(i, block.columns.size))
                            .then(
                                if (block.sortable) {
                                    Modifier.clickable {
                                        if (sortCol == i) ascending = !ascending
                                        else { sortCol = i; ascending = false }
                                    }
                                } else Modifier,
                            ),
                    )
                }
            }
            rows.forEachIndexed { r, row ->
                if (r > 0) HairLine()
                Row(modifier = Modifier.padding(vertical = 7.dp)) {
                    block.columns.forEachIndexed { i, col ->
                        val numeric = col.numeric || col.align == "end"
                        Text(
                            text = row.getOrElse(i) { "" },
                            style = if (numeric) ReportType.statSmall else ReportType.cell,
                            color = theme.ink,
                            textAlign = if (numeric) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.width(cellWidth(i, block.columns.size)),
                        )
                    }
                }
            }
        }
        if (block.footer.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = block.footer, style = ReportType.caption, color = theme.inkSecondary)
        }
    }
}

/** Перша колонка ширша — у ній зазвичай назва, у решті числа. */
private fun cellWidth(index: Int, total: Int): androidx.compose.ui.unit.Dp =
    if (index == 0 && total > 1) 132.dp else 96.dp

@Composable
private fun RankingBlock(block: ReportBlock.Ranking, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    val ranked = remember(block.key) { ReportAggregate.ranked(block.entries) }
    Panel(block.title, modifier) {
        ranked.forEachIndexed { i, (entry, fraction) ->
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "%02d".format(i + 1),
                        style = ReportType.axis,
                        color = theme.inkSecondary,
                        modifier = Modifier.width(24.dp),
                    )
                    Text(
                        text = entry.label,
                        style = ReportType.cell,
                        color = theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = entry.display.ifBlank {
                            ReportFormat.withUnit(entry.value.toDouble(), block.unit, 1)
                        },
                        style = ReportType.statSmall,
                        color = theme.inkStrong,
                    )
                }
                Spacer(Modifier.height(6.dp))
                // Смуга частки від лідера — саме вона робить рейтинг рейтингом,
                // а не просто відсортованим списком.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(PhantomRadii.TightShape)
                        .background(theme.glassBorder),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(PhantomRadii.TightShape)
                            .background(theme.primary),
                    )
                }
                if (entry.note.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = entry.note, style = ReportType.axis, color = theme.inkSecondary)
                }
            }
        }
    }
}

@Composable
private fun ComparisonBlock(block: ReportBlock.Comparison, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Panel(block.title, modifier) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Spacer(Modifier.weight(1.2f))
            Text(
                text = block.leftLabel.uppercase(),
                style = ReportType.kicker,
                color = theme.inkSecondary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            // The right column is the emphasised side, and its VALUES already
            // say so in inkStrong — the header follows them, not the accent.
            Text(
                text = block.rightLabel.uppercase(),
                style = ReportType.kicker,
                color = theme.inkStrong,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        block.rows.forEachIndexed { i, row ->
            if (i > 0) HairLine()
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = row.label,
                    style = ReportType.cell,
                    color = theme.inkSecondary,
                    modifier = Modifier.weight(1.2f),
                )
                Text(
                    text = row.left,
                    style = ReportType.statSmall,
                    color = theme.inkSecondary,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = row.right,
                    style = ReportType.statSmall,
                    color = if (row.tone == ReportTone.NEUTRAL) theme.inkStrong else toneColor(row.tone),
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Хронологія. Вертикальна нитка з пінами: ключова подія отримує більший
 * пін і повний контраст, решта тьмяніє — інакше двадцять однакових крапок
 * не дають прочитати, коли саме все зламалося.
 */
@Composable
private fun TimelineBlock(block: ReportBlock.Timeline, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Panel(block.title, modifier) {
        block.events.forEachIndexed { i, e ->
            val accent = if (e.tone == ReportTone.NEUTRAL) theme.inkSecondary else toneColor(e.tone)
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = e.time,
                    style = ReportType.statSmall,
                    color = theme.inkSecondary,
                    modifier = Modifier.width(62.dp).padding(top = 2.dp),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(20.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (e.pivotal) 10.dp else 6.dp)
                            .clip(PhantomRadii.Pill)
                            .background(accent),
                    )
                    if (i < block.events.lastIndex) {
                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(if (e.detail.isBlank()) 26.dp else 44.dp)
                                .background(theme.glassBorder),
                        )
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 6.dp, bottom = 12.dp)) {
                    Text(
                        text = e.title,
                        style = ReportType.blockTitle,
                        color = if (e.pivotal) theme.inkStrong else theme.ink,
                    )
                    if (e.detail.isNotBlank()) {
                        Spacer(Modifier.height(3.dp))
                        Text(text = e.detail, style = ReportType.caption, color = theme.inkSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun CountdownsBlock(block: ReportBlock.Countdowns, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Panel(block.title, modifier) {
        block.items.forEach { c ->
            val used = (1f - (c.remaining / c.interval)).coerceIn(0f, 1f)
            // Понад 90 % інтервалу — це вже не «скоро», це прострочення на
            // підході; фарбуємо коралом, поки ще є час зреагувати.
            val color = when {
                used >= 0.9f -> theme.coral
                used >= 0.75f -> theme.primary
                else -> theme.success
            }
            Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = c.label,
                        style = ReportType.cell,
                        color = theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = ReportFormat.withUnit(c.remaining.toDouble(), c.unit, 0),
                        style = ReportType.statSmall,
                        color = color,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(PhantomRadii.TightShape)
                        .background(theme.glassBorder),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(used)
                            .height(4.dp)
                            .clip(PhantomRadii.TightShape)
                            .background(color),
                    )
                }
                if (c.due.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = c.due, style = ReportType.axis, color = theme.inkSecondary)
                }
            }
        }
    }
}

/**
 * Дерево дій агента. Відступ = глибина виклику, час і вартість — праворуч
 * моноширинним: аудиторський журнал читають колонкою, а не рядком.
 */
@Composable
private fun AgentTraceBlock(block: ReportBlock.AgentTrace, modifier: Modifier) {
    val theme = LocalPhantomTheme.current
    Panel(block.title, modifier) {
        block.nodes.forEach { n ->
            val statusColor = when (n.status.lowercase()) {
                "ok", "done" -> theme.success
                "retry", "warn" -> theme.primary
                "fail", "error" -> theme.coral
                else -> theme.inkSecondary
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = (n.depth.coerceIn(0, 4) * 14).dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(PhantomRadii.Pill)
                        .background(statusColor),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = n.tool, style = ReportType.statSmall, color = theme.ink)
                    if (n.detail.isNotBlank()) {
                        Text(text = n.detail, style = ReportType.axis, color = theme.inkSecondary)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = ReportFormat.millis(n.ms),
                        style = ReportType.axis,
                        color = theme.inkSecondary,
                    )
                    if (n.cost.isNotBlank()) {
                        Text(text = n.cost, style = ReportType.axis, color = theme.inkSecondary)
                    }
                }
            }
        }
    }
}

/* ── Спільні деталі ──────────────────────────────────────────────── */

/**
 * Панель для вставленого обʼєкта. Отримують її лише діаграми, таблиці й
 * дерева — тобто те, що справді є вкладеним обʼєктом на сторінці.
 */
@Composable
fun Panel(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(PhantomRadii.LargeShape)
            .background(theme.glassPanel.copy(alpha = 0.42f))
            .border(0.5.dp, theme.glassBorder, PhantomRadii.LargeShape)
            .padding(ReportMetrics.panelPad),
    ) {
        if (title.isNotBlank()) {
            Text(text = title, style = ReportType.blockTitle, color = theme.inkStrong)
            Spacer(Modifier.height(12.dp))
        }
        content()
    }
}

@Composable
private fun HairLine() {
    val theme = LocalPhantomTheme.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(0.5.dp)
            .background(theme.glassBorder),
    )
}

/**
 * Сортування таблиці — чиста функція, а не логіка всередині рендера.
 *
 * Числова колонка сортується як число, а не як рядок: інакше «12,4»
 * стає меншим за «9,1», і таблиця бреше рівно там, де оператор їй
 * найбільше довіряє.
 */
object ReportTableSort {

    fun sort(
        rows: List<List<String>>,
        column: Int,
        ascending: Boolean,
        columns: List<Column>,
    ): List<List<String>> {
        if (column < 0 || column >= columns.size) return rows
        val numeric = columns[column].numeric || columns[column].align == "end"
        val comparator = if (numeric) {
            compareBy<List<String>> { numberOf(it.getOrElse(column) { "" }) }
        } else {
            compareBy<List<String>> { it.getOrElse(column) { "" }.lowercase() }
        }
        val sorted = rows.sortedWith(comparator)
        return if (ascending) sorted else sorted.reversed()
    }

    /**
     * Витягує число з відформатованої комірки: «12 270 км» → 12270.0,
     * «−4,2 %» → −4.2. Комірка без числа їде в кінець (`-inf` при
     * спаданні), а не ламає порівняння.
     */
    fun numberOf(cell: String): Double {
        val cleaned = cell
            .replace(ReportFormat.THIN_NBSP.toString(), "")
            .replace(" ", "")
            .replace(" ", "")
            .replace(ReportFormat.MINUS, '-')
            .replace(',', '.')
        val match = Regex("-?\\d+(\\.\\d+)?").find(cleaned) ?: return Double.NEGATIVE_INFINITY
        return match.value.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY
    }
}
