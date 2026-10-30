package local.phantom.companion.feature.stream.ui.report

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R

/**
 * Полотна звіту — **уся графіка малюється тут, руками, на Canvas**.
 *
 * ## Чому без бібліотеки
 *
 * Жодна графічна бібліотека не знає, що бурштин у PHANTOM означає систему,
 * а не попередження; що швидкість фарбується трьома смугами щодо
 * обмеження, а не градієнтом; що дві траси в одному полі — це доказ, а не
 * дві серії. Готова бібліотека дала б стандартні діаграми з чужою
 * типографікою й власною палітрою — і звіт перестав би виглядати як
 * документ PHANTOM. Canvas коштує більше коду і повертає повний контроль
 * над кожним пікселем сенсу.
 *
 * ## Розподіл праці
 *
 * Тут — **тільки геометрія й фарба**. Скільки саме дорівнює число і як
 * воно виглядає рядком — це [ReportAggregate] і [ReportFormat], і вони
 * покриті тестами. Полотно отримує вже нормалізовані величини.
 *
 * ## Підписи — поза полотном
 *
 * Осі, легенди й підписи верстаються звичайним `Text`, а не `drawText`.
 * Так вони успадковують масштаб шрифту оператора (ТВІД T4) і лишаються
 * доступними для читача екрана; текст, замкнений у Canvas, не є ні тим,
 * ні іншим.
 */

/* ── Спарклайн ───────────────────────────────────────────────────── */

/**
 * Мінідіаграма для картки-передачі та ряду показників. Без осей і
 * підписів: її робота — показати **форму**, а не значення.
 */
@Composable
fun Sparkline(
    values: List<Float>,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
) {
    Canvas(modifier = modifier) {
        val pts = ReportAggregate.sparkPoints(values, size.width, size.height * 0.86f)
        if (pts.size < 2) return@Canvas
        val yOffset = size.height * 0.07f

        val line = Path()
        pts.forEachIndexed { i, (x, y) ->
            if (i == 0) line.moveTo(x, y + yOffset) else line.lineTo(x, y + yOffset)
        }
        if (filled) {
            val area = Path().apply {
                addPath(line)
                lineTo(pts.last().first, size.height)
                lineTo(pts.first().first, size.height)
                close()
            }
            drawPath(
                path = area,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.30f), color.copy(alpha = 0f)),
                ),
            )
        }
        drawPath(line, color, style = Stroke(width = 2.dp.toPx()))
        // Остання точка — «де ми зараз»; без неї спарклайн читається як
        // історія без сьогодні.
        drawCircle(color, radius = 2.6.dp.toPx(), center = Offset(pts.last().first, pts.last().second + yOffset))
    }
}

/* ── Багаторядна лінія ───────────────────────────────────────────── */

@Composable
fun LineSeriesChart(
    block: ReportBlock.LineSeries,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val raw = block.series.map { it.points }
    val normalized = ReportAggregate.normalizeTogether(raw)
    val (lo, hi) = ReportAggregate.bounds(raw)

    Column(modifier = modifier) {
        ChartFrame(height = ReportMetrics.chartHeight) {
            gridLines(theme)
            normalized.forEachIndexed { si, points ->
                val color = seriesColorFor(theme, block.series.getOrNull(si)?.tone, si)
                drawSeries(points, color, filled = block.filled && normalized.size == 1)
            }
            // Маркери — вертикальні пунктири в точці зламу. Вони пояснюють
            // ряд, тому малюються поверх нього, а не під ним.
            block.markers.forEach { m ->
                val n = normalized.firstOrNull()?.size ?: return@forEach
                if (n < 2) return@forEach
                val x = (m.index.coerceIn(0, n - 1).toFloat() / (n - 1)) * size.width
                drawLine(
                    color = markerColorOf(theme, m.tone).copy(alpha = 0.6f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 6f)),
                )
            }
        }
        AxisScale(lo = lo, hi = hi, unit = block.unit)
        if (block.xLabels.isNotEmpty()) AxisLabels(block.xLabels)
        if (block.series.size > 1 || block.series.firstOrNull()?.name?.isNotBlank() == true) {
            Legend(block.series.mapIndexed { i, s -> s.name to seriesColorFor(theme, s.tone, i) })
        }
        if (block.markers.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            block.markers.forEach { m ->
                Text(
                    text = "${ReportFormat.MINUS} ${m.label}",
                    style = ReportType.axis,
                    color = markerColorOf(theme, m.tone),
                )
            }
        }
    }
}

