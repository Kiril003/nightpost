package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import local.phantom.companion.core.data.peers.PeerMotion
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.PeerPrecision
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.design.layout.bottomChromeInsets
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.feature.stream.R
import kotlin.math.roundToInt
import local.phantom.companion.feature.stream.ui.map.MapPrimaryAction
import local.phantom.companion.feature.stream.ui.map.MapSecondaryAction
import local.phantom.companion.feature.stream.ui.map.around.AroundPlace
import local.phantom.companion.feature.stream.ui.map.dossier.formatDistance
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector

/** Зустріч живе в памʼяті мапи того, хто кликав: точка й ті, кого кликали. */
internal data class Meeting(val lat: Double, val lon: Double, val members: List<Pair<String, String>>, val sinceMs: Long)

internal enum class MeetState { THERE, ON_WAY, NEAR, FAR, UNSEEN }

internal data class MeetRow(val peerId: String, val name: String, val state: MeetState, val metres: Double?, val peer: TappedPeer?)

private const val THERE_M = 150.0
private const val NEAR_M = 2_000.0

/**
 * Хто вже в дорозі й скільки кому лишилось — з того, що кожен сам показує.
 * Метри — лише до точного місця; про пляму кажемо словом, бо центр плями — не людина.
 */
internal fun meetRows(m: Meeting, points: List<PeerPoint>, nowMs: Long): List<MeetRow> = m.members.map { (id, name) ->
    val p = points.firstOrNull { it.threadId == id && it.isWhere() && !it.expiredAt(nowMs) }
    if (p == null) return@map MeetRow(id, name, MeetState.UNSEEN, null, null)
    val tapped = p.tapped(nowMs)
    val box = p.box
    if (p.precision != PeerPrecision.EXACT || box != null) {
        val inside = box != null && m.lat in box.south..box.north && m.lon in box.west..box.east
        return@map MeetRow(id, p.name, if (inside) MeetState.NEAR else MeetState.FAR, null, tapped)
    }
    val d = metresBetween(p.lat, p.lon, m.lat, m.lon)
    val state = when {
        d <= THERE_M -> MeetState.THERE
        p.motion == PeerMotion.FOOT || p.motion == PeerMotion.VEHICLE -> MeetState.ON_WAY
        d <= NEAR_M -> MeetState.NEAR
        else -> MeetState.FAR
    }
    MeetRow(id, p.name, state, d.takeIf { state != MeetState.THERE }, tapped)
}.sortedBy { it.state.ordinal }

internal data class FairMeetCandidate(
    val name: String,
    val lat: Double,
    val lon: Double,
    val venueId: String?,
    val etasMinutes: Map<String, Int>,
    val maxSpreadMinutes: Int,
)

private const val WALK_METERS_PER_MIN = 80.0

internal fun pickFairMeetVenues(
    participants: List<Pair<String, Pair<Double, Double>>>,
    venues: List<AroundPlace>,
    limit: Int = 3,
): List<FairMeetCandidate> {
    if (participants.isEmpty() || venues.isEmpty() || limit <= 0) return emptyList()
    return venues
        .filter { it.name.isNotBlank() }
        .map { venue ->
            val etas = LinkedHashMap<String, Int>(participants.size)
            for ((person, coords) in participants) {
                val dist = haversineMeters(coords.first, coords.second, venue.lat, venue.lon)
                etas[person] = maxOf(1, (dist / WALK_METERS_PER_MIN).roundToInt())
            }
            val mins = etas.values
            val spread = (mins.maxOrNull() ?: 0) - (mins.minOrNull() ?: 0)
            FairMeetCandidate(
                name = venue.name,
                lat = venue.lat,
                lon = venue.lon,
                venueId = venue.venueId,
                etasMinutes = etas,
                maxSpreadMinutes = spread,
            )
        }
        .sortedBy { it.etasMinutes.values.sum() }
        .sortedWith(compareBy({ it.maxSpreadMinutes }))
        .take(limit)
}

@androidx.annotation.StringRes
internal fun fairMeetSelfLabelRes(): Int = R.string.map_people_you

internal fun buildFairMeetParticipants(
    selfLabel: String,
    me: Pair<Double, Double>?,
    points: List<PeerPoint>,
    nowMs: Long,
): List<Pair<String, Pair<Double, Double>>> = buildList {
    if (me != null) add(selfLabel to me)
    points.filter { it.precision != null && !it.expiredAt(nowMs) }.forEach { p ->
        add(p.name to (p.lat to p.lon))
    }
}

internal fun fairMeetRecipients(
    candidate: FairMeetCandidate,
    eligiblePeers: List<Pair<String, String>>,
): List<Pair<String, String>> =
    eligiblePeers.filter { (id, name) ->
        name in candidate.etasMinutes.keys || id in candidate.etasMinutes.keys
    }

internal fun formatFairMeetMessage(meetAtLabel: String, timeLabel: String): String {
    val cleanTime = timeLabel.trim()
    return if (cleanTime.isNotEmpty()) "$meetAtLabel · $cleanTime" else meetAtLabel
}

