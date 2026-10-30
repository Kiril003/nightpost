package local.phantom.companion.feature.stream.ui.map.peers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomSans
import local.phantom.companion.feature.stream.ui.map.MapPrimaryAction
import local.phantom.companion.feature.stream.ui.map.MapSecondaryAction
import local.phantom.companion.feature.stream.ui.map.mapRaised
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import local.phantom.companion.core.design.theme.PhantomRadii
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import local.phantom.companion.core.design.layout.bottomChromeInsets
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import java.util.Locale
import java.util.TimeZone
import local.phantom.companion.core.design.layout.readingGutter
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTitle
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.dossier.formatDistance
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters

/** Копія фактів на мить тапу: картка не перечитує шар і не може розійтись із ним. */
internal data class TappedPeer(
    val nodeId: String,
    val threadId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    val verified: Boolean,
    val fresh: Boolean,
    /** Не null — людина поділилась положенням: район, квартал чи точно. */
    val precision: String? = null,
    val near: String? = null,
    val motion: String? = null,
    val spot: String? = null,
    val lowBattery: Boolean = false,
    val face: String? = null,
)

internal fun local.phantom.companion.core.data.peers.PeerPoint.tapped(nowMs: Long) = TappedPeer(
    nodeId = nodeId,
    threadId = threadId,
    name = name,
    lat = lat,
    lon = lon,
    atMs = atMs,
    verified = verified,
    fresh = freshAt(nowMs),
    precision = precision?.name,
    near = near?.name,
    motion = motion?.name,
    spot = spot?.name,
    lowBattery = lowBattery,
    face = face,
)

/** Вік словом: «щойно», «7 хв тому», «2 год тому». Годинника без контексту картка не показує. */
internal fun peerAgeMinutes(atMs: Long, nowMs: Long): Long = ((nowMs - atMs).coerceAtLeast(0L)) / 60_000L

/** Час ВИМІРУ, не доставки. Пояс параметром — щоб рядок доводився тестом. */
internal fun peerClockOf(atMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val fmt = java.text.SimpleDateFormat("HH:mm", Locale.US)
    fmt.timeZone = zone
    return fmt.format(java.util.Date(atMs))
}

/** «1,2 км на північний схід від вас» — лише коли відоме і своє місце, і її точне. */
@Composable
private fun awayLine(origin: Pair<Double, Double>?, peer: TappedPeer): String? {
    val (lat, lon) = origin ?: return null
    val meters = haversineMeters(lat, lon, peer.lat, peer.lon)
    val dirs = stringResource(R.string.peer_card_dirs).split(',')
    val deg = local.phantom.companion.feature.stream.ui.map.bearingDegrees(lat to lon, peer.lat to peer.lon)
    val dir = dirs[((deg + 22.5) / 45.0).toInt().mod(8)]
    return stringResource(R.string.peer_card_away_dir, formatDistance(meters), dir)
}

/** Те саме обличчя, що на мапі й у розмові: фото або куля тону людини з ініціалом. */
@Composable
internal fun PeerAvatar(
    name: String,
    size: androidx.compose.ui.unit.Dp = AVATAR,
    toneKey: String = name,
    face: String? = null,
) {
    val night = LocalPhantomTheme.current.isDark
    val photo = androidx.compose.runtime.remember(face) {
        face?.let { runCatching { android.graphics.BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() }
    }
    if (photo != null) {
        androidx.compose.foundation.Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.size(size).clip(CircleShape),
        )
        return
    }
    val t = PersonTone.of(toneKey, night)
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            // Плоска монограма, як аватар у месенджері (дизайн-система §2.1): без світла й глибини.
            .background(Color(t.fill)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            peerInitial(name),
            color = Color(t.ink),
            fontFamily = PhantomSans,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

@Composable
private fun PeerHeader(name: String, line: String?, note: String? = null, toneKey: String = name, face: String? = null) {
    val theme = LocalPhantomTheme.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        PeerAvatar(name, toneKey = toneKey, face = face)
        Spacer(Modifier.width(PhantomSpacings.Base))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            PhantomTitle(text = name, color = theme.inkStrong, fontWeight = FontWeight.Bold, maxLines = 1)
            if (line != null) PhantomBody(text = line, color = theme.ink, maxLines = 2)
            if (note != null) PhantomCaption(text = note)
        }
    }
}

@Composable
private fun PeerActions(onWrite: (() -> Unit)?, onRoute: (() -> Unit)?) {
    if (onWrite == null && onRoute == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small)) {
        if (onWrite != null) {
            MapPrimaryAction(
                PhantomGlyph.ASK_HERE.asImageVector(),
                stringResource(R.string.peer_where_write),
                onWrite,
                Modifier.weight(1f),
            )
        }
        if (onRoute != null) {
            MapSecondaryAction(
                PhantomGlyph.ROUTE_TO.asImageVector(),
                stringResource(R.string.map_sheet_tile_route),
                onRoute,
                Modifier.weight(1f),
            )
        }
    }
}

