package local.phantom.companion.feature.stream.ui.map.around

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.dossierCategoryOf
import local.phantom.companion.feature.stream.ui.map.dossier.kindLabelUk
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph

internal const val TAG_PEEK_PLACES = "map-peek-places"

@Composable
internal fun PeekPlacesStrip(places: PeekPlaces, onPlace: (AroundPlace) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalPhantomColors.current
    when (places) {
        is PeekPlaces.Shown -> Row(
            modifier = modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = PhantomSpace.gutterPhone)
                .testTag(TAG_PEEK_PLACES),
            horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            places.rows.forEach { row -> PeekCard(row) { onPlace(row.place) } }
        }
        else -> Text(
            text = stringResource(
                when (places) {
                    PeekPlaces.Asking -> R.string.map_around_places_wait
                    PeekPlaces.ZoomIn -> R.string.map_peek_zoom_in
                    else -> R.string.map_around_places_none
                },
            ),
            style = PhantomText.callout,
            color = c.text2,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = PhantomSpace.gutterPhone)
                .testTag(TAG_PEEK_PLACES),
        )
    }
}

@Composable
private fun PeekCard(row: PeekRow, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val p = row.place
    val hint = if (p.category != null && p.subcategory != null) mapOf(p.category to p.subcategory) else emptyMap()
    val status = p.hours?.let { OpeningHours.statusNow(it) }
    val statusText = when (status) {
        is OpeningHours.Status.Open -> status.untilMinuteOfDay
            ?.let { stringResource(R.string.dossier_open_until, OpeningHours.fmtMinute(it)) }
            ?: stringResource(R.string.dossier_open)
        is OpeningHours.Status.Closed -> status.opensAtMinuteOfDay
            ?.let { stringResource(R.string.dossier_closed_until, OpeningHours.fmtMinute(it)) }
            ?: stringResource(R.string.dossier_closed)
        else -> null
    }
    Column(
        modifier = Modifier
            .width(CARD_W)
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.overlay)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(PhantomSpace.s3),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(c.raised), contentAlignment = Alignment.Center) {
                PhantomGlyphIcon(
                    glyph = dossierCategoryOf(hint, p.category, p.subcategory).glyph(),
                    tint = c.text2,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            row.meters?.let {
                Text(
                    aroundDistance(it, false, stringResource(R.string.map_unit_m), stringResource(R.string.map_unit_km)),
                    style = PhantomText.caption,
                    color = c.text3,
                )
            }
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        val twoLines = with(androidx.compose.ui.platform.LocalDensity.current) { (PhantomText.subhead.lineHeight * 2).toDp() }
        Box(Modifier.heightIn(min = twoLines)) {
            Text(p.name, style = PhantomText.subhead, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        val line = statusText ?: kindLabelUk(hint, p.category, p.subcategory)?.replaceFirstChar { it.uppercase() }
        Text(
            line.orEmpty(),
            style = PhantomText.caption,
            color = when (status) {
                is OpeningHours.Status.Open -> c.success
                else -> c.text2
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val CARD_W = 164.dp
