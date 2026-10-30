package local.phantom.companion.feature.stream.ui.report

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.component.GlassCard
import local.phantom.companion.core.design.component.GlassLevel
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomRadii
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import local.phantom.companion.feature.stream.R

/**
 * Картка-передача — те, що звіт лишає **в потоці чату**.
 *
 * ## Чому не сам звіт
 *
 * Восьмирозділовий документ у стрічці діалогу знищує діалог: щоб дійти до
 * наступної репліки PHANTOM, оператор мусить прогорнути чужий документ.
 * Тому в потоці лишається розписка: що це, за який період, три числа,
 * форма періоду — і одна дія.
 *
 * ## Чому саме ці елементи
 *
 * Заголовок відповідає «про що», період — «за коли», три числа — «чи варто
 * відкривати», спарклайн — «як воно рухалося». Це рівно та інформація, за
 * якою ухвалюється рішення відкривати документ; четверте число вже не
 * впливає на рішення, лише додає шуму.
 *
 * ## Чому картка виглядає інакше за решту віджетів
 *
 * Решта віджетів — це репліки. Ця — **обкладинка документа**, і має
 * читатися як обкладинка: кікер, велика назва, ряд чисел, лінія відрізу,
 * дія. `LivingCard` дав би їй тон звичайної картки і тим сховав би
 * головне — що за нею є ще вісім розділів.
 */
@Composable
fun ReportHandoffCard(
    doc: ReportDoc,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val sectionCount = doc.sections.size
    val blockCount = remember(doc) { doc.sections.sumOf { it.blocks.size } }

    GlassCard(
        level = GlassLevel.Card,
        modifier = modifier.fillMaxWidth().clickable(onClick = onOpen),
    ) {
        Column(modifier = Modifier.padding(ReportMetrics.panelPad)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(24.dp)
                        .clip(PhantomRadii.SmallShape)
                        .background(theme.primary.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = kindGlyph(doc.kind),
                        style = ReportType.caption,
                        color = theme.accentSharp,
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = doc.kicker.ifBlank { stringResource(R.string.kt_report_kicker) }.uppercase(),
                    style = ReportType.kicker,
                    color = theme.accentSharp,
                    modifier = Modifier.weight(1f),
                )
                if (sectionCount > 0) {
                    Text(
                        text = pluralStringResource(R.plurals.kt_report_sections, sectionCount, sectionCount),
                        style = ReportType.axis,
                        color = theme.inkSecondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text(text = doc.title, style = ReportType.title, color = theme.inkStrong)
            if (doc.period.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(text = doc.period, style = ReportType.axis, color = theme.inkSecondary)
            }

            if (doc.headline.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    doc.headline.take(MAX_HEADLINE_ON_CARD).forEach { stat ->
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stat.label.uppercase(),
                                style = ReportType.axis,
                                color = theme.inkSecondary,
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = stat.value,
                                    style = ReportType.statSmall,
                                    color = if (stat.tone == ReportTone.NEUTRAL) theme.inkStrong
                                    else toneColor(stat.tone),
                                )
                                if (stat.unit.isNotBlank()) {
                                    Spacer(Modifier.width(3.dp))
                                    Text(
                                        text = stat.unit,
                                        style = ReportType.axis,
                                        color = theme.inkSecondary,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (doc.spark.size >= 2) {
                Spacer(Modifier.height(14.dp))
                Sparkline(
                    values = doc.spark,
                    color = theme.primary,
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(0.5.dp).background(theme.glassBorder),
            )
            Spacer(Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.kt_report_open),
                    style = ReportType.blockTitle,
                    color = theme.accentSharp,
                )
                Spacer(Modifier.width(6.dp))
                Text(text = "→", style = ReportType.blockTitle, color = theme.accentSharp)
                Spacer(Modifier.weight(1f))
                // Вага документа — чесна підказка про те, у що оператор
                // зараз увійде; «відкрити» без неї обіцяє картку.
                Text(
                    text = pluralStringResource(R.plurals.kt_report_blocks, blockCount, blockCount),
                    style = ReportType.axis,
                    color = theme.inkSecondary,
                )
            }
        }
    }
}

/**
 * Картка для payload, з якого не вдалося зібрати документ.
 *
 * Показує причину, а не порожній прямокутник: «звіт не зібрався» — це
 * стан системи, про який оператор має право знати, інакше він вирішить,
 * що даних немає.
 */
@Composable
fun ReportBrokenCard(modifier: Modifier = Modifier) {
    val theme = LocalPhantomTheme.current
    GlassCard(level = GlassLevel.Card, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(ReportMetrics.panelPad)) {
            Text(text = stringResource(R.string.kt_report_kicker), style = ReportType.kicker, color = theme.inkSecondary)
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.kt_report_broken),
                style = ReportType.body,
                color = theme.ink,
            )
        }
    }
}

private const val MAX_HEADLINE_ON_CARD = 3
