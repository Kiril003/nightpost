package local.phantom.companion.feature.stream.ui.map.here

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Lang
import local.phantom.companion.core.truth.Speech
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapChip
import local.phantom.companion.feature.stream.ui.map.MapQuietRow
import local.phantom.companion.feature.stream.ui.map.MapTextAction
import local.phantom.companion.feature.stream.ui.map.MapTone
import local.phantom.companion.feature.stream.ui.map.SheetNote

internal const val TAG_HERE_NOW = "map-here-now"

internal fun hereNowLang(tag: String): Lang = if (tag == "uk") Lang.UK else Lang.EN

@Composable
private fun hereWords() = HereWords(
    held = stringResource(R.string.here_now_held_short),
    sent = stringResource(R.string.here_now_sent_short),
    unsure = stringResource(R.string.here_now_unsure),
)

@Composable
internal fun HereNowBlock(
    state: HereNowState,
    nowMs: Long,
    onReport: (kindWire: String, readingWire: String) -> Unit,
) {
    if (!hereNowShows(state)) return
    val c = LocalPhantomColors.current
    val lang = hereNowLang(stringResource(R.string.here_now_lang))
    val rows = hereNowRows(state)
    val offered = hereReportOffered(state)
    var open by remember(state.cell) { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TAG_HERE_NOW),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = PhantomTouchFloor),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hereFactsLine(rows, lang, hereWords()),
                    style = PhantomText.subhead,
                    color = c.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val from = stringResource(R.string.here_now_from_people)
                val meta = hereFreshestMs(rows)?.let { "$from · " + Speech.ago(nowMs - it, lang) } ?: from
                Text(meta, style = PhantomText.caption, color = c.text3, maxLines = 1)
            }
            if (offered) {
                MapTextAction(stringResource(R.string.here_now_report_short), { open = !open }, tone = MapTone.Neutral)
            }
        }
        if (open && offered) HereReportPicker(state, lang, onReport) { open = false }
        if (state.skyHolds) SheetNote(stringResource(R.string.here_now_embargo))
        HereNoticeNote(state)
    }
}

@Composable
internal fun HereReportRow(
    state: HereNowState,
    onReport: (kindWire: String, readingWire: String) -> Unit,
) {
    if (hereNowShows(state) || !hereReportOffered(state)) return
    val lang = hereNowLang(stringResource(R.string.here_now_lang))
    var open by remember(state.cell) { mutableStateOf(false) }
    MapQuietRow(stringResource(R.string.here_now_report), { open = !open })
    if (!open) return
    HereReportPicker(state, lang, onReport) { open = false }
    HereNoticeNote(state)
}

@Composable
private fun HereReportPicker(
    state: HereNowState,
    lang: Lang,
    onReport: (kindWire: String, readingWire: String) -> Unit,
    onDone: () -> Unit,
) {
    var pick by remember(state.cell) { mutableStateOf<FactKind?>(null) }
    val kind = pick
    if (kind == null) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            for (k in hereReportKinds(state)) {
                MapChip(if (lang == Lang.EN) k.en else k.uk, { pick = k })
            }
        }
        return
    }
    val yes = kind.positive ?: return
    val no = kind.opposite(yes) ?: return
    val road = kind == FactKind.ROAD
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s4),
    ) {
        Text(
            text = if (lang == Lang.EN) kind.en else kind.uk,
            style = PhantomText.subhead,
            color = LocalPhantomColors.current.text,
            modifier = Modifier.weight(1f).clickable(role = Role.Button) { pick = null },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ReportPair(
            yes = stringResource(if (road) R.string.here_now_road_yes else R.string.here_now_yes),
            no = stringResource(if (road) R.string.here_now_road_no else R.string.here_now_no),
            onYes = { onReport(kind.wire, yes.wire); onDone() },
            onNo = { onReport(kind.wire, no.wire); onDone() },
        )
    }
}

@Composable
private fun HereNoticeNote(state: HereNowState) {
    val n = state.notice ?: return
    SheetNote(
        when (n) {
            is HereNotice.Refused -> stringResource(R.string.here_now_refused)
            is HereNotice.Limited -> stringResource(R.string.here_now_limited, n.minutes)
            HereNotice.NoKey -> stringResource(R.string.here_now_no_key)
            HereNotice.Failed -> stringResource(R.string.here_now_failed)
        },
        color = LocalPhantomColors.current.text,
    )
}

@Composable
private fun ReportPair(yes: String, no: String, onYes: () -> Unit, onNo: () -> Unit) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .width(PAIR_WIDTH)
            .height(PhantomTouchFloor)
            .clip(RoundedCornerShape(PhantomRadius.control))
            .background(c.raised)
            .padding(PhantomSpace.s1),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        for ((label, click) in listOf(yes to onYes, no to onNo)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(PhantomRadius.chip))
                    .background(c.surface)
                    .clickable(role = Role.Button, onClick = click)
                    .semantics(mergeDescendants = true) { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                local.phantom.companion.feature.stream.ui.map.FitLabel(label, c.text, FontWeight.SemiBold)
            }
        }
    }
}

private val PAIR_WIDTH = 184.dp
