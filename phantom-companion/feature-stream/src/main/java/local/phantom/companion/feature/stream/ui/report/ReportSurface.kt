package local.phantom.companion.feature.stream.ui.report

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Lock
import local.phantom.companion.core.design.license.LicenseNotice
import local.phantom.companion.core.design.license.LocalEntitlements
import local.phantom.companion.core.license.LicenseFeature
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomRadii
import androidx.compose.ui.res.pluralStringResource

/**
 * Повноекранна поверхня документа.
 *
 * ## Чому `Dialog`, а не маршрут навігації
 *
 * Вимога брифу — «закриття повертає в **точну** позицію чату». Маршрут у
 * `PhantomNavGraph` знімає чат із композиції: `LazyListState` потоку
 * доводилося б зберігати, відновлювати й сподіватися, що висоти бульбашок
 * не змінилися за час читання. `Dialog` натомість накривається **поверх**
 * живої композиції: список чату не переміряється, не перескладається і не
 * рухається. Повернення в точну позицію тут не реалізоване — воно
 * структурно неможливе інакше.
 *
 * Побічна вигода: системна кнопка «назад» закриває звіт безкоштовно
 * (`dismissOnBackPress`), і ця поведінка не потребує жодних правок у графі
 * навігації — а отже, жодних правок у файлах, які тримає інша частина
 * системи.
 *
 * ## Чому один блок = один `item`
 *
 * Див. [ReportLayout]: розділ, покладений в один `item`, склався б цілком
 * у першому ж кадрі своєї появи. Плаский список рядків віддає лінивість
 * `LazyColumn`, а [HydrateOnVisible] додає другий рівень для важких блоків.
 *
 * ## Порядок появи
 *
 * Шапка й ключові числа — легкі рядки, вони стоять першими й малюються
 * миттєво. Тому поверхня ніколи не відкривається порожньою: до першого
 * скролу оператор уже читає титул, період і три числа.
 */
@Composable
fun ReportSurface(
    doc: ReportDoc,
    onClose: () -> Unit,
) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            // Документ читають скролом; випадковий дотик у поле не має
            // зачиняти вісім розділів.
            dismissOnClickOutside = false,
        ),
    ) {
        ReportSurfaceContent(doc = doc, onClose = onClose)
    }
}

@Composable
private fun ReportSurfaceContent(doc: ReportDoc, onClose: () -> Unit) {
    val theme = LocalPhantomTheme.current
    val reducedMotion = rememberReducedMotion()
    val hydration = rememberReportHydration(doc.id)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val exportEntitled = LocalEntitlements.current.has(LicenseFeature.ExportReports)

    val rows = remember(doc) { ReportLayout.flatten(doc) }
    val outline = remember(doc) { ReportLayout.outline(doc) }

    var tocOpen by remember { mutableStateOf(false) }
    var exported by remember { mutableStateOf(false) }
    var exportBlocked by remember { mutableStateOf(false) }

    // derivedStateOf — щоб шапка перескладалася на зміну розділу, а не на
    // кожен піксель скролу вісімсотрядкового документа.
    val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    val visibleCount by remember {
        derivedStateOf { listState.layoutInfo.visibleItemsInfo.size }
    }
    val currentSection by remember(rows) {
        derivedStateOf { ReportLayout.currentSection(rows, firstVisible) }
    }
    val progress by remember(rows) {
        derivedStateOf { ReportLayout.progress(rows.size, firstVisible, visibleCount) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(theme.surfaceBase),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = ReportMetrics.pageEdge,
                end = ReportMetrics.pageEdge,
                top = ReportMetrics.headerHeight + 32.dp,
                bottom = 96.dp,
            ),
        ) {
            items(count = rows.size, key = { rows[it].key }) { index ->
                ReportRowView(
                    row = rows[index],
                    doc = doc,
                    hydration = hydration,
                    reducedMotion = reducedMotion,
                )
            }
        }

        StickyReportHeader(
            doc = doc,
            sectionTitle = outline.getOrNull(currentSection)?.title.orEmpty(),
            sectionOrdinal = currentSection + 1,
            sectionTotal = outline.size,
            progress = progress,
            reducedMotion = reducedMotion,
            tocOpen = tocOpen,
            exported = exported,
            onToc = { tocOpen = !tocOpen },
            exportLocked = !exportEntitled,
            exportBlocked = exportBlocked,
            onExport = {
                val text = ReportExport.forClipboard(doc, exportEntitled)
                // Замкнена кнопка мусить пояснити себе на місці. Мовчазне
                // «нічого не сталося» читається як поламка застосунку.
                if (text == null) {
                    exportBlocked = true
                } else {
                    clipboard.setText(AnnotatedString(text))
                    exported = true
                }
            },
            onClose = onClose,
        )

        AnimatedVisibility(
            visible = tocOpen,
            enter = if (reducedMotion) EnterTransition.None else PhantomMotion.chamberEnter,
            exit = if (reducedMotion) ExitTransition.None else PhantomMotion.chamberExit,
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            TocRail(
                entries = outline,
                current = currentSection,
                onPick = { entry ->
                    tocOpen = false
                    scope.launch {
                        if (reducedMotion) listState.scrollToItem(entry.rowIndex)
                        else listState.animateScrollToItem(entry.rowIndex)
                    }
                },
                onDismiss = { tocOpen = false },
            )
        }
    }
}

