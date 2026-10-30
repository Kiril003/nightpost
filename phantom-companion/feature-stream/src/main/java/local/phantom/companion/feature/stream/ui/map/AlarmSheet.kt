package local.phantom.companion.feature.stream.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import local.phantom.companion.core.data.alerts.AirAlertDelivery
import local.phantom.companion.core.data.alerts.AirAlertMissLog
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomDisplay
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.theme.onAccentInk

private val HAIRLINE = MapChromeMetrics.Hairline
private val JOURNAL_DOT = PhantomSpacings.Small
private val OBLAST_TILE = PhantomSpacings.XLarge
private val CLOCK_COLUMN = PhantomSpacings.XXXLarge
private val ICON = MapChromeMetrics.Glyph

/**
 * СИРЕНА — «Небо»: the alarm command center. One sheet that answers, in
 * order of how much the operator's pulse cares: is MY sky on fire → what
 * exactly is in it → where else is burning → what has changed on this watch.
 *
 * Everything rendered here is observed feed truth: the board is the live
 * snapshot, the journal is only the transitions THIS session witnessed
 * (labelled so), and threat types appear exactly as detected — never
 * invented (Law 1).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlarmSheet(
    ownOblast: String?,
    alerts: Map<String, AlertState>,
    journal: List<AlertTransition>,
    detailLive: Boolean,
    /** Wall time of the last successful feed fetch; null = never answered. */
    fetchedAtMs: Long?,
    sheetState: SheetState,
    onFocusOblast: (String) -> Unit,
    onOpenBrief: (String) -> Unit,
    onRouteToShelter: () -> Unit,
    onDismiss: () -> Unit,
    circle: (@Composable () -> Unit)? = null,
) {
    val theme = LocalPhantomTheme.current
    val board = remember(alerts, ownOblast) { sortAlertsForBoard(alerts, ownOblast) }
    // Oblasts that are quiet oblast-wide but have raion/hromada alarms —
    // the keyless typed feed's gift; shown dimmer, never painted red.
    val localOnly = remember(alerts) {
        alerts.filterValues { !it.active && it.districts.isNotEmpty() }
            .toList()
            .sortedByDescending { it.second.districts.size }
    }

    // One slow clock for every «триває …» line — not a per-row ticker.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            nowMs = System.currentTimeMillis()
        }
    }
    val clockFmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale("uk", "UA")) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = theme.surfaceBase,
        contentColor = theme.ink,
        shape = PhantomRadii.SheetTop,
        scrimColor = theme.surfaceDeep.copy(alpha = PhantomAlpha.Secondary),
        tonalElevation = PhantomSpacings.None,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = readingGutter(PhantomSpacings.Base))
                .padding(bottom = PhantomSpacings.Large),
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Base),
        ) {
            // ── Header: the country's one-line verdict ──────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                PhantomKicker(
                    text = stringResource(R.string.kt_alarm_title),
                    color = theme.inkStrong,
                    modifier = Modifier.weight(1f),
                )
                val anyAlert = board.isNotEmpty()
                Box(
                    modifier = Modifier
                        .clip(PhantomRadii.Pill)
                        .background(
                            (if (anyAlert) theme.coral else theme.success)
                                .copy(alpha = PhantomAlpha.Hairline)
                        )
                        .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Micro),
                ) {
                    PhantomKicker(
                        text = if (anyAlert) {
                            LocalContext.current.resources.getQuantityString(R.plurals.kt_alarm_regions, board.size, board.size)
                        } else {
                            stringResource(R.string.kt_alarm_clear_short)
                        },
                        color = if (anyAlert) theme.coral else theme.success,
                    )
                }
            }

            // Feed freshness — the sheet never pretends a dead feed is a
            // quiet sky.
            if (fetchedAtMs == null) {
                PhantomCaption(
                    text = stringResource(R.string.kt_alarm_no_feed),
                    color = theme.primary,
                )
            } else {
                val age = durationShortUk(fetchedAtMs, nowMs)
                PhantomCaption(
                    text = if (age == stringResource(R.string.mapl_e_duration_just_now)) {
                        stringResource(R.string.kt_alarm_updated_now)
                    } else {
                        stringResource(R.string.kt_alarm_updated_ago, age)
                    },
                )
            }

            // ── The operator's own sky ──────────────────────────────────
            OwnSkyCard(
                ownOblast = ownOblast,
                state = ownOblast?.let { alerts[it] },
                nowMs = nowMs,
                onRouteToShelter = onRouteToShelter,
            )

            circle?.invoke()

            // ── The country board ───────────────────────────────────────
            SectionCaption(stringResource(R.string.kt_alarm_countrywide))
            if (board.isEmpty()) {
                PhantomBody(
                    text = stringResource(R.string.kt_alarm_none_active),
                    color = theme.success,
                )
            } else {
                board.forEach { (name, st) ->
                    OblastRow(
                        name = name,
                        state = st,
                        isOwn = name == ownOblast,
                        nowMs = nowMs,
                        onOpen = { onOpenBrief(name) },
                        onFocus = { onFocusOblast(name) },
                    )
                }
            }

            // ── Raion-level alarms in otherwise-quiet oblasts ───────────
            if (localOnly.isNotEmpty()) {
                SectionCaption(stringResource(R.string.kt_alarm_local_head))
                localOnly.forEach { (name, st) ->
                    LocalAlarmRow(
                        name = name,
                        state = st,
                        onOpen = { onOpenBrief(name) },
                    )
                }
            }

            // ── The watch journal ───────────────────────────────────────
            SectionCaption(stringResource(R.string.kt_alarm_log_head))
            if (journal.isEmpty()) {
                PhantomBody(
                    text = stringResource(R.string.kt_alarm_log_empty),
                    color = theme.inkSecondary,
                )
            } else {
                journal.take(JOURNAL_SHOWN).forEach { t ->
                    JournalRow(t, clockFmt)
                }
            }

            // ── Sources — the sheet says where its truth comes from ─────
            // ATLAS-3: імена стрічок бере пак. Вписані сюди, вони пережили б
            // країну, для якої писались, і аркуш назвав би джерелом те, чого
            // за кордоном ніхто не питав.
            val feeds = local.phantom.companion.core.data.country.CountryPackRegistry.current()?.alarmFeeds.orEmpty()
            val stateHost = feeds.firstOrNull {
                it.role == local.phantom.companion.core.data.country.AlarmFeedRole.REGION_STATE
            }?.host
            val typedHost = feeds.firstOrNull {
                it.role == local.phantom.companion.core.data.country.AlarmFeedRole.TYPED
            }?.host
            val detailHost = feeds.firstOrNull {
                it.role == local.phantom.companion.core.data.country.AlarmFeedRole.DETAIL
            }?.host
            // Обидва або жодного: назвати джерелом «null» гірше, ніж змовчати.
            if (stateHost != null && typedHost != null) {
                PhantomCaption(
                    text = buildString {
                        append(stringResource(R.string.kt_alarm_sources, stateHost, typedHost))
                        if (detailLive) append(" · $detailHost")
                    },
                    color = theme.inkSecondary,
                )
            }
        }
    }
}

