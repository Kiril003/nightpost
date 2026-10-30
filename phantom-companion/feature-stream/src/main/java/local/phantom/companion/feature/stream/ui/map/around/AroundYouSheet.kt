package local.phantom.companion.feature.stream.ui.map.around

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetTitle
import local.phantom.companion.feature.stream.ui.map.dossier.dossierCategoryOf
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.feature.stream.ui.map.peers.PeerAvatar

/** Вміст аркуша «Поруч»: друзі, що діляться місцем, і місця поруч — обидва з відстанню. */
@Composable
internal fun AroundYouContent(
    friends: List<AroundFriend>,
    places: AroundPlaces?,
    me: Pair<Double, Double>?,
    nowMs: Long,
    onFriend: (AroundFriend) -> Unit,
    onPlace: (AroundPlace) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = readingGutter(PhantomSpace.gutterPhone))
            .padding(top = PhantomSpace.s1, bottom = PhantomSpace.s6)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
    ) {
        SheetTitle(stringResource(R.string.map_around_title))
        if (me == null) SheetNote(stringResource(R.string.map_around_no_fix))

        SheetSection(stringResource(R.string.map_around_friends), Modifier.padding(top = PhantomSpace.s2))
        if (friends.isEmpty()) {
            SheetNote(stringResource(R.string.map_people_none), Modifier.testTag("map-around-nobody"))
        } else {
            friends.forEach { f -> FriendRow(f, nowMs) { onFriend(f) } }
        }

        val shown = places as? AroundPlaces.Shown
        SheetSection(
            stringResource(if (shown?.venues == true) R.string.map_around_venues else R.string.map_around_places),
            Modifier.padding(top = PhantomSpace.s3),
        )
        when {
            shown != null -> shown.places.forEach { p -> PlaceRow(p, me) { onPlace(p) } }
            me == null -> Unit
            places == null -> SheetNote(stringResource(R.string.map_around_places_wait))
            places is AroundPlaces.Nothing -> SheetNote(stringResource(R.string.map_around_places_none))
            else -> SheetNote(stringResource(R.string.map_around_places_unknown))
        }
    }
}

@Composable
private fun FriendRow(f: AroundFriend, nowMs: Long, onClick: () -> Unit) {
    val p = f.point
    val minutes = ((nowMs - p.atMs).coerceAtLeast(0L)) / 60_000L
    val age = when {
        minutes < 1 -> stringResource(R.string.map_around_now)
        minutes < 60 -> stringResource(R.string.map_around_min_ago, minutes.toInt())
        else -> stringResource(R.string.map_around_h_ago, (minutes / 60).toInt())
    }
    val line = if (f.approx) "${stringResource(R.string.map_around_area)} · $age" else age
    AroundRow(
        lead = { PeerAvatar(p.name, size = AVATAR, toneKey = p.nodeId, face = p.face) },
        title = p.name,
        line = line,
        trailing = f.meters?.let { distance(it, f.approx) },
        dim = !f.fresh,
        onClick = onClick,
    )
}

@Composable
private fun PlaceRow(p: AroundPlace, me: Pair<Double, Double>?, onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val rating = p.rating?.let { stringResource(R.string.map_around_rating, "%.1f".format(it.mean), it.count) }
    AroundRow(
        lead = {
            Box(
                Modifier.size(AVATAR).clip(CircleShape).background(c.overlay),
                contentAlignment = Alignment.Center,
            ) {
                PhantomGlyphIcon(
                    glyph = dossierCategoryOf(emptyMap(), p.category, p.subcategory).glyph(),
                    tint = c.text2,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
        title = p.name,
        line = rating ?: p.address,
        trailing = me?.let { distance(haversineMeters(it.first, it.second, p.lat, p.lon), false) },
        dim = false,
        onClick = onClick,
    )
}

@Composable
private fun distance(meters: Double, approx: Boolean): String =
    aroundDistance(meters, approx, stringResource(R.string.map_unit_m), stringResource(R.string.map_unit_km))

@Composable
private fun AroundRow(
    lead: @Composable () -> Unit,
    title: String,
    line: String?,
    trailing: String?,
    dim: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(PhantomRadius.card))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Spacer(Modifier.width(PhantomSpace.s3))
        Column(Modifier.weight(1f)) {
            Text(title, style = PhantomText.body, color = if (dim) c.text2 else c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line != null) {
                Text(line, style = PhantomText.footnote, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(PhantomSpace.s2))
            Text(trailing, style = PhantomText.callout, color = c.text3)
        }
    }
}

private val AVATAR = 40.dp