/** Один рядок документа. Важкі блоки проходять через гідратацію. */
@Composable
private fun ReportRowView(
    row: ReportLayout.Row,
    doc: ReportDoc,
    hydration: ReportHydrationState,
    reducedMotion: Boolean,
) {
    when (row) {
        ReportLayout.Row.Masthead -> Masthead(doc)
        ReportLayout.Row.Headline -> {
            Spacer(Modifier.height(ReportMetrics.blockGap))
            KpiGrid(items = doc.headline)
        }
        ReportLayout.Row.Empty -> EmptyReportState()
        is ReportLayout.Row.SectionHead -> SectionHeader(row.index, row.section)
        is ReportLayout.Row.BlockRow -> {
            Spacer(Modifier.height(ReportMetrics.blockGap))
            if (row.block.weight == ReportWeight.HEAVY) {
                HydrateOnVisible(
                    key = row.key,
                    reservedHeightDp = row.block.reservedHeightDp,
                    state = hydration,
                    reducedMotion = reducedMotion,
                ) {
                    ReportBlockView(row.block)
                }
            } else {
                ReportBlockView(row.block)
            }
        }
        ReportLayout.Row.Colophon -> Colophon(doc)
    }
}

/* ── Шапка документа ─────────────────────────────────────────────── */

@Composable
private fun Masthead(doc: ReportDoc) {
    val theme = LocalPhantomTheme.current
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = kindGlyph(doc.kind),
                style = ReportType.blockTitle,
                color = theme.accentSharp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = doc.kicker.uppercase(),
                style = ReportType.kicker,
                color = theme.accentSharp,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(text = doc.title, style = ReportType.hero, color = theme.inkStrong)
        if (doc.period.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = doc.period, style = ReportType.statSmall, color = theme.inkSecondary)
        }
        if (doc.lede.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(text = doc.lede, style = ReportType.lede, color = theme.ink)
        }
    }
}

/**
 * Заголовок розділу. Нумерація моноширинним ліворуч від назви — саме вона
 * перетворює послідовність екранів на документ: оператор бачить, що це
 * «3 з 8», а не чергова картка.
 */
@Composable
private fun SectionHeader(index: Int, section: ReportSection) {
    val theme = LocalPhantomTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = ReportMetrics.sectionGap)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(theme.primary.copy(alpha = 0.6f), theme.glassBorder),
                    ),
                ),
        )
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Not the accent: the masthead above already spent the document's
            // one amber moment, and the rule line here carries it as a fill.
            Text(
                text = "%02d".format(index + 1),
                style = ReportType.kicker,
                color = theme.ink,
            )
            if (section.kicker.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Text(
                    text = section.kicker.uppercase(),
                    style = ReportType.kicker,
                    color = theme.inkSecondary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = section.title, style = ReportType.sectionTitle, color = theme.inkStrong)
        if (section.lede.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            Text(text = section.lede, style = ReportType.body, color = theme.inkSecondary)
        }
    }
}