private const val JOURNAL_SHOWN = 24

@Composable
private fun SectionCaption(text: String) {
    PhantomKicker(text = text)
}

/** The hero card: the operator's own oblast, or an honest «не знаю де ти». */
@Composable
private fun OwnSkyCard(
    ownOblast: String?,
    state: AlertState?,
    nowMs: Long,
    onRouteToShelter: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val active = state?.active == true
    val tint = if (active) theme.coral else theme.success

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(PhantomRadii.LargeShape)
            .background(tint.copy(alpha = if (active) PhantomAlpha.Hairline else PhantomAlpha.Ghost))
            .border(
                HAIRLINE,
                tint.copy(alpha = if (active) PhantomAlpha.Secondary else PhantomAlpha.Veil),
                PhantomRadii.LargeShape,
            )
            .padding(PhantomSpacings.Base),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        if (ownOblast == null) {
            PhantomBody(
                text = stringResource(R.string.kt_alarm_unknown_place),
                color = theme.inkSecondary,
            )
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                PhantomDisplay(
                    text = stringResource(if (active) R.string.kt_alarm_word_alert else R.string.kt_alarm_word_clear),
                    color = tint,
                )
                PhantomBody(
                    text = oblastShortUk(ownOblast),
                    color = theme.inkStrong,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (active) {
                state?.changedEpochMs?.let { since ->
                    PhantomDataText(
                        text = stringResource(R.string.kt_alarm_lasting, durationShortUk(since, nowMs)),
                        color = theme.inkSecondary,
                    )
                }
            }
        }

        // Raion-level truth: worth a line whether or not the whole oblast
        // is lit («тривога в районах» while the oblast itself sleeps).
        val districts = state?.districts.orEmpty()
        if (districts.isNotEmpty()) {
            PhantomBody(
                text = stringResource(R.string.kt_alarm_locally, districtsLineUk(districts)),
                color = if (active) theme.inkSecondary else theme.primary,
            )
        }

        // Чи ця тривога взагалі вийде в шторку. Місце тут, а не в хромі мапи:
        // хром люди ковзають поглядом, аркуш неба — читають. І четвертий
        // постійний рядок у хромі був би тією самою скаргою власника про
        // смугу, що займає багато місця, лише іншим текстом.
        //
        // Здоровий стан не малюється ЗОВСІМ. Продукт не має права казати
        // «тривога дійде»: оболонки Xiaomi й Huawei притримують сповіщення
        // самі, і зсередини це не видно. Тиша і є тут чесність.
        AlertDeliveryLine()

        if (active) {
            val shelterName = stringResource(R.string.kt_alarm_shelter_route)
            Button(
                onClick = onRouteToShelter,
                colors = ButtonDefaults.buttonColors(containerColor = theme.coral),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomTouchFloor)
                    .semantics(mergeDescendants = true) { contentDescription = shelterName },
                shape = PhantomRadii.MediumShape,
            ) {
                PhantomBody(
                    text = shelterName,
                    color = theme.coral.onAccentInk(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** One oblast of the country board: what, where, since when. */
@Composable
private fun OblastRow(
    name: String,
    state: AlertState,
    isOwn: Boolean,
    nowMs: Long,
    onOpen: () -> Unit,
    onFocus: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val meta = state.threatType.meta
    val since = state.changedEpochMs?.let { durationShortUk(it, nowMs) }
    val justNow = stringResource(R.string.mapl_e_duration_just_now)
    val agoText = since?.let { if (it == justNow) it else stringResource(R.string.kt_alarm_ago, it) }
    val detail = listOfNotNull(agoText)
        .joinToString(" · ").ifEmpty { stringResource(R.string.kt_alarm_detail_default) }
    val openName = stringResource(R.string.kt_alarm_open_name, oblastShortUk(name), detail)
    val focusName = stringResource(R.string.kt_alarm_focus_name, oblastShortUk(name))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(PhantomRadii.MediumShape)
            .background(theme.glassPanel.copy(alpha = if (isOwn) PhantomAlpha.Near else PhantomAlpha.Secondary))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = openName }
            .padding(start = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(OBLAST_TILE)
                .clip(PhantomRadii.Pill)
                .background(Color(meta.colorArgb).copy(alpha = PhantomAlpha.Wash))
                .border(HAIRLINE, Color(meta.colorArgb).copy(alpha = PhantomAlpha.Secondary), PhantomRadii.Pill),
            contentAlignment = Alignment.Center,
        ) {
            PhantomGlyphIcon(glyph = meta.glyph, tint = Color(meta.colorArgb), modifier = Modifier.size(THREAT_GLYPH))
        }
        Spacer(Modifier.width(PhantomSpacings.Small))
        Column(Modifier.weight(1f)) {
            PhantomBody(
                text = oblastShortUk(name),
                color = theme.inkStrong,
                fontWeight = if (isOwn) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PhantomCaption(text = detail, maxLines = 1)
        }
        Box(
            modifier = Modifier
                .size(PhantomTouchFloor)
                .clip(PhantomRadii.Pill)
                .clickable(role = Role.Button, onClick = onFocus)
                .semantics { contentDescription = focusName },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.MyLocation,
                contentDescription = null,
                tint = theme.inkSecondary,
                modifier = Modifier.size(ICON),
            )
        }
    }
}

/**
 * A quiet oblast with raion/hromada alarms: dimmer than the board — the
 * oblast-wide siren is NOT sounding, and the row must not pretend it is.
 */
@Composable
private fun LocalAlarmRow(
    name: String,
    state: AlertState,
    onOpen: () -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val worst = state.districts.maxByOrNull { it.threatType.meta.severity }?.threatType
        ?: ThreatType.UNKNOWN
    val detail = districtsLineUk(state.districts, max = 3)
    val openName = stringResource(R.string.kt_alarm_local_name, oblastShortUk(name), detail)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .clip(PhantomRadii.MediumShape)
            .background(theme.glassPanel.copy(alpha = PhantomAlpha.Recessed))
            .clickable(role = Role.Button, onClick = onOpen)
            .semantics { contentDescription = openName }
            .padding(horizontal = PhantomSpacings.Small, vertical = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhantomGlyphIcon(glyph = worst.meta.glyph, tint = Color(worst.meta.colorArgb), modifier = Modifier.size(THREAT_GLYPH))
        Spacer(Modifier.width(PhantomSpacings.Small))
        Column(Modifier.weight(1f)) {
            PhantomBody(
                text = oblastShortUk(name),
                color = theme.inkStrong,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            PhantomCaption(text = detail, maxLines = 2)
        }
    }
}

/** One witnessed transition: time · what happened · where. */
@Composable
private fun JournalRow(t: AlertTransition, clockFmt: java.text.SimpleDateFormat) {
    val theme = LocalPhantomTheme.current
    val (word, tint) = when (t.kind) {
        AlertTransitionKind.STARTED -> stringResource(R.string.kt_alarm_journal_start) to theme.coral
        AlertTransitionKind.ENDED -> stringResource(R.string.kt_alarm_journal_end) to theme.success
    }
    Row(
        modifier = Modifier.padding(vertical = PhantomSpacings.Micro),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PhantomDataText(
            text = clockFmt.format(java.util.Date(t.atEpochMs)),
            color = theme.inkSecondary,
            modifier = Modifier.width(CLOCK_COLUMN),
        )
        Box(
            modifier = Modifier
                .size(JOURNAL_DOT)
                .clip(PhantomRadii.Pill)
                .background(tint),
        )
        Spacer(Modifier.width(PhantomSpacings.Small))
        PhantomBody(
            text = buildString {
                append(word).append(" · ").append(oblastShortUk(t.oblast))
            },
            color = theme.inkStrong,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Правда про доставку — і квитанція про вже пропущену тривогу.
 *
 * Речення про стан стоїть постійно, бо стан не змахують. Квитанція — навпаки:
 * вона про подію, показується один раз і гасне при читанні. Без неї речення
 * про стан за два тижні стає меблями; квитанція повертає йому вагу, бо каже
 * не «може не прийти», а «не прийшла».
 */
@Composable
private fun AlertDeliveryLine() {
    val theme = LocalPhantomTheme.current
    val context = LocalContext.current
    val delivery = remember { AirAlertDelivery.of(context) }
    var miss by remember { mutableStateOf(AirAlertMissLog.last(context)) }

    when (delivery) {
        is AirAlertDelivery.Silent -> PhantomBody(
            text = stringResource(R.string.sky_delivery_none),
            color = theme.coral,
            fontWeight = FontWeight.Medium,
        )
        AirAlertDelivery.Quiet -> PhantomBody(
            text = stringResource(R.string.sky_delivery_quiet),
            color = theme.inkSecondary,
        )
        AirAlertDelivery.Fine -> Unit
    }

    miss?.let { seen ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(PhantomRadii.MediumShape)
                .background(theme.coral.copy(alpha = PhantomAlpha.Hairline))
                .heightIn(min = PhantomTouchFloor)
                .clickable {
                    AirAlertMissLog.clear(context)
                    miss = null
                }
                .padding(PhantomSpacings.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PhantomBody(
                text = stringResource(
                    R.string.sky_missed,
                    oblastShortUk(seen.oblast),
                    timeShortUk(seen.atEpochMs),
                ),
                color = theme.coral,
            )
        }
    }
}

/** Година й хвилина події — рівно те, що потрібно, щоб її впізнати. */
private fun timeShortUk(atEpochMs: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = atEpochMs }
    return "%02d:%02d".format(
        cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE),
    )
}