/* ── Профіль швидкості ───────────────────────────────────────────── */

/**
 * Профіль швидкості — центральний кадр звіту про поїздку.
 *
 * Лінія фарбується посегментно за станом щодо обмеження, а не одним
 * кольором із міткою «середня швидкість». Середня швидкість приховує
 * рівно те, що варто бачити: де саме було перевищення й скільки воно
 * тривало.
 */
@Composable
fun SpeedProfileChart(
    block: ReportBlock.SpeedProfile,
    modifier: Modifier = Modifier,
) {
    val theme = LocalPhantomTheme.current
    val speeds = block.samples.map { it.kmh }
    val peak = (speeds.maxOrNull() ?: 0f).coerceAtLeast(block.limitKmh).coerceAtLeast(1f)

    Column(modifier = modifier) {
        ChartFrame(height = ReportMetrics.chartHeight) {
            gridLines(theme)
            val stepX = if (speeds.size > 1) size.width / (speeds.size - 1) else size.width
            fun yOf(v: Float) = size.height - (v / peak) * size.height

            // Заливка під профілем — маса, а не лише контур.
            val area = Path().apply {
                moveTo(0f, size.height)
                speeds.forEachIndexed { i, v -> lineTo(i * stepX, yOf(v)) }
                lineTo((speeds.size - 1) * stepX, size.height)
                close()
            }
            drawPath(
                area,
                Brush.verticalGradient(
                    listOf(theme.primary.copy(alpha = PhantomAlpha.Wash), theme.primary.copy(alpha = 0f)),
                ),
            )

            for (i in 0 until speeds.lastIndex) {
                val band = ReportAggregate.speedBand(
                    maxOf(speeds[i], speeds[i + 1]),
                    block.limitKmh,
                )
                drawLine(
                    color = speedBandColor(theme, band),
                    start = Offset(i * stepX, yOf(speeds[i])),
                    end = Offset((i + 1) * stepX, yOf(speeds[i + 1])),
                    strokeWidth = 2.5.dp.toPx(),
                )
            }

            if (block.limitKmh > 0f) {
                val y = yOf(block.limitKmh)
                drawLine(
                    color = theme.inkSecondary.copy(alpha = PhantomAlpha.Secondary),
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
            }

            block.events.forEach { e ->
                val i = e.at.coerceIn(0, speeds.lastIndex)
                val x = i * stepX
                drawLine(
                    color = markerColorOf(theme, e.tone).copy(alpha = 0.35f),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
                drawCircle(markerColorOf(theme, e.tone), 3.5.dp.toPx(), Offset(x, yOf(speeds[i])))
            }
        }
        AxisScale(lo = 0f, hi = peak, unit = stringResource(R.string.kt_report_kmh))
        val ticks = block.samples.filter { it.t.isNotBlank() }
        if (ticks.isNotEmpty()) {
            AxisLabels(pickEvenly(ticks.map { it.t }, MAX_AXIS_LABELS))
        }
        if (block.events.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            block.events.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(markerColorOf(theme, e.tone))
                    Spacer(Modifier.width(6.dp))
                    Text(text = e.label, style = ReportType.caption, color = theme.inkSecondary)
                }
            }
        }
    }
}

/* ── Стовпці (з накопиченням) ────────────────────────────────────── */