/** Колофон: звідки дані, як рахували, коли зібрано. */
@Composable
private fun Colophon(doc: ReportDoc) {
    val theme = LocalPhantomTheme.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = ReportMetrics.sectionGap)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(0.5.dp).background(theme.glassBorder),
        )
        Spacer(Modifier.height(16.dp))
        if (doc.sources.isNotEmpty()) {
            Text(text = stringResource(R.string.kt_report_sources), style = ReportType.kicker, color = theme.inkSecondary)
            Spacer(Modifier.height(8.dp))
            doc.sources.forEach {
                Text(text = "· $it", style = ReportType.caption, color = theme.inkSecondary)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (doc.methodology.isNotEmpty()) {
            Text(text = stringResource(R.string.kt_report_method_head), style = ReportType.kicker, color = theme.inkSecondary)
            Spacer(Modifier.height(8.dp))
            doc.methodology.forEach {
                Text(text = "· $it", style = ReportType.caption, color = theme.inkSecondary)
            }
            Spacer(Modifier.height(16.dp))
        }
        if (doc.generatedAt.isNotBlank()) {
            Text(
                text = stringResource(R.string.kt_report_generated, doc.generatedAt),
                style = ReportType.axis,
                color = theme.inkSecondary,
            )
        }
    }
}

/**
 * Чесний порожній стан.
 *
 * Звіт без даних не має права показувати нулі: «0 км · 0 ₴» неможливо
 * відрізнити від «телеметрію вимкнено», і оператор зробить хибний
 * висновок про власний тиждень. Тому — прямий текст про причину.
 */
@Composable
fun EmptyReportState(modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = ReportMetrics.sectionGap)
            .clip(PhantomRadii.LargeShape)
            .border(0.5.dp, theme.glassBorder, PhantomRadii.LargeShape)
            .padding(ReportMetrics.panelPad),
    ) {
        Text(text = stringResource(R.string.kt_report_no_data), style = ReportType.kicker, color = theme.inkSecondary)
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.kt_report_no_data_body),
            style = ReportType.body,
            color = theme.ink,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.kt_report_no_zeros),
            style = ReportType.caption,
            color = theme.inkSecondary,
        )
    }
}

/* ── Липка шапка поверхні ────────────────────────────────────────── */