internal fun dispatchFairMeet(
    port: MapPeoplePort,
    candidate: FairMeetCandidate,
    timeLabel: String,
    meetAtLabel: String,
    arrivedWords: String,
    eligiblePeers: List<Pair<String, String>>,
    nowMs: Long,
): Meeting? {
    val words = formatFairMeetMessage(meetAtLabel, timeLabel)
    val recipients = fairMeetRecipients(candidate, eligiblePeers)
    val sent = recipients.filter { (id, _) ->
        port.sendPlace(id, candidate.lat, candidate.lon, words)
    }
    if (sent.isEmpty()) return null
    port.tellOnArrival(sent.map { it.first }, candidate.lat, candidate.lon, arrivedWords)
    return Meeting(candidate.lat, candidate.lon, sent, nowMs)
}

/** Центр ваги: мене й тих, чиє місце відоме. Без жодного відомого — «посередині» немає. */
internal fun meetMiddle(me: Pair<Double, Double>, members: List<String>, points: List<PeerPoint>, nowMs: Long): Pair<Double, Double>? {
    val known = members.mapNotNull { id -> points.firstOrNull { it.threadId == id && it.isWhere() && !it.expiredAt(nowMs) } }
    if (known.isEmpty()) return null
    val all = known.map { it.lat to it.lon } + me
    return all.map { it.first }.average() to all.map { it.second }.average()
}

private fun metresBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val dLat = (bLat - aLat) * 111_320.0
    val dLon = (bLon - aLon) * 111_320.0 * kotlin.math.cos(Math.toRadians((aLat + bLat) / 2))
    return kotlin.math.hypot(dLat, dLon)
}

/** Кого кликати: ті, з ким є розмова. Точка — тут або посередині між усіма, хто себе показує. */
@Composable
internal fun MeetPickSheet(
    people: List<Pair<String, String>>,
    canMiddle: (List<String>) -> Boolean,
    onInvite: (List<Pair<String, String>>, middle: Boolean) -> Unit,
) {
    val theme = LocalPhantomTheme.current
    val picked = remember { mutableStateListOf<String>() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomTitle(text = stringResource(R.string.meet_title), color = theme.inkStrong, fontWeight = FontWeight.Bold)
        PhantomCaption(text = stringResource(R.string.meet_pick))
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            people.forEach { (id, name) ->
                val on = id in picked
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = PhantomTouchFloor)
                        .clickable(role = Role.Checkbox) { if (on) picked.remove(id) else picked.add(id) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PeerAvatar(name, 36.dp, toneKey = id)
                    Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
                    PhantomBody(text = name, color = theme.inkStrong, maxLines = 1, modifier = Modifier.weight(1f))
                    Checkbox(checked = on, onCheckedChange = null)
                }
            }
        }
        val chosen = people.filter { it.first in picked }
        if (chosen.isNotEmpty()) {
            MapPrimaryAction(PhantomGlyph.HANDOUT.asImageVector(), stringResource(R.string.meet_here), { onInvite(chosen, false) })
            if (canMiddle(chosen.map { it.first })) {
                MapSecondaryAction(PhantomGlyph.VIA.asImageVector(), stringResource(R.string.meet_middle_all), { onInvite(chosen, true) })
            } else {
                PhantomCaption(text = stringResource(R.string.meet_nobody))
            }
        }
    }
}

@Composable
internal fun MeetSheet(rows: List<MeetRow>, nowMs: Long, onOpen: (TappedPeer) -> Unit, onEnd: () -> Unit) {
    val theme = LocalPhantomTheme.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Small),
    ) {
        PhantomTitle(text = stringResource(R.string.meet_title), color = theme.inkStrong, fontWeight = FontWeight.Bold)
        rows.forEach { person ->
            val line = when (person.state) {
                MeetState.THERE -> stringResource(R.string.meet_there)
                MeetState.ON_WAY -> stringResource(R.string.meet_on_way, formatDistance(person.metres ?: 0.0))
                MeetState.NEAR -> person.metres?.let { stringResource(R.string.meet_to_go, formatDistance(it)) } ?: stringResource(R.string.meet_near)
                MeetState.FAR -> person.metres?.let { stringResource(R.string.meet_to_go, formatDistance(it)) } ?: stringResource(R.string.meet_far)
                MeetState.UNSEEN -> stringResource(R.string.meet_unseen)
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PhantomTouchFloor)
                    .then(if (person.peer != null) Modifier.clickable(role = Role.Button) { onOpen(person.peer) } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PeerAvatar(person.name, 40.dp, toneKey = person.peerId, face = person.peer?.face)
                Spacer(Modifier.width(PhantomSpacings.Small + PhantomSpacings.Micro))
                Column(Modifier.weight(1f)) {
                    PhantomBody(text = person.name, color = theme.inkStrong, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    PhantomCaption(text = line.replaceFirstChar { it.uppercase() })
                }
                // «На місці» — бурштином: це те, чого чекають.
                if (person.state == MeetState.THERE) PhantomBody(text = "●", color = theme.primary)
            }
        }
        MapSecondaryAction(PhantomGlyph.DISMISS.asImageVector(), stringResource(R.string.meet_end), onEnd)
    }
}