@Composable
fun BarSeriesChart(block: ReportBlock.BarSeries, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val totals = block.groups.map { g -> g.segments.sum() }
    val peak = (totals.maxOrNull() ?: 0f).coerceAtLeast(1f)

    Column(modifier = modifier) {
        ChartFrame(height = ReportMetrics.chartHeight) {
            gridLines(theme)
            val slot = size.width / block.groups.size
            val barW = slot * 0.62f
            block.groups.forEachIndexed { gi, group ->
                val cx = slot * gi + slot / 2f
                var yCursor = size.height
                group.segments.forEachIndexed { si, value ->
                    val h = (value / peak) * size.height
                    if (h <= 0f) return@forEachIndexed
                    drawRoundRect(
                        color = seriesColor(theme, si),
                        topLeft = Offset(cx - barW / 2f, yCursor - h),
                        size = Size(barW, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                    yCursor -= h + 1.dp.toPx()
                }
            }
        }
        AxisScale(lo = 0f, hi = peak, unit = block.unit)
        AxisLabels(block.groups.map { it.label })
        if (block.segmentLabels.isNotEmpty()) {
            Legend(block.segmentLabels.mapIndexed { i, l -> l to seriesColor(theme, i) })
        }
    }
}

/* ── Кільце часток ───────────────────────────────────────────────── */

@Composable
fun DonutChart(block: ReportBlock.Donut, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val fractions = ReportAggregate.shares(block.slices)

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier.size(132.dp),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
                val stroke = 20.dp.toPx()
                val inset = stroke / 2f
                var start = -90f
                fractions.forEachIndexed { i, f ->
                    val sweep = f * 360f
                    drawArc(
                        color = seriesColor(theme, i),
                        startAngle = start,
                        sweepAngle = sweep - SLICE_GAP_DEG,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke),
                    )
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (block.center.isNotBlank()) {
                    Text(block.center, style = ReportType.stat, color = theme.inkStrong)
                }
                if (block.centerLabel.isNotBlank()) {
                    Text(
                        block.centerLabel.uppercase(),
                        style = ReportType.kicker,
                        color = theme.inkSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            block.slices.forEachIndexed { i, s ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(seriesColor(theme, i))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = s.label,
                        style = ReportType.caption,
                        color = theme.ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = ReportFormat.share(fractions.getOrElse(i) { 0f }.toDouble()),
                        style = ReportType.statSmall,
                        color = theme.inkSecondary,
                    )
                }
            }
        }
    }
}

/* ── Розподіл ────────────────────────────────────────────────────── */