@Composable
private fun StickyReportHeader(
    doc: ReportDoc,
    sectionTitle: String,
    sectionOrdinal: Int,
    sectionTotal: Int,
    progress: Float,
    reducedMotion: Boolean,
    tocOpen: Boolean,
    exported: Boolean,
    exportLocked: Boolean,
    exportBlocked: Boolean,
    onToc: () -> Unit,
    onExport: () -> Unit,
    onClose: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val shown by animateFloatAsState(
        targetValue = progress,
        animationSpec = MotionTokens.snapSpringFloat,
        label = "report-progress",
    )
    val bar = if (reducedMotion) progress else shown

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(theme.surfaceBase, theme.surfaceBase.copy(alpha = 0.92f)),
                ),
            )
            .statusBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReportMetrics.headerHeight)
                .padding(horizontal = ReportMetrics.pageEdge),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (sectionTitle.isBlank()) doc.title else sectionTitle,
                    style = ReportType.title,
                    color = theme.inkStrong,
                    maxLines = 1,
                )
                Text(
                    text = if (sectionTotal == 0) doc.period
                    else "${sectionOrdinal.coerceAtLeast(1)} / $sectionTotal · ${doc.period}",
                    style = ReportType.axis,
                    color = theme.inkSecondary,
                    maxLines = 1,
                )
            }
            HeaderButton(
                icon = if (exportLocked) Icons.Outlined.Lock else Icons.Outlined.IosShare,
                label = when {
                    exportLocked -> stringResource(R.string.report_export_locked)
                    exported -> stringResource(R.string.report_export_done)
                    else -> stringResource(R.string.report_export)
                },
                highlighted = exported,
                onClick = onExport,
            )
            Spacer(Modifier.width(4.dp))
            HeaderButton(
                icon = Icons.Outlined.ListAlt,
                label = stringResource(R.string.kt_report_toc),
                highlighted = tocOpen,
                onClick = onToc,
            )
            Spacer(Modifier.width(4.dp))
            HeaderButton(
                icon = Icons.Outlined.Close,
                label = stringResource(R.string.kt_report_close),
                highlighted = false,
                onClick = onClose,
            )
        }
        if (exportBlocked) {
            LicenseNotice(
                headline = stringResource(R.string.report_gate_headline),
                body = stringResource(R.string.report_gate_body),
                modifier = Modifier.padding(
                    horizontal = ReportMetrics.pageEdge,
                    vertical = 8.dp,
                ),
            )
        }
        // Смуга прогресу читання — єдина шкала на поверхні; вона каже
        // «скільки лишилось», що для восьмирозділового документа важливіше
        // за будь-яку іншу індикацію.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(ReportMetrics.progressBar)
                .background(theme.glassBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(bar.coerceIn(0f, 1f))
                    .height(ReportMetrics.progressBar)
                    .background(theme.primary),
            )
        }
    }
}

@Composable
private fun HeaderButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    highlighted: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    // Tap area 48dp, tinted tile stays 36dp: clickable moves to the outer
    // box so the visible tile never grows past its own .size(36.dp).
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(PhantomRadii.SmallShape)
                .background(if (highlighted) theme.primary.copy(alpha = 0.16f) else theme.glassPanel.copy(alpha = PhantomAlpha.Recessed)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) theme.primary else theme.inkSecondary,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/* ── Рейка змісту ────────────────────────────────────────────────── */

/**
 * Зміст як **рейка**, а не як окремий екран.
 *
 * Окремий екран змісту розриває читання: оператор виходить із документа,
 * вибирає розділ, повертається — і губить, де був. Рейка накривається
 * збоку, лишає документ на місці й зникає одразу після вибору.
 */
@Composable
private fun TocRail(
    entries: List<ReportLayout.OutlineEntry>,
    current: Int,
    onPick: (ReportLayout.OutlineEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .padding(top = ReportMetrics.headerHeight, end = 12.dp)
            .width(ReportMetrics.railWidth)
            .heightIn(max = ReportMetrics.railMaxHeight)
            .clip(PhantomRadii.XLargeShape)
            .background(theme.glassPanel)
            .border(0.5.dp, theme.glassBorder, PhantomRadii.XLargeShape)
            .navigationBarsPadding()
            .padding(vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.kt_report_toc_head),
                style = ReportType.kicker,
                color = theme.inkSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "×",
                style = ReportType.title,
                color = theme.inkSecondary,
                modifier = Modifier.clickable(onClick = onDismiss),
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            entries.forEach { e ->
                val active = e.index == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPick(e) }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        text = "%02d".format(e.index + 1),
                        style = ReportType.axis,
                        color = if (active) theme.accentSharp else theme.inkSecondary,
                        modifier = Modifier.width(26.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = e.title,
                            style = ReportType.cell,
                            color = if (active) theme.inkStrong else theme.ink,
                        )
                        if (e.kicker.isNotBlank()) {
                            Text(
                                text = e.kicker.uppercase(),
                                style = ReportType.axis,
                                color = theme.inkSecondary,
                            )
                        }
                    }
                    Text(
                        text = e.blockCount.toString(),
                        style = ReportType.axis,
                        color = theme.inkSecondary,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.Start,
        ) {
            Text(
                text = pluralStringResource(R.plurals.kt_report_sections, entries.size, entries.size),
                style = ReportType.axis,
                color = theme.inkSecondary,
            )
        }
    }
}
