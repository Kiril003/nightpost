package local.phantom.companion.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.text.format.DateFormat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomSpace

/** The detents the operator chooses between. 0 = «назавжди» (never delete). */
val RETENTION_DETENTS = listOf(7, 30, 90, 365, 0)

/** The finite detents a shortening can land on — the ones with a delete count. */
val RETENTION_FINITE_DETENTS = RETENTION_DETENTS.filter { it > 0 }

/**
 * What is stored at rest, sampled together so the preview count and its date
 * share one clock. [perDetentDelete] is `countOlderThan(cutoff)` for each finite
 * detent — the exact rows the sweep at that term would remove.
 */
data class RetentionUiState(
    val totalItems: Int,
    val oldestMs: Long?,
    val pinnedCount: Int,
    val approxBytes: Long,
    val computedAtMs: Long,
    val perDetentDelete: Map<Int, Int>,
)

@Composable
internal fun RetentionBody(
    days: Int,
    stats: RetentionUiState?,
    onApplyInstant: (Int) -> Unit,
    onConfirmShorten: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = LocalPhantomColors.current
    var pending by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(days) { pending = null }
    val labels = RETENTION_DETENTS.associateWith { detentLabel(it) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.settings_retention_title),
                style = PhantomText.body,
                color = c.text,
                modifier = Modifier.weight(1f).padding(end = PhantomSpace.s3),
            )
            Text(
                text = retentionValueLabel(pending ?: days),
                style = PhantomText.callout,
                color = c.text3,
            )
        }
        Spacer(Modifier.height(PhantomSpace.s3))
        SettingsSegmented(
            items = RETENTION_DETENTS,
            selected = pending ?: days,
            label = { labels[it].orEmpty() },
            onSelect = { target ->
                when {
                    target == days -> pending = null
                    isShortening(days, target) -> {
                        val toDelete = stats?.perDetentDelete?.get(target)
                        if (toDelete == null || toDelete > 0) pending = target else { pending = null; onApplyInstant(target) }
                    }
                    else -> { pending = null; onApplyInstant(target) }
                }
            },
        )
        if (stats != null) {
            Spacer(Modifier.height(PhantomSpace.s2))
            Text(
                text = retentionStatusLine(stats),
                style = PhantomText.footnote,
                color = c.text3,
            )
        }

        val target = pending
        val toDelete = target?.let { stats?.perDetentDelete?.get(it) } ?: 0
        if (target != null && stats != null && toDelete > 0) {
            Spacer(Modifier.height(PhantomSpace.s3))
            androidx.compose.runtime.key(target) {
                SettingsConfirmAction(
                    label = stringResource(R.string.settings_retention_wipe, toDelete),
                    confirmLabel = stringResource(R.string.settings_retention_wipe_armed),
                    onConfirm = { onConfirmShorten(target) },
                )
            }
        }
    }
}

@Composable
private fun detentLabel(days: Int): String = when {
    days <= 0 -> stringResource(R.string.settings_retention_detent_forever)
    days == 365 -> stringResource(R.string.settings_retention_detent_year)
    else -> stringResource(R.string.settings_retention_detent_days, days)
}

@Composable
private fun retentionValueLabel(days: Int): String =
    if (days <= 0) {
        stringResource(R.string.settings_retention_detent_forever)
    } else {
        daysLabel(days)
    }

@Composable
private fun retentionStatusLine(stats: RetentionUiState): String =
    if (stats.totalItems <= 0) {
        stringResource(R.string.settings_retention_status_empty)
    } else {
        val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
        stringResource(
            R.string.settings_retention_status,
            stats.totalItems,
            stats.oldestMs?.let { formatDay(it, locale) } ?: "—",
            stats.pinnedCount,
            approxMb(stats.approxBytes),
        )
    }

private fun magnitude(days: Int): Long = if (days <= 0) Long.MAX_VALUE else days.toLong()

private fun isShortening(current: Int, target: Int): Boolean =
    magnitude(target) < magnitude(current)

internal fun formatDay(ms: Long, locale: Locale): String {
    val pattern = runCatching { DateFormat.getBestDateTimePattern(locale, "MMMMd") }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: if (locale.language == "en") "MMMM d" else "d MMMM"
    return SimpleDateFormat(pattern, locale).format(Date(ms))
}

private fun approxMb(bytes: Long): Int = ((bytes + 512L * 1024) / (1024L * 1024)).toInt()

@Composable
private fun daysLabel(n: Int): String =
    pluralStringResource(R.plurals.retention_days, n, n)