@Composable
fun HistogramChart(block: ReportBlock.Histogram, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val bins = ReportAggregate.histogram(block.values, block.bins)
    val peak = (bins.maxOfOrNull { it.count } ?: 0).coerceAtLeast(1)
    val median = ReportAggregate.median(block.values)

    Column(modifier = modifier) {
        ChartFrame(height = ReportMetrics.chartHeight) {
            gridLines(theme)
            val slot = size.width / bins.size
            bins.forEachIndexed { i, b ->
                val h = (b.count.toFloat() / peak) * size.height
                drawRoundRect(
                    color = theme.primary.copy(alpha = 0.75f),
                    topLeft = Offset(slot * i + slot * 0.12f, size.height - h),
                    size = Size(slot * 0.76f, h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            // Медіана — та єдина вертикаль, яку варто провести по гістограмі:
            // вона показує, де насправді сидить половина спостережень.
            val lo = bins.first().from
            val hi = bins.last().to
            if (hi - lo > 0f) {
                val x = ((median - lo) / (hi - lo)).toFloat().coerceIn(0f, 1f) * size.width
                drawLine(
                    color = theme.accentSharp,
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(),
                )
            }
        }
        AxisLabels(
            pickEvenly(
                bins.map { ReportFormat.compact(it.from.toDouble()) },
                MAX_AXIS_LABELS,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.kt_report_median, ReportFormat.withUnit(median, block.unit, 1)),
            style = ReportType.axis,
            color = theme.accentSharp,
        )
    }
}

/* ── Водоспад ────────────────────────────────────────────────────── */

@Composable
fun WaterfallChart(block: ReportBlock.Waterfall, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val runs = ReportAggregate.waterfall(block.steps)
    val lo = minOf(0f, runs.minOfOrNull { minOf(it.from, it.to) } ?: 0f)
    val hi = maxOf(0f, runs.maxOfOrNull { maxOf(it.from, it.to) } ?: 1f)
    val span = (hi - lo).coerceAtLeast(1e-3f)

    Column(modifier = modifier) {
        ChartFrame(height = ReportMetrics.chartHeight) {
            val zeroY = size.height - ((0f - lo) / span) * size.height
            drawLine(
                color = theme.glassBorder,
                start = Offset(0f, zeroY),
                end = Offset(size.width, zeroY),
                strokeWidth = 1.dp.toPx(),
            )
            val slot = size.width / runs.size
            runs.forEachIndexed { i, r ->
                val yFrom = size.height - ((r.from - lo) / span) * size.height
                val yTo = size.height - ((r.to - lo) / span) * size.height
                val top = minOf(yFrom, yTo)
                val h = abs(yTo - yFrom).coerceAtLeast(1.5.dp.toPx())
                val color = when {
                    r.isTotal -> theme.accentSharp
                    r.to >= r.from -> theme.coral
                    else -> theme.success
                }
                drawRoundRect(
                    color = color.copy(alpha = 0.85f),
                    topLeft = Offset(slot * i + slot * 0.16f, top),
                    size = Size(slot * 0.68f, h),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
                // Зʼєднувач до наступного стовпця: без нього водоспад
                // розпадається на неповʼязані прямокутники.
                if (i < runs.lastIndex && !runs[i + 1].isTotal) {
                    drawLine(
                        color = theme.inkSecondary.copy(alpha = PhantomAlpha.Recessed),
                        start = Offset(slot * i + slot * 0.84f, yTo),
                        end = Offset(slot * (i + 1) + slot * 0.16f, yTo),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
        }
        AxisLabels(runs.map { it.label })
        Legend(
            listOf(
                stringResource(R.string.kt_report_rise) to theme.coral,
                stringResource(R.string.kt_report_fall) to theme.success,
                stringResource(R.string.kt_report_total) to theme.accentSharp,
            ),
        )
    }
}

/* ── Теплова матриця ─────────────────────────────────────────────── */

@Composable
fun HeatMatrixChart(block: ReportBlock.HeatMatrix, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val heat = ReportAggregate.heat(block.values)
    val cols = heat.firstOrNull()?.size ?: 0

    Column(modifier = modifier) {
        if (block.colLabels.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().padding(start = ROW_LABEL_W)) {
                pickEvenly(block.colLabels, MAX_AXIS_LABELS).forEach { l ->
                    Text(
                        text = l,
                        style = ReportType.axis,
                        color = theme.inkSecondary,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        heat.forEachIndexed { r, row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.rowLabels.getOrElse(r) { "" },
                    style = ReportType.axis,
                    color = theme.inkSecondary,
                    modifier = Modifier.width(ROW_LABEL_W),
                )
                Canvas(modifier = Modifier.weight(1f).height(HEAT_CELL)) {
                    if (cols == 0) return@Canvas
                    val cw = size.width / cols
                    row.forEachIndexed { c, v ->
                        // Порожня комірка лишається видимою слабким тлом:
                        // «нуль поїздок о 4-й ранку» — це теж свідчення.
                        drawRoundRect(
                            color = if (v <= 0f) {
                                theme.glassBorder.copy(alpha = 0.5f)
                            } else {
                                theme.primary.copy(alpha = PhantomAlpha.Hairline + 0.78f * v)
                            },
                            topLeft = Offset(cw * c + 0.5f, 0f),
                            size = Size(cw - 1f, size.height),
                            cornerRadius = CornerRadius(1.5.dp.toPx()),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.kt_report_less), style = ReportType.axis, color = theme.inkSecondary)
            Spacer(Modifier.width(6.dp))
            Canvas(modifier = Modifier.width(72.dp).height(6.dp)) {
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(theme.primary.copy(alpha = PhantomAlpha.Hairline), theme.primary),
                    ),
                    size = size,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.kt_report_more), style = ReportType.axis, color = theme.inkSecondary)
            if (block.unit.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                Text(block.unit, style = ReportType.axis, color = theme.inkSecondary)
            }
        }
    }
}

/* ── Кластер шкал ────────────────────────────────────────────────── */

@Composable
fun GaugeCluster(block: ReportBlock.Gauges, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        block.items.take(MAX_GAUGES).forEach { g ->
            val fraction = ((g.value - g.min) / (g.max - g.min)).coerceIn(0f, 1f)
            val inRange = g.value >= g.okFrom && g.value <= g.okTo
            val color = if (inRange) theme.success else theme.coral
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(74.dp)) {
                        val stroke = 7.dp.toPx()
                        // 240° дуга: розрив унизу читається як «шкала», а
                        // повне коло — як «прогрес», і це різні обіцянки.
                        drawArc(
                            color = theme.glassBorder,
                            startAngle = 150f,
                            sweepAngle = 240f,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = color,
                            startAngle = 150f,
                            sweepAngle = 240f * fraction,
                            useCenter = false,
                            topLeft = Offset(stroke / 2f, stroke / 2f),
                            size = Size(size.width - stroke, size.height - stroke),
                            style = Stroke(width = stroke),
                        )
                    }
                    Text(
                        text = ReportFormat.number(g.value, if (abs(g.value) < 10) 1 else 0),
                        style = ReportType.statSmall,
                        color = theme.inkStrong,
                    )
                }
                Text(
                    text = g.label,
                    style = ReportType.axis,
                    color = theme.inkSecondary,
                )
                if (g.unit.isNotBlank()) {
                    Text(text = g.unit, style = ReportType.axis, color = theme.inkSecondary)
                }
            }
        }
    }
}

/* ── Схема коліс (TPMS) ──────────────────────────────────────────── */

@Composable
fun TireDiagram(block: ReportBlock.Tires, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val byPos = block.items.associateBy { it.pos.lowercase() }

    Box(modifier = modifier.fillMaxWidth().height(196.dp)) {
        // Силует кузова — контекст, який робить «FL» зрозумілим без легенди.
        Canvas(modifier = Modifier.align(Alignment.Center).width(96.dp).height(160.dp)) {
            drawRoundRect(
                color = theme.glassBorder,
                size = size,
                cornerRadius = CornerRadius(18.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawLine(
                color = theme.glassBorder,
                start = Offset(size.width * 0.2f, size.height * 0.32f),
                end = Offset(size.width * 0.8f, size.height * 0.32f),
                strokeWidth = 1.dp.toPx(),
            )
        }
        TireChip(byPos["fl"], Modifier.align(Alignment.TopStart))
        TireChip(byPos["fr"], Modifier.align(Alignment.TopEnd))
        TireChip(byPos["rl"], Modifier.align(Alignment.BottomStart))
        TireChip(byPos["rr"], Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun TireChip(tire: Tire?, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    if (tire == null) return
    // Відхилення понад 0,2 бар від цілі — межа, за якою знос і витрата
    // вже вимірні; менше — це похибка датчика і погода.
    val off = abs(tire.bar - tire.targetBar) > TIRE_TOLERANCE_BAR && tire.targetBar > 0f
    val color = if (off) theme.coral else theme.success
    Column(
        modifier = modifier
            .width(96.dp)
            .clip(PhantomRadii.MediumShape)
            .background(theme.glassPanel.copy(alpha = 0.6f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(tire.label.ifBlank { tire.pos.uppercase() }, style = ReportType.axis, color = theme.inkSecondary)
        Spacer(Modifier.height(2.dp))
        Text(
            text = stringResource(R.string.kt_report_bar, ReportFormat.number(tire.bar, 1)),
            style = ReportType.statSmall,
            color = color,
        )
        if (tire.temp > 0f) {
            Text(
                text = "${ReportFormat.number(tire.temp, 0)} °C",
                style = ReportType.axis,
                color = theme.inkSecondary,
            )
        }
    }
}

/* ── Слід маршруту ───────────────────────────────────────────────── */

@Composable
fun RouteTraceChart(block: ReportBlock.RouteTrace, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    Column(modifier = modifier) {
        MapFrame {
            val pts = ReportAggregate.project(block.points, size.width, size.height, MAP_PAD)
            if (pts.size < 2) return@MapFrame
            for (i in 0 until pts.lastIndex) {
                val kmh = block.points[i + 1].kmh
                val color = if (kmh < 0f) {
                    theme.primary
                } else {
                    speedBandColor(theme, ReportAggregate.speedBand(kmh, block.limitKmh))
                }
                drawLine(
                    color = color,
                    start = Offset(pts[i].first, pts[i].second),
                    end = Offset(pts[i + 1].first, pts[i + 1].second),
                    strokeWidth = 3.dp.toPx(),
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            // Початок — кільце, кінець — заливка: напрямок читається без стрілки.
            drawCircle(theme.ink, 5.dp.toPx(), Offset(pts.first().first, pts.first().second), style = Stroke(2.dp.toPx()))
            drawCircle(theme.accentSharp, 5.dp.toPx(), Offset(pts.last().first, pts.last().second))
            block.events.forEach { e ->
                val p = pts.getOrNull(e.at.coerceIn(0, pts.lastIndex)) ?: return@forEach
                drawCircle(markerColorOf(theme, e.tone), 4.dp.toPx(), Offset(p.first, p.second))
                drawCircle(
                    markerColorOf(theme, e.tone).copy(alpha = 0.25f),
                    9.dp.toPx(),
                    Offset(p.first, p.second),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(block.from, style = ReportType.caption, color = theme.ink)
            Text(
                text = "  →  ",
                style = ReportType.caption,
                color = theme.inkSecondary,
            )
            Text(block.to, style = ReportType.caption, color = theme.ink, modifier = Modifier.weight(1f))
        }
        Legend(
            listOf(
                stringResource(R.string.kt_report_within) to theme.success,
                stringResource(R.string.kt_report_near) to theme.primary,
                stringResource(R.string.kt_report_over) to theme.coral,
            ),
        )
    }
}

/* ── Розбіжність двох трас ───────────────────────────────────────── */

/**
 * Доказовий кадр звіту про підміну GPS.
 *
 * Обидві траси проєктуються **в одне спільне поле**, а не кожна у своє.
 * Окремі поля показали б два акуратні маршрути; спільне показує те, що
 * сталося насправді — рішення розійшлися, і одне з них більше не про це
 * місто.
 */
@Composable
fun DivergenceChart(block: ReportBlock.Divergence, modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    val gapKm = remember(block) {
        val a = block.trackA.lastOrNull()
        val b = block.trackB.lastOrNull()
        if (a != null && b != null) ReportAggregate.distanceKm(a, b) else 0.0
    }

    Column(modifier = modifier) {
        MapFrame {
            val all = block.trackA + block.trackB
            val projected = ReportAggregate.project(all, size.width, size.height, MAP_PAD)
            val a = projected.take(block.trackA.size)
            val b = projected.drop(block.trackA.size)

            drawTrack(a, theme.success, dashed = false)
            drawTrack(b, theme.coral, dashed = true)

            // Лінія розбіжності між останніми відомими позиціями — те саме
            // число, тільки видиме.
            val la = a.lastOrNull()
            val lb = b.lastOrNull()
            if (la != null && lb != null) {
                drawLine(
                    color = theme.inkSecondary.copy(alpha = 0.5f),
                    start = Offset(la.first, la.second),
                    end = Offset(lb.first, lb.second),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f, 5f)),
                )
                drawCircle(theme.success, 5.dp.toPx(), Offset(la.first, la.second))
                drawCircle(theme.coral, 5.dp.toPx(), Offset(lb.first, lb.second))
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(listOf(block.labelA to theme.success, block.labelB to theme.coral))
        if (gapKm > 0.0) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.kt_report_gap, ReportFormat.withUnit(gapKm, stringResource(R.string.kt_report_km), 0)),
                style = ReportType.statSmall,
                color = theme.coral,
            )
        }
        if (block.note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(block.note, style = ReportType.caption, color = theme.inkSecondary)
        }
    }
}

/* ── Спільні деталі полотен ──────────────────────────────────────── */

@Composable
private fun ChartFrame(
    height: androidx.compose.ui.unit.Dp,
    draw: DrawScope.() -> Unit,
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) { draw() }
}

@Composable
private fun MapFrame(draw: DrawScope.() -> Unit) {
    val theme = LocalPhantomTheme.current
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ReportMetrics.mapHeight)
            .clip(PhantomRadii.MediumShape)
            .background(theme.surfaceDeep.copy(alpha = 0.6f)),
    ) {
        // Ледь помітна сітка — дає відчуття поля, а не порожнього прямокутника.
        val step = size.width / 6f
        for (i in 1 until 6) {
            drawLine(
                color = theme.glassBorder.copy(alpha = 0.5f),
                start = Offset(step * i, 0f),
                end = Offset(step * i, size.height),
                strokeWidth = 0.5.dp.toPx(),
            )
        }
        draw()
    }
}

private fun DrawScope.gridLines(theme: PhantomThemeSpec) {
    for (i in 0..3) {
        val y = size.height * i / 3f
        drawLine(
            color = theme.glassBorder.copy(alpha = 0.6f),
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 0.5.dp.toPx(),
        )
    }
}

private fun DrawScope.drawSeries(points: List<Float>, color: Color, filled: Boolean) {
    if (points.size < 2) return
    val stepX = size.width / (points.size - 1)
    val path = Path()
    points.forEachIndexed { i, v ->
        val x = i * stepX
        val y = size.height - v * size.height
        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    if (filled) {
        val area = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(
            area,
            Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0f))),
        )
    }
    drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
}

private fun DrawScope.drawTrack(points: List<Pair<Float, Float>>, color: Color, dashed: Boolean) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 2.5.dp.toPx(),
            pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(8f, 6f)) else null,
        ),
    )
}

@Composable
private fun AxisScale(lo: Float, hi: Float, unit: String) {
    val theme = LocalPhantomTheme.current
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text = ReportFormat.withUnit(lo.toDouble(), unit, if (abs(lo) < 10f) 1 else 0),
            style = ReportType.axis,
            color = theme.inkSecondary,
        )
        Text(
            text = ReportFormat.withUnit(hi.toDouble(), unit, if (abs(hi) < 10f) 1 else 0),
            style = ReportType.axis,
            color = theme.inkSecondary,
        )
    }
}

@Composable
private fun AxisLabels(labels: List<String>) {
    val theme = LocalPhantomTheme.current
    if (labels.isEmpty()) return
    Spacer(Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        pickEvenly(labels, MAX_AXIS_LABELS).forEach { l ->
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(text = l, style = ReportType.axis, color = theme.inkSecondary, maxLines = 1)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(entries: List<Pair<String, Color>>) {
    val theme = LocalPhantomTheme.current
    val visible = entries.filter { it.first.isNotBlank() }
    if (visible.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        visible.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(color)
                Spacer(Modifier.width(5.dp))
                Text(text = label, style = ReportType.axis, color = theme.inkSecondary)
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Canvas(modifier = Modifier.size(7.dp)) {
        drawCircle(color, radius = size.minDimension / 2f)
    }
}

/**
 * Проріджує підписи осі до [max] штук. Вісь із 24 підписами по 9,5sp на
 * телефоні — це не вісь, це сіра смуга; краще шість читомих, ніж двадцять
 * чотири злиплих.
 */
fun pickEvenly(labels: List<String>, max: Int): List<String> {
    if (labels.size <= max || max <= 0) return labels
    val step = labels.size.toFloat() / max
    return (0 until max).map { labels[(it * step).roundToInt().coerceAtMost(labels.lastIndex)] }
}

private const val MAX_AXIS_LABELS = 6
private const val MAX_GAUGES = 4
private const val SLICE_GAP_DEG = 1.6f
private const val TIRE_TOLERANCE_BAR = 0.2f
private const val MAP_PAD = 18f
private val ROW_LABEL_W = 34.dp
private val HEAT_CELL = 16.dp