/** Людина без рівня точності — стара поширка точки. Відстань лише від власної позиції. */
@Composable
internal fun PeerPointCard(
    peer: TappedPeer,
    origin: Pair<Double, Double>?,
    accentColor: Color,
    onWrite: (() -> Unit)? = null,
    /** Поширена точка — точна, тож маршрут до неї чесний. */
    onRoute: (() -> Unit)? = null,
    onShowMe: (() -> Unit)? = null,
) {
    val age = when {
        peer.fresh -> stringResource(R.string.peer_point_now)
        peer.atMs > 0L -> stringResource(R.string.peer_point_as_of, peerClockOf(peer.atMs))
        else -> null
    }
    val away = awayLine(origin, peer)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Large)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Large),
    ) {
        // Два короткі рядки, а не один, що ламається посередині «станом на».
        PeerHeader(peer.name, away ?: age, if (away != null) age else null)
        PeerActions(onWrite = onWrite, onRoute = onRoute)
        if (onShowMe != null) {
            MapSecondaryAction(
                PhantomGlyph.LOCATE_LOCKED.asImageVector(),
                stringResource(R.string.peer_card_show_me),
                onShowMe,
            )
        }
    }
}

/** Довше за це мовчання — «давно не оновлювалось»; так само виглядає й привид, і телефон без супутників. */
internal const val PEER_STALE_MIN = 30L

/**
 * Слова стану на картці. Застаріле місце стану не має: «у дорозі» годину тому —
 * вже неправда, тож лишається лише «давно не оновлювалось».
 */
internal fun peerStateWords(peer: TappedPeer, nowMs: Long): List<Int> {
    if (peerAgeMinutes(peer.atMs, nowMs) >= PEER_STALE_MIN) return listOf(R.string.peer_state_stale)
    val doing = when {
        peer.spot == "HOME" -> R.string.peer_state_home
        peer.spot == "WORK" -> R.string.peer_state_work
        peer.motion == "VEHICLE" -> R.string.peer_state_vehicle
        peer.motion == "FOOT" -> R.string.peer_state_foot
        else -> null
    }
    return listOfNotNull(doing, R.string.peer_state_low_battery.takeIf { peer.lowBattery })
}

/** Де зустрітись: посередині між мною і нею — на її точці чи в центрі її плями. */
internal fun peerMidpoint(origin: Pair<Double, Double>, peer: TappedPeer): Pair<Double, Double> =
    (origin.first + peer.lat) / 2 to (origin.second + peer.lon) / 2

@Composable
internal fun PeerWhereCard(
    peer: TappedPeer,
    accentColor: Color,
    onWrite: () -> Unit,
    onWhoSees: () -> Unit,
    nowMs: Long = System.currentTimeMillis(),
    onRoute: (() -> Unit)? = null,
    /** Власна позиція — для відстані й напряму до ТОЧНОГО місця; до району їх не кажемо. */
    origin: Pair<Double, Double>? = null,
    onWave: (() -> Unit)? = null,
    onCallHere: (() -> Unit)? = null,
    onMeetMiddle: (() -> Unit)? = null,
) {
    val minutes = peerAgeMinutes(peer.atMs, nowMs)
    val stale = minutes >= PEER_STALE_MIN
    val age = when {
        minutes < 1 -> stringResource(R.string.peer_where_age_now)
        minutes < 60 -> stringResource(R.string.peer_where_age_min, minutes)
        else -> stringResource(R.string.peer_where_age_hours, minutes / 60)
    }
    val level = when (peer.precision) {
        "DISTRICT" -> R.string.peer_where_level_district
        "QUARTER" -> R.string.peer_where_level_quarter
        else -> R.string.peer_where_level_exact
    }
    val near = when (peer.near) {
        "SAME" -> if (peer.precision == "DISTRICT") R.string.peer_where_near_same_district else R.string.peer_where_near_same
        "NEAR" -> R.string.peer_where_near_near
        "FAR" -> R.string.peer_where_near_far
        else -> R.string.peer_where_near_unknown
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = readingGutter(PhantomSpacings.Base))
            .padding(top = PhantomSpacings.Base, bottom = PhantomSpacings.Base)
            .windowInsetsPadding(bottomChromeInsets()),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Large),
    ) {
        val where = (if (peer.precision == "EXACT") awayLine(origin, peer) else null) ?: stringResource(near)
        val words = peerStateWords(peer, nowMs).map { stringResource(it) }
        // Стан — першим: «Вдома» людині важливіше за те, як далеко.
        val line = (if (stale) words else words + where).joinToString(" · ").replaceFirstChar { it.uppercase() }
        PeerHeader(
            peer.name,
            line,
            // Звірки на картці немає свідомо: вона живе лише в профілі людини.
            "$age · " + stringResource(R.string.peer_where_shows, stringResource(level)),
            toneKey = peer.threadId.ifBlank { peer.name },
            face = peer.face,
        )
        PeerActions(onWrite = onWrite, onRoute = onRoute)
        if (onWave != null || onCallHere != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(PhantomSpacings.Small)) {
                if (onWave != null) {
                    MapSecondaryAction(PhantomGlyph.PERSON.asImageVector(), stringResource(R.string.peer_act_wave), onWave, Modifier.weight(1f))
                }
                if (onCallHere != null) {
                    MapSecondaryAction(PhantomGlyph.HANDOUT.asImageVector(), stringResource(R.string.peer_act_call_here), onCallHere, Modifier.weight(1f))
                }
            }
        }
        if (onMeetMiddle != null) {
            MapSecondaryAction(PhantomGlyph.VIA.asImageVector(), stringResource(R.string.peer_act_meet_middle), onMeetMiddle)
        }
        // Відповідь тим самим: вона показує себе — людина одним дотиком вирішує, що показати їй.
        MapSecondaryAction(
            PhantomGlyph.LOCATE_LOCKED.asImageVector(),
            stringResource(R.string.peer_card_show_me),
            onWhoSees,
        )
    }
}

private val AVATAR = 56.dp
