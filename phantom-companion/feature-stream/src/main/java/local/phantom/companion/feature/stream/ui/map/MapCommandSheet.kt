package local.phantom.companion.feature.stream.ui.map

import android.app.TimePickerDialog
import java.util.Calendar
import androidx.compose.ui.platform.LocalContext
import androidx.annotation.StringRes
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyph as MapGlyph
import local.phantom.companion.feature.stream.ui.map.icons.asImageVector
import local.phantom.companion.core.data.law.CurfewMatch
import local.phantom.companion.core.data.law.CurfewRules
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.transit.AccessMode
import local.phantom.companion.core.data.transit.Itinerary
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.ui.map.transit.TransitModeTint
import local.phantom.companion.core.nav.RoutePlanner
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.feature.stream.R
import kotlin.math.roundToInt

/** Годинник прибуття зсувається на хвилину — частіше рахувати нема сенсу. */
private const val ARRIVAL_TICK_MS = 30_000L

/**
 * СМУГА МАРШРУТУ — відповідь унизу, під великим пальцем.
 *
 * Картка колись лежала у ВЕРХНІЙ колонці, на всю ширину і на 300+dp заввишки —
 * тобто накривала верхню третину щойно вписаного маршруту, той самий маршрут,
 * який описувала. «Старт» стояв останнім рядком посеред екрана. Тепер це смуга:
 * один рядок відповіді (чим · скільки · куди · СТАРТ), розкриття вгору за
 * потреби, і після «старт» те саме місце морфиться в EtaStrip.
 *
 * Порядок поверхів у розгорнутому стані — порядок рішення (чим → котрим → що на
 * шляху → відповідь), і рядок відповіді ЗАВЖДИ лишається нижнім: палець між
 * станами не рухається.
 */
@Composable
internal fun MapCommandSheet(
    info: MapViewModel.RouteInfo?,
    busy: Boolean,
    options: List<RoutePlanner.Option>,
    selectedIndex: Int,
    deadlineLine: String?,
    onSelectVariant: (Int) -> Unit,
    onProfile: (RouteProfile) -> Unit,
    onClear: () -> Unit,
    onStartGuidance: () -> Unit,
    onFlyover: () -> Unit,
    /** Відмова рушія. Не NoticePill: маршрут, який не побудувався, — стан. */
    failure: RouteFailure? = null,
    worstRegister: GroundRegister? = null,
    /** Uptime останнього жесту камери: рука пішла на мапу — смуга згортається. */
    cameraGestureAtMs: Long = 0L,
    waypoints: List<Pair<Double, Double>> = emptyList(),
    onRemoveVia: ((Int) -> Unit)? = null,
    onSetDeadline: ((String) -> Boolean)? = null,
    onClearDeadline: (() -> Unit)? = null,
    /** Час виїзду для транзиту, секунди від опівночі. Null — «зараз». */
    transitDepartSec: Int? = null,
    onTransitDepart: ((Int?) -> Unit)? = null,
    onOpenManeuverList: (() -> Unit)? = null,
    /** Кому можна віддати цей маршрут. Порожньо — дії немає взагалі. */
    shareTargets: List<local.phantom.companion.core.net.peer.PeerLiveShare.Target> = emptyList(),
    /** Обрано кого. Null ховає дію разом зі списком. */
    onShareRoute: ((String) -> Unit)? = null,
    /** Чим скінчився попередній дотик. Null — нічого ще не тиснули. */
    shareHandoff: MapViewModel.RouteHandoff? = null,
    onShareHandoffSeen: () -> Unit = {},
) {
    when {
        failure != null -> RouteFailureBand(failure, transitDepartSec, onTransitDepart, onProfile, onClear)
        info != null -> RouteBand(
            info = info,
            busy = busy,
            options = options,
            selectedIndex = selectedIndex,
            deadlineLine = deadlineLine,
            worstRegister = worstRegister,
            cameraGestureAtMs = cameraGestureAtMs,
            waypoints = waypoints,
            onRemoveVia = onRemoveVia,
            onSetDeadline = onSetDeadline,
            onClearDeadline = onClearDeadline,
            transitDepartSec = transitDepartSec,
            onTransitDepart = onTransitDepart,
            onOpenManeuverList = onOpenManeuverList,
            shareTargets = shareTargets,
            onShareRoute = onShareRoute,
            shareHandoff = shareHandoff,
            onShareHandoffSeen = onShareHandoffSeen,
            onSelectVariant = onSelectVariant,
            onProfile = onProfile,
            onClear = onClear,
            onStartGuidance = onStartGuidance,
            onFlyover = onFlyover,
        )
        busy -> RoutingBand()
    }
}

/** Відмова маршрутизатора у формі, придатній до показу. */
internal data class RouteFailure(
    /** Причина від рушія. Null — загальна: рушій її не віддав, вигадувати нема з чого. */
    @StringRes val reason: Int? = null,
    /**
     * Жива причина словами, коли вона є лише як текст: транзит каже «до метро
     * задалеко», і це правдивіше за будь-який наш загальний рядок. Виграє в
     * [reason], бо конкретне завжди корисніше за родове.
     */
    val reasonText: String? = null,
    /** Азимут на мету, °. Чиста математика, підписана не-маршрутом. */
    val bearingDeg: Int? = null,
    val straightMeters: Double? = null,
    /** Транзит без рейсів — питання найчастіше в часі: дай ручку тут же. */
    val offerDepartAt: Boolean = false,
    /** Профіль, яким СПРАВДІ пробували прокласти маршрут — рядок вибору
     *  мусить показувати саме його, а не мовчки підмінювати на «пішки». */
    val profile: RouteProfile = RouteProfile.FOOT,
)

@Composable
private fun BandGlass(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    MapFloatingSurface(
        modifier = Modifier
            .fillMaxWidth(local.phantom.companion.core.design.layout.chromeWidthFraction())
            .animateContentSize(),
        content = content,
    )
}

// ── стан 1: будується ────────────────────────────────────────────────────

@Composable
private fun RoutingBand() {
    val c = LocalPhantomColors.current
    BandGlass {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = BAND_ROW)
                .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // «Старт» тут ВІДСУТНІЙ, не disabled: дії нема, поки нема відповіді.
            SheetNote(stringResource(R.string.map_route_busy), color = c.accentText, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ── стан 2: не побудувався ───────────────────────────────────────────────

@Composable
private fun RouteFailureBand(
    failure: RouteFailure,
    transitDepartSec: Int?,
    onTransitDepart: ((Int?) -> Unit)?,
    onProfile: (RouteProfile) -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    val closeName = stringResource(R.string.map_route_clear)
    BandGlass {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = PhantomSpace.s2),
            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = PhantomSpace.s4, end = PhantomSpace.s1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
                ) {
                    SheetBody(
                        stringResource(R.string.route_none),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    SheetNote(
                        failure.reasonText
                            ?: stringResource(failure.reason ?: R.string.route_none_reason),
                        maxLines = 2,
                    )
                }
                Spacer(Modifier.width(PhantomSpace.s2))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(role = Role.Button, onClick = onClear)
                        .semantics { contentDescription = closeName },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        MapGlyph.DISMISS.asImageVector(),
                        contentDescription = null,
                        tint = c.text2,
                        modifier = Modifier.size(ICON),
                    )
                }
            }
            // СИРЕНА-4 — азимут і відстань тепер ВЕЛИКІ й на всю ширину, а не
            // рядок дрібного моноширинного тексту, затиснутий поруч із кнопкою
            // закриття: стрілка-напрям і число кроків — краще за нічого, коли
            // електронного маршруту немає взагалі.
            if (failure.bearingDeg != null && failure.straightMeters != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PhantomSpace.s4),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s3),
                ) {
                    Icon(
                        imageVector = MapGlyph.BEARING.asImageVector(),
                        contentDescription = null,
                        tint = c.accentText,
                        // Дротик вказує на північ у спокої — поворот за
                        // годинниковою стрілкою на сам азимут і є вказівкою.
                        modifier = Modifier
                            .size(32.dp)
                            .rotate(failure.bearingDeg.toFloat()),
                    )
                    Column {
                        SheetText(fmtDistance(res, failure.straightMeters), PhantomText.title1, tnum = true)
                        SheetNote(
                            stringResource(R.string.route_none_bearing_caption, failure.bearingDeg),
                            maxLines = 2,
                            tnum = true,
                        )
                    }
                }
            }
            ProfileSegments(
                selected = failure.profile,
                enabled = true,
                onProfile = onProfile,
            )
            if (failure.offerDepartAt && onTransitDepart != null) {
                Box(Modifier.padding(horizontal = PhantomSpace.s4)) {
                    DepartRow(transitDepartSec, onTransitDepart)
                }
            }
        }
    }
}

// ── стани 3-5: жива смуга ────────────────────────────────────────────────

@Composable
private fun RouteBand(
    info: MapViewModel.RouteInfo,
    busy: Boolean,
    options: List<RoutePlanner.Option>,
    selectedIndex: Int,
    deadlineLine: String?,
    worstRegister: GroundRegister?,
    cameraGestureAtMs: Long,
    waypoints: List<Pair<Double, Double>> = emptyList(),
    onRemoveVia: ((Int) -> Unit)? = null,
    onSetDeadline: ((String) -> Boolean)? = null,
    onClearDeadline: (() -> Unit)? = null,
    transitDepartSec: Int? = null,
    onTransitDepart: ((Int?) -> Unit)? = null,
    onOpenManeuverList: (() -> Unit)? = null,
    /** Кому можна віддати цей маршрут. Порожньо — дії немає взагалі. */
    shareTargets: List<local.phantom.companion.core.net.peer.PeerLiveShare.Target> = emptyList(),
    /** Обрано кого. Null ховає дію разом зі списком. */
    onShareRoute: ((String) -> Unit)? = null,
    /** Чим скінчився попередній дотик. Null — нічого ще не тиснули. */
    shareHandoff: MapViewModel.RouteHandoff? = null,
    onShareHandoffSeen: () -> Unit = {},
    onSelectVariant: (Int) -> Unit,
    onProfile: (RouteProfile) -> Unit,
    onClear: () -> Unit,
    onStartGuidance: () -> Unit,
    onFlyover: () -> Unit,
) {
    val hasThread = info.itinerary != null

    var expanded by rememberSaveable { mutableStateOf(false) }
    // Раз операторів вибір зроблено — він старший за нашу здогадку. Без цього
    // прапорця смуга сама розкривалась назад після повороту екрана: композиція
    // нова, ефект спрацьовує ще раз.
    var expandChosenByHand by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hasThread) { if (hasThread && !expandChosenByHand) expanded = true }

    // Жест по мапі згортає смугу: людина потяглась дивитись землю — земля
    // головна. Порівняння з ПЕРШИМ баченим значенням, а не з нулем: інакше
    // будь-який жест до появи маршруту згортав би смугу на першому кадрі.
    val gestureAtEntry = remember { cameraGestureAtMs }
    LaunchedEffect(cameraGestureAtMs) {
        if (cameraGestureAtMs != gestureAtEntry) expanded = false
    }

    val chosen = options.getOrNull(selectedIndex)
    val cautions = routeCautions(info, chosen, deadlineLine, worstRegister, regionAt = rememberCurfewRegionAt())

    // Довідник поселень читається з диска — один раз на мету, а не двічі за
    // кожну композицію (корона і рядок відповіді питають одне й те саме імʼя).
    val destName = remember(info.destName, info.destLat, info.destLon) { destinationName(info) }

    // Список адресатів живе вище за ряд дій, який його відкриває: сам ряд
    // стоїть останнім поверхом, а список мусить розкриватись НАД ним, інакше
    // імена людей падали б під край скла.
    var picking by remember { mutableStateOf(false) }
    val modeMinutes = remember(info.distanceMeters, info.profile, info.durationSeconds) {
        estimateModeMinutes(info.distanceMeters, info.profile, info.durationSeconds)
    }

    BandGlass {
        if (info.itinerary != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s2),
            ) {
                TransitRouteLegCard(
                    itinerary = info.itinerary,
                    durationSeconds = info.durationSeconds,
                    selected = true,
                    onClick = { expandChosenByHand = true; expanded = !expanded },
                )
            }
        }
        if (expanded) {
            val c = LocalPhantomColors.current
            Grabber(onClick = { expandChosenByHand = true; expanded = false })
            RouteCrown(destName, fmtCoords(info.destLat, info.destLon))
            ProfileSegments(
                selected = info.profile,
                enabled = !busy,
                onProfile = onProfile,
                modeMinutes = modeMinutes,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
            if (options.size >= 2) {
                VariantSegments(options, selectedIndex, onSelectVariant)
                Spacer(Modifier.height(PhantomSpace.s2))
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PhantomSpace.s4),
                verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
            ) {
                cautions.forEach { CautionLine(it) }
                intelLine(chosen)?.let { SheetNote(it, maxLines = 1, tnum = true) }
                if (info.profile == RouteProfile.TRANSIT && onTransitDepart != null) {
                    DepartRow(transitDepartSec, onTransitDepart)
                }
                ArriveRow(
                    // План на обраний час стоїть на своїх годинах; живий годинник
                    // «зараз + тривалість» брехав би рівно на різницю до виїзду.
                    itinerary = info.itinerary.takeIf { transitDepartSec != null },
                    durationSeconds = info.durationSeconds,
                    deadlineSet = deadlineLine != null,
                    onSetDeadline = onSetDeadline,
                    onClearDeadline = onClearDeadline,
                )
                val paw = chosen?.variant?.parkAndWalk
                if (paw != null) {
                    ParkAndWalkLine(paw)
                }
                // Рядка витрат тут НЕМАЄ навмисно: «~N л пального» рахувався з
                // вигаданих 7,8 л/100 км — витрата авто оператора невідома.
                // Немає джерела — немає рядка, а не прочерк.

                // Віддати маршрут ОБРАНОМУ. Не «поділитись» кудись назовні:
                // список — це ті, чий ключ у нас є, поіменно, і біля кожного
                // сказано, чи лист дійде, ЩЕ ДО дотику. Сама дія живе в ряду
                // дій унизу; тут лишається тільки те, що вона відкриває.
                if (onShareRoute != null && shareTargets.isNotEmpty()) {
                    if (shareHandoff != null) {
                        // Речення живе стільки, скільки триває сама подія:
                        // «надіслано», що висить під смугою до кінця поїздки,
                        // бреше про теперішнє так само, як мовчання брехало
                        // про минуле.
                        LaunchedEffect(shareHandoff) {
                            delay(HANDOFF_LIFE_MS)
                            onShareHandoffSeen()
                        }
                        SheetNote(
                            shareHandoffLine(shareHandoff),
                            color = if (shareHandoff is MapViewModel.RouteHandoff.Sent) c.success else c.danger,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = PhantomSpace.s1),
                        )
                    }
                    if (picking) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = PhantomSpace.s1),
                            verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
                        ) {
                            shareTargets.forEach { peer ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 48.dp)
                                        .clip(CircleShape)
                                        .clickable(role = Role.Button) {
                                            picking = false
                                            onShareRoute(peer.peerId)
                                        }
                                        .padding(horizontal = PhantomSpace.s2),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    SheetBody(peer.name, fontWeight = FontWeight.Medium, maxLines = 1)
                                    SheetSmall(
                                        stringResource(
                                            when (peer.reach) {
                                                local.phantom.companion.core.net.peer.PeerLiveShare.Reach.MESH ->
                                                    R.string.peer_share_reach_nearby
                                                local.phantom.companion.core.net.peer.PeerLiveShare.Reach.RELAY ->
                                                    R.string.peer_share_reach_relay
                                                else -> R.string.peer_share_reach_queued
                                            },
                                        ),
                                        color = c.text2,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (waypoints.size > 2) {
                Spacer(Modifier.height(PhantomSpace.s2))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PhantomSpace.s4),
                    verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
                ) {
                    SheetSmall(
                        stringResource(R.string.map_route_stops_count, waypoints.size - 2),
                        color = c.text2,
                    )
                    waypoints.forEachIndexed { index, wp ->
                        if (index > 0 && index < waypoints.size - 1) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 36.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(
                                        MapGlyph.PLACE.asImageVector(),
                                        contentDescription = null,
                                        tint = c.accentText,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    SheetNote(
                                        "${index}. ${fmtCoords(wp.first, wp.second)}",
                                        color = c.text,
                                        tnum = true,
                                    )
                                }
                                if (onRemoveVia != null) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .clip(CircleShape)
                                            .clickable(role = Role.Button) { onRemoveVia(index) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Icon(
                                            MapGlyph.DISMISS.asImageVector(),
                                            contentDescription = stringResource(R.string.map_route_remove_stop),
                                            tint = c.text2,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (info.itinerary != null) {
                Spacer(Modifier.height(PhantomSpace.s2))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = THREAD_MAX)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = PhantomSpace.s4),
                ) {
                    ThreadTimeline(info.itinerary)
                }
            }
            Spacer(Modifier.height(PhantomSpace.s4))
            RouteActions(
                onOpenManeuverList = onOpenManeuverList,
                onShare = if (onShareRoute != null && shareTargets.isNotEmpty()) {
                    { picking = !picking }
                } else {
                    null
                },
                onFlyover = onFlyover,
                onClear = onClear,
            )
            Spacer(Modifier.height(PhantomSpace.s2))
        }

        AnswerRow(
            info = info,
            destName = destName,
            busy = busy,
            expanded = expanded,
            topCaution = cautions.firstOrNull().takeIf { !expanded },
            onToggle = { expandChosenByHand = true; expanded = !expanded },
            onStartGuidance = onStartGuidance,
        )
    }
}

/**
 * Чим скінчився дотик — реченням, а не станом.
 *
 * «Надіслано» без слова про шлях — це половина правди: маршрут, що ліг у
 * чергу, і маршрут, що вже поїхав, для того, хто його чекає, різні речі.
 */
@Composable
private fun shareHandoffLine(handoff: MapViewModel.RouteHandoff): String = when (handoff) {
    MapViewModel.RouteHandoff.NoRoute -> stringResource(R.string.peer_route_no_plan)
    is MapViewModel.RouteHandoff.TooBig -> stringResource(
        if (handoff.tooManyStops) R.string.peer_route_too_many_stops else R.string.peer_route_unsendable,
    )
    is MapViewModel.RouteHandoff.NoKey -> stringResource(R.string.peer_route_no_key, handoff.name)
    is MapViewModel.RouteHandoff.Sent -> stringResource(
        when (handoff.reach) {
            local.phantom.companion.core.net.peer.PeerLiveShare.Reach.MESH ->
                R.string.peer_route_sent_mesh
            local.phantom.companion.core.net.peer.PeerLiveShare.Reach.RELAY ->
                R.string.peer_route_sent_relay
            else -> R.string.peer_route_sent_queued
        },
        handoff.name,
    )
}

/** Скільки живе речення про дотик. Довше — і воно вже про минуле. */
private const val HANDOFF_LIFE_MS = 8_000L

@Composable
private fun Grabber(onClick: () -> Unit) {
    val c = LocalPhantomColors.current
    val label = stringResource(R.string.route_collapse)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(GRABBER_ZONE)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(GRABBER_W)
                .height(GRABBER_H)
                .clip(CircleShape)
                .background(c.lineStrong),
        )
    }
}

/**
 * Рядок відповіді — постійний нижній поверх смуги.
 *
 * Гліф профілю · час · відстань · мета · СТАРТ. Пауза перед «стартом» більша за
 * решту проміжків: між відповіддю і дією має бути видима пауза, інакше смуга
 * читається одним стосом.
 */
@Composable
private fun AnswerRow(
    info: MapViewModel.RouteInfo,
    /** Імʼя мети, уже знайдене смугою один раз: див. [destinationName]. */
    destName: String?,
    busy: Boolean,
    expanded: Boolean,
    topCaution: RouteCaution?,
    onToggle: () -> Unit,
    onStartGuidance: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    // Координати відомі завжди — але в згорнутому рядку для них немає місця, і
    // вони обрізались до «49.9737, 2…», тобто переставали бути координатами.
    // Тому в рядку стоїть слово, а числа живуть там, де їх видно цілими: у
    // короні розгорнутої смуги й у тому, що читає голос.
    val destCoords = fmtCoords(info.destLat, info.destLon)
    val destination = destName ?: stringResource(R.string.route_dest_point)
    val duration = fmtDuration(res, info.durationSeconds)
    val answerCd = stringResource(R.string.route_answer_cd, duration, destName ?: destCoords) +
        ", " + stringResource(if (expanded) R.string.route_collapse else R.string.route_expand)

    var dragAccum by remember { mutableFloatStateOf(0f) }
    val drag = rememberDraggableState { delta ->
        dragAccum += delta
        if (dragAccum < -DRAG_TRIGGER_PX && !expanded) { onToggle(); dragAccum = 0f }
        if (dragAccum > DRAG_TRIGGER_PX && expanded) { onToggle(); dragAccum = 0f }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = PhantomSpace.s4,
                end = PhantomSpace.s3,
                // Згорнута смуга віддає 4dp верхнього поля ґраберу, і рівно на
                // стільки ж зменшує саме поле: 4+4+48+8 = 64dp, як заявлено.
                top = if (expanded) PhantomSpace.s2 else PhantomSpace.s1,
                bottom = PhantomSpace.s2,
            ),
    ) {
        if (!expanded) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(GRABBER_W)
                    .height(GRABBER_H)
                    .clip(CircleShape)
                    .background(c.lineStrong),
            )
        }
        Row(
            // IntrinsicSize.Min — не косметика, а межа. `fillMaxHeight()` на
            // дитині всередині бере СТІЛЬКИ, скільки дає батько; поки смуга
            // жила у верхній колонці, її обмежували сусіди, а в нижньому
            // кластері вона дістала цілий екран і розтягнулась на нього — біла
            // картка на всю мапу з відповіддю посеред неї. Тепер висоту диктує
            // вміст, а `heightIn` лишається підлогою для пальця.
            modifier = Modifier
                .fillMaxWidth()
                .height(androidx.compose.foundation.layout.IntrinsicSize.Min)
                .heightIn(min = PhantomTouchFloor),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Button, onClick = onToggle)
                    .draggable(drag, Orientation.Vertical, onDragStopped = { dragAccum = 0f })
                    .semantics(mergeDescendants = true) {
                        contentDescription = answerCd
                        role = Role.Button
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
            ) {
                Icon(
                    imageVector = profileIcon(info.profile),
                    contentDescription = null,
                    tint = c.text2,
                    modifier = Modifier.size(ICON),
                )
                // Час — найбільший текст смуги і лівий якір відповіді: title1
                // (22sp) з табличними цифрами, щоб «12» і «18» мали одну ширину.
                SheetText(
                    duration,
                    PhantomText.title1,
                    color = if (busy) c.text2 else c.text,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    tnum = true,
                )
                // Транзит замість кілометрів каже пересадки: для нього це і є
                // ціна маршруту. Нуль пересадок мовчить — «0 пересадок» гірше
                // за тишу.
                val transfers = transferCount(info.itinerary)
                val second = if (info.profile == RouteProfile.TRANSIT && transfers > 0) {
                    pluralStringResource(R.plurals.route_transfers, transfers, transfers)
                } else {
                    fmtDistance(res, info.distanceMeters)
                }
                SheetNote("· $second", maxLines = 1, tnum = true)
                SheetBody(
                    destination,
                    color = if (busy) c.text2 else c.text,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (busy) {
                    SheetNote(stringResource(R.string.map_route_busy), color = c.accentText, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(PhantomSpace.s3))
            // ЄДИНЕ залите місце в усій смузі — і тому воно не мусить бути ще й
            // найширшим. Знято дві речі: підлогу ширини (68dp, через яку кнопка
            // розтягувалась і на короткому слові) і провідний гліф — рядок уже
            // починається гліфом профілю, а два мальованих знаки на одній
            // 48-точковій лінії читаються як орнамент. Лишились слово і колір;
            // решта дій довкола стала обведеною, тож ієрархія тримається
            // заливкою, а не розміром. 03.10: та сама головна дія, що й на
            // аркушах мапи — `accent` з чорнилом `onAccent`, а не стара PhantomChip.
            MapPillPrimary(
                label = stringResource(R.string.route_start),
                onClick = onStartGuidance,
            )
        }
        // Застереження стоїть МІЖ числами і пальцем навмисно: його неможливо
        // перескочити пальцем, не перескочивши оком.
        if (topCaution != null) {
            Spacer(Modifier.height(PhantomSpace.s1))
            CautionLine(topCaution)
        }
    }
}

// ── профілі — сегментований ряд, без прокрутки ───────────────────────────

/**
 * Чотири профілі однією формою на всю ширину.
 *
 * Прокрутка з-під згасання показувала четвертий режим тринадцятьма пікселями
 * слова — вибір, якого не видно цілим, не є вибором. `weight(1f)` ділить ширину,
 * тож ряд влазить за побудовою: слова 4-7 літер при 11sp лишаються цілими навіть
 * на масштабі шрифту 1.3. Пʼята комірка ще влазить; якщо профілів стане більше
 * пʼяти — ряд стає ґраткою 2×N, НЕ прокруткою.
 */
internal fun estimateModeMinutes(
    distanceMeters: Double,
    activeProfile: RouteProfile,
    activeDurationSeconds: Double,
): Map<RouteProfile, Int> {
    val d = distanceMeters.coerceAtLeast(0.0)
    val base = mapOf(
        RouteProfile.FOOT to (d / 83.33).roundToInt().coerceAtLeast(1),
        RouteProfile.TRANSIT to (3.0 + d / 200.0).roundToInt().coerceAtLeast(1),
        RouteProfile.BIKE to (d / 200.0).roundToInt().coerceAtLeast(1),
        RouteProfile.CAR to (4.0 + d / 400.0).roundToInt().coerceAtLeast(1),
    )
    if (activeDurationSeconds <= 0.0) return base
    val activeMin = (activeDurationSeconds / 60.0).roundToInt().coerceAtLeast(1)
    return base + (activeProfile to activeMin)
}

@Composable
internal fun TransitRouteLegCard(
    itinerary: Itinerary,
    durationSeconds: Double,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    val totalSec = if (durationSeconds > 0.0) durationSeconds else itinerary.durationSec.toDouble()
    val firstRide = itinerary.legs.filterIsInstance<Itinerary.Leg.Ride>().firstOrNull()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(PhantomRadius.card))
            .background(c.raised)
            .border(
                width = 1.dp,
                color = if (selected) c.accentText else c.line,
                shape = RoundedCornerShape(PhantomRadius.card),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = PhantomSpace.s3, vertical = PhantomSpace.s2),
        verticalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s1),
            ) {
                itinerary.legs.forEachIndexed { index, leg ->
                    if (index > 0) {
                        SheetSmall("›", color = c.text3)
                    }
                    when (leg) {
                        is Itinerary.Leg.Access -> {
                            val mins = (leg.seconds / 60.0).roundToInt().coerceAtLeast(1)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(PhantomRadius.chip))
                                    .background(c.surface)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = (if (leg.mode == AccessMode.DRIVE) MapGlyph.CAR else MapGlyph.FOOT).asImageVector(),
                                    contentDescription = null,
                                    tint = c.text2,
                                    modifier = Modifier.size(14.dp),
                                )
                                SheetSmall(mins.toString(), color = c.text2, maxLines = 1, tnum = true)
                            }
                        }
                        is Itinerary.Leg.Ride -> {
                            val colour = TransitModeTint.fromHex(transitLegHex(leg.colour, leg.mode))
                                ?: TransitModeTint.of(leg.mode)
                            val badgeText = leg.routeLabel.ifBlank { stringResource(modeNameRes(leg.mode)) }
                            SheetNote(
                                badgeText,
                                color = TransitModeTint.inkOn(colour),
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                tnum = true,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(PhantomRadius.chip))
                                    .background(colour)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                        is Itinerary.Leg.Transfer -> {
                            val mins = (leg.seconds / 60.0).roundToInt().coerceAtLeast(1)
                            Row(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(PhantomRadius.chip))
                                    .background(c.surface)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Icon(
                                    imageVector = MapGlyph.FOOT.asImageVector(),
                                    contentDescription = null,
                                    tint = c.text2,
                                    modifier = Modifier.size(14.dp),
                                )
                                SheetSmall(mins.toString(), color = c.text2, maxLines = 1, tnum = true)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.width(PhantomSpace.s2))
            SheetBody(
                fmtDuration(res, totalSec),
                color = if (selected) c.accentText else c.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                tnum = true,
            )
        }
        val subtitle = if (firstRide != null) {
            rideTimesLine(firstRide) { res.getString(R.string.route_thread_stops, it) }
        } else {
            val arrive = (if (itinerary.hasModelledTimes) "≈" else "") + fmtSec(itinerary.arriveSec)
            stringResource(R.string.route_depart_arrive, fmtSec(itinerary.departSec), arrive)
        }
        SheetNote(subtitle, color = c.text2, maxLines = 1, tnum = true)
    }
}

@Composable
private fun ProfileSegments(
    selected: RouteProfile,
    enabled: Boolean,
    onProfile: (RouteProfile) -> Unit,
    modeMinutes: Map<RouteProfile, Int> = emptyMap(),
) {
    val c = LocalPhantomColors.current
    val minUnit = stringResource(R.string.map_unit_min)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4)
            .height(PROFILE_ROW)
            .clip(CircleShape)
            .segmentEdge(c.raised)
            .alpha(if (enabled) 1f else RECESSED),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val profiles = listOf(
            RouteProfile.FOOT,
            RouteProfile.TRANSIT,
            RouteProfile.BIKE,
            RouteProfile.CAR,
        )
        profiles.forEachIndexed { i, p ->
            if (i > 0) Seam(c.line)
            val word = stringResource(profileLabelRes(p))
            val mins = modeMinutes[p]
            val label = if (mins != null) "$word $mins $minUnit" else word
            SegmentCell(
                selected = p == selected,
                enabled = enabled,
                spokenName = stringResource(
                    if (p == selected) R.string.route_profile_cd_selected else R.string.route_profile_cd,
                    label,
                ),
                onClick = { onProfile(p) },
            ) { ink ->
                Icon(
                    imageVector = profileIcon(p),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(ICON),
                )
                SheetSmall(
                    label,
                    color = ink,
                    maxLines = 1,
                    fontWeight = if (p == selected) FontWeight.SemiBold else FontWeight.Medium,
                    tnum = mins != null,
                )
            }
        }
    }
}

// ── варіанти — абсолюти поруч, слово характеру лише з математики ─────────

/**
 * Варіанти абсолютами, не приростами.
 *
 * «+7 хв» — дельта без осі: плюс до чого? Людина порівнює «49 проти 56»
 * швидше, ніж підписані прирости. Слово під числом каже характер — і тільки
 * той, який дані витримують: «швидший» і «коротший» — чиста математика,
 * «освітлено N%» — виміряна розвідка. Слова «безпечніший» тут не буде ніколи:
 * порівнювальної форми безпеки між варіантами дані не мають.
 */
@Composable
private fun VariantSegments(
    options: List<RoutePlanner.Option>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    val marks = remember(options) { variantMarks(options) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4)
            .height(VARIANT_ROW)
            .clip(CircleShape)
            .segmentEdge(c.raised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.take(MAX_VARIANTS).forEachIndexed { i, opt ->
            if (i > 0) Seam(c.line)
            val value = fmtDuration(res, opt.variant.durationSeconds)
            val word = markWord(marks.getOrNull(i))
            SegmentCell(
                selected = i == selectedIndex,
                enabled = true,
                spokenName = stringResource(
                    if (i == selectedIndex) R.string.route_variant_cd_selected
                    else R.string.route_variant_cd,
                    i + 1,
                    listOfNotNull(value, word).joinToString(" · "),
                ),
                onClick = { onSelect(i) },
            ) { ink ->
                SheetText(value, PhantomText.headline, color = ink, maxLines = 1, tnum = true)
                if (word != null) SheetSmall(word, color = ink, maxLines = 1)
            }
        }
    }
}

@Composable
private fun markWord(mark: VariantMark?): String? = when (mark) {
    VariantMark.Fastest -> stringResource(R.string.route_variant_fastest)
    VariantMark.Shortest -> stringResource(R.string.route_variant_shortest)
    // Коротка форма, не рядок розвідки: у комірці ~100dp довге слово стало б
    // трьома літерами й трьома крапками.
    is VariantMark.Lit -> stringResource(R.string.route_variant_lit, mark.percent)
    null -> null
}

/**
 * Комірка колії — одна для вибору і для дії.
 *
 * [selected] `null` означає «вибору тут нема взагалі»: комірка-дієслово. Це не
 * косметика — від цього залежить і роль у дереві доступності (`Tab` проти
 * `Button`), і те, що жодна комірка ряду дій не має стану «обрано».
 *
 * ОБРАНІСТЬ МАЛЮЄТЬСЯ ВСТАВНОЮ ПІГУЛКОЮ, не заливкою комірки. Заливка йшла на
 * всю висоту й ширину, тобто впритул до краю колії та її власного канта — на
 * кадрі власника 18.09 вибране «пішки» читалось як гірчичний блок ІНШОЇ форми,
 * ніж колія, і сидів він у ній криво. Вставка на [SELECT_INSET] дає пігулку
 * всередині пігулки: форма своя, край колії цілий.
 *
 * Обраність і далі несе не тільки колір (це було б невидимо під червоним
 * світлом і в сірому): своя форма, свій кант і напівжирне слово.
 */
@Composable
private fun RowScope.SegmentCell(
    selected: Boolean?,
    enabled: Boolean,
    // Не `contentDescription`: усередині лямбди семантики просте імʼя резолвиться
    // у властивість приймача, а вона тільки для запису — читання кидає.
    spokenName: String,
    onClick: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.(ink: Color) -> Unit,
) {
    val c = LocalPhantomColors.current
    // Обране — як на вебі (ListFolders): мʼякий бурштин, бурштинова волосина,
    // бурштинове слово. НЕ залитий бурштин: залита на смузі одна дія — старт.
    val ink = when (selected) {
        true -> c.accentText
        false -> c.text2
        null -> c.text
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxHeight()
            .then(
                when {
                    !enabled -> Modifier
                    selected == null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                    else -> Modifier.selectable(selected = selected, role = Role.Tab, onClick = onClick)
                },
            )
            .semantics(mergeDescendants = true) { contentDescription = spokenName },
        contentAlignment = Alignment.Center,
    ) {
        if (selected == true) {
            Box(
                Modifier
                    .matchParentSize()
                    .padding(SELECT_INSET)
                    .sheetSegment(true),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            content(ink)
        }
    }
}

// ── ряд дій — ті самі комірки, тільки дієслова ───────────────────────────

/**
 * Усе, що можна зробити з побудованим маршрутом, — ОДНІЄЮ колією.
 *
 * Вирок власника 18.09: «кнопки скинути, обліт, надіслати маршрут, список
 * маневрів і прибуття — все воно виглядає по-…». Він назвав не кожну кнопку
 * окремо, а стос: чотири обведені пігулки різної ширини, одна під одною,
 * половина з них акцентна, з емодзі замість знаків. «Надіслати маршрут» важило
 * на кадрі стільки ж, скільки «старт».
 *
 * Тепер це одна колія рівних комірок тієї самої форми, що й вибір пересування,
 * — і жодна з них не залита. Заливка в смузі лишилась рівно одна: «старт».
 * Ієрархія тепер у КОЛЬОРІ, а не в розмірі.
 *
 * Порядок не довільний: маневри й обліт — те, що роблять ДО виїзду, надіслати —
 * рідше, скинути — останнім, найдалі від великого пальця. Знищення маршруту й
 * далі коштує двох дотиків (розгорнути → скинути): воно має бути довшим шляхом,
 * ніж старт.
 *
 * Комірок від двох до чотирьох: адресатів може не бути (немає нікого з ключем),
 * списку маневрів — теж. Ряд ділить ширину порівну, тож будь-яка кількість
 * лягає рівно.
 */
@Composable
private fun RouteActions(
    onOpenManeuverList: (() -> Unit)?,
    onShare: (() -> Unit)?,
    onFlyover: () -> Unit,
    onClear: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val actions = buildList {
        onOpenManeuverList?.let { add(Triple(R.string.route_maneuvers, MapGlyph.GUIDE, it)) }
        add(Triple(R.string.route_flyover, MapGlyph.FLYOVER, onFlyover))
        onShare?.let { add(Triple(R.string.peer_route_share_chip, MapGlyph.HANDOFF, it)) }
        add(Triple(R.string.route_clear, MapGlyph.DISMISS, onClear))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4)
            .height(ACTION_ROW)
            .clip(CircleShape)
            .segmentEdge(c.raised),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        actions.forEachIndexed { i, (labelRes, glyph, onClick) ->
            if (i > 0) Seam(c.line)
            val word = stringResource(labelRes)
            SegmentCell(
                selected = null,
                enabled = true,
                spokenName = stringResource(R.string.route_action_cd, word),
                onClick = onClick,
            ) { ink ->
                Icon(
                    imageVector = glyph.asImageVector(),
                    contentDescription = null,
                    tint = ink,
                    modifier = Modifier.size(ICON),
                )
                SheetSmall(word, color = ink, maxLines = 1)
            }
        }
    }
}

@Suppress("UNUSED_PARAMETER")
@Composable
private fun RowScope.Seam(colour: Color) {
    Box(Modifier.width(SEAM))
}

/** Колія сегментів — на тон вище за смугу (`raised` на `surface`), без канта. */
private fun Modifier.segmentEdge(colour: Color): Modifier = this.background(colour)

// ── застереження ─────────────────────────────────────────────────────────

/** Що на цьому маршруті міняє рішення. Порядок — порядок ваги. */
internal sealed interface RouteCaution {
    // Тут БУЛА `OfflineClassless` — «офлайн · без класу доріг — перевіряй
    // очима». Вирок власника 18.09: рядок стояв під кожним офлайновим
    // маршрутом, нічого не міняв у рішенні й наказував дивитись на дорогу.
    // Застереження — це те, що міняє рішення; «звідки взялися хвилини» —
    // це властивість самого маршруту, і вона переїхала в деталі
    // (ManeuverListSheet ← RouteBasis.lines).
    data class CurfewCollision(val match: CurfewMatch) : RouteCaution
    data class Deadline(val line: String) : RouteCaution
    data class Register(val register: GroundRegister) : RouteCaution
    data class Barrier(
        val description: String = "",
        @StringRes val labelRes: Int? = null,
        val count: Int = 0,
    ) : RouteCaution
    /**
     * БРІД — на шляху позначено переправу вбрід. [count] — скільки саме доріг
     * маршруту її несуть, а не скільки метрів: брід — це подія в точці.
     *
     * Каже, що переправа ПОЗНАЧЕНА, і не каже, що вона проїзна. Див.
     * [local.phantom.companion.core.net.map.RouteBasis.fordLine] — формулювання
     * живе там і тут не дублюється.
     */
    data class Ford(val count: Int) : RouteCaution
    data object TrunkShoulder : RouteCaution
    data object Modelled : RouteCaution
    data object NoVariantsVia : RouteCaution
}

/**
 * The feature layer's region resolver: core modules must not reach into the
 * bundled region geometry, so the lookup is handed down. A point inside one
 * of the active pack's rings resolves to that pack's id; anywhere else — no
 * pack, no region, no rule, no warning.
 */
@Composable
internal fun rememberCurfewRegionAt(): (Double, Double) -> String? {
    val ctx = LocalContext.current
    // 722 КБ GeoJSON — перший парс лише на IO; до готовності регіон чесно невідомий.
    val shapes by produceState<OblastShapes?>(null, ctx) {
        value = withContext(Dispatchers.IO) { OblastShapes.load(ctx) }
    }
    return { lat, lon ->
        if (shapes?.oblastAt(lat, lon) != null) {
            local.phantom.companion.core.data.country.CountryPackRegistry.current()?.id
        } else {
            null
        }
    }
}

internal fun routeCautions(
    info: MapViewModel.RouteInfo,
    chosen: RoutePlanner.Option?,
    deadlineLine: String?,
    worstRegister: GroundRegister?,
    nowEpochMs: Long = System.currentTimeMillis(),
    /**
     * Which region the arrival point falls in, or null. Defaulting to «no
     * region» is the honest default: no region, no rule, no warning.
     */
    regionAt: ((Double, Double) -> String?)? = null,
): List<RouteCaution> = buildList {
    // ПЕРШИМ, і це не смак: згорнута смуга показує РІВНО ОДНЕ застереження
    // (`cautions.firstOrNull()`), а переправа міняє, чи людина ВЗАГАЛІ проїде.
    (chosen?.variant?.fordCrossings ?: 0).takeIf { it > 0 }?.let { add(RouteCaution.Ford(it)) }
    // `speedBasis == "ASSUMED"` тут НЕ перевіряється навмисно — див. коментар
    // над [RouteCaution]. Раніше саме воно й забирало це єдине місце в броду:
    // 2 348 із 2 896 позначених бродів країни стоять на `highway=track`, а
    // `track` навмисно не має класової швидкості, тобто обидва спрацьовували
    // разом на кожному маршруті, де брід узагалі має значення.
    if (deadlineLine != null) add(RouteCaution.Deadline(deadlineLine))
    if (worstRegister?.staleNoteRes() != null) add(RouteCaution.Register(worstRegister))
    if (info.itinerary?.hasModelledTimes == true) add(RouteCaution.Modelled)

    // Law 3 (D8): the curfew line exists only where a verified rule covers
    // the ARRIVAL point. No resolver, no region, no rule → nothing is said.
    val arrivalMs = nowEpochMs + (info.durationSeconds * 1000.0).toLong()
    val arrivalRegion = regionAt?.invoke(info.destLat, info.destLon)
    CurfewRules.matchAt(arrivalRegion, arrivalMs)?.let { add(RouteCaution.CurfewCollision(it)) }

    val v = chosen?.variant
    if (v != null) {
        if (v.trunkShoulderMeters >= 150.0) add(RouteCaution.TrunkShoulder)
        
        val intelReport = (chosen.intel as? RoutePlanner.Intel.Ready)?.report
        if (intelReport != null && intelReport.barriers.isNotEmpty()) {
            val count = intelReport.barriers.size
            val hasCheckpoints = intelReport.barriers.any {
                it.kind.contains("checkpoint") || it.kind.contains("border")
            }
            val hasTolls = intelReport.barriers.any { it.kind.contains("toll") }
            val (resId, desc) = when {
                hasCheckpoints -> R.string.map_route_barriers_stops to "\u041a\u041f\u041f / \u0431\u043b\u043e\u043a\u043f\u043e\u0441\u0442\u0438 ($count)"
                hasTolls -> R.string.map_route_barriers_tolls to "\u041f\u043b\u0430\u0442\u043d\u0456 \u0434\u0456\u043b\u044f\u043d\u043a\u0438 ($count)"
                else -> R.string.map_route_barriers_gates to "\u0428\u043b\u0430\u0433\u0431\u0430\u0443\u043c\u0438 ($count)"
            }
            add(RouteCaution.Barrier(description = desc, labelRes = resId, count = count))
        } else {
            val barrierStep = v.steps.firstOrNull { step ->
                val n = step.roadName.lowercase()
                n.contains("шлагбаум") || n.contains("checkpoint") || n.contains("блокпост") ||
                    step.type.contains("barrier") || step.type.contains("toll")
            }
            if (barrierStep != null) {
                if (barrierStep.roadName.isNotBlank()) {
                    add(RouteCaution.Barrier(description = barrierStep.roadName))
                } else {
                    add(RouteCaution.Barrier(labelRes = R.string.map_route_barriers_gates, count = 1))
                }
            }
        }
    }

    // Останнім: у згорнутій смузі місце одне, і воно належить тому, що міняє
    // рішення, а не тому, чого просто немає.
    if (info.viaCount > 0) add(RouteCaution.NoVariantsVia)
}

@Composable
private fun CautionLine(caution: RouteCaution) {
    val c = LocalPhantomColors.current
    when (caution) {
        // Names the window and cites the rule that says so. It never says
        // «рух заборонено»: the app is not the authority, the source is.
        is RouteCaution.CurfewCollision -> SheetNote(
            stringResource(
                R.string.route_curfew_window,
                caution.match.atHhMm,
                caution.match.windowHhMm,
                caution.match.rule.source,
                caution.match.rule.verifiedAt,
            ),
            color = c.danger,
            maxLines = 2,
        )
        is RouteCaution.Barrier -> {
            val label = if (caution.labelRes != null) {
                stringResource(caution.labelRes, caution.count)
            } else {
                caution.description.ifBlank { stringResource(R.string.mark_gate) }
            }
            SheetNote(
                stringResource(R.string.map_route_on_route, label),
                color = c.warning,
                maxLines = 1,
            )
        }
        is RouteCaution.Ford -> SheetNote(
            local.phantom.companion.core.net.map.RouteBasis.fordLine(caution.count)
                ?.replaceFirstChar { it.uppercase() }.orEmpty(),
            color = c.warning,
            maxLines = 2,
        )
        RouteCaution.TrunkShoulder -> SheetNote(
            stringResource(R.string.map_route_trunk_shoulder),
            color = c.warning,
            maxLines = 1,
        )
        is RouteCaution.Deadline -> SheetNote(
            caution.line,
            color = c.accentText,
            maxLines = 1,
            tnum = true,
        )
        is RouteCaution.Register -> SheetNote(
            stringResource(R.string.nav_route_stale),
            color = caution.register.tint(c.isDark),
            maxLines = 1,
        )
        RouteCaution.Modelled -> SheetNote(
            stringResource(R.string.route_modelled),
            maxLines = 1,
        )
        RouteCaution.NoVariantsVia -> SheetNote(
            stringResource(R.string.route_variants_via),
            maxLines = 1,
        )
    }
}

@Composable
private fun ArrivalLine(durationSeconds: Double) {
    // Годинник живий ЛИШЕ поки смуга розгорнута: згорнута смуга не тримає
    // жодного таймера. «55 хв» треба додавати в голові до поточного часу,
    // «о 18:03» відповідає одразу — але застиглий годинник брехав би тим
    // упевненіше, чим довше на нього дивляться.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(ARRIVAL_TICK_MS)
        }
    }
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val clock = remember(locale) { java.text.SimpleDateFormat("HH:mm", locale) }
    val arrival = remember(now, durationSeconds, clock) {
        clock.format(java.util.Date(now + (durationSeconds * 1000.0).toLong()))
    }
    SheetNote(stringResource(R.string.nav_eta, arrival), maxLines = 1, tnum = true)
}

/**
 * Прибуття і відлік — ОДИН рядок, одна ручка.
 *
 * Було: рядок «прибуття 22:26» простим текстом, а під ним окремий ряд із
 * чотирьох саморобних пігулок — «+15 хв», «+30 хв», «+45 хв» і «⏱ Точний час»,
 * кожна з власною заливкою й кантом, жодна не з нашої родини чипів. Разом із
 * рядом «Прибути:» це пʼять речей заради однієї величини.
 *
 * Стало: число ліворуч, одна ручка праворуч. Пресети зняті свідомо — «прибути
 * за 15 хвилин» і так вимагає подивитись на годинник, а вибір часу дає ту саму
 * відповідь одним діалогом. Коли відлік уже стоїть, ручка міняє значення на
 * «зняти»: сам відлік у цей час уже названий рядком застереження вище, тож
 * друге його написання було б повтором.
 */
@Composable
private fun ArriveRow(
    itinerary: Itinerary?,
    durationSeconds: Double,
    deadlineSet: Boolean,
    onSetDeadline: ((String) -> Boolean)?,
    onClearDeadline: (() -> Unit)?,
) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (itinerary != null) DepartArriveLine(itinerary) else ArrivalLine(durationSeconds)
        when {
            deadlineSet && onClearDeadline != null -> MapPillQuiet(
                label = stringResource(R.string.route_arrive_clear),
                onClick = onClearDeadline,
            )
            !deadlineSet && onSetDeadline != null -> MapPillQuiet(
                label = stringResource(R.string.route_arrive_by),
                onClick = { pickTime(ctx) { hh, mm -> onSetDeadline("%02d:%02d".format(hh, mm)) } },
            )
        }
    }
}

/** Годинник апарата — одним місцем: три копії цього виклику розходились. */
private fun pickTime(ctx: android.content.Context, onPicked: (Int, Int) -> Unit) {
    val cal = Calendar.getInstance()
    TimePickerDialog(
        ctx,
        { _, hourOfDay, minute -> onPicked(hourOfDay, minute) },
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        true,
    ).show()
}

// ── час виїзду — ручка транзитного планувальника ─────────────────────────

/**
 * «Виїзд: зараз / о H:MM». Лише виїзд: «прибути до…» вимагає зворотного
 * RAPTOR, якого немає, а вибору дати немає, бо в розкладі доба одна й
 * вічна — ручка без механізму була б мертвою.
 */
@Composable
private fun DepartRow(departSec: Int?, onDepart: (Int?) -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PhantomSpace.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SheetNote(stringResource(R.string.route_depart_label), maxLines = 1)
        // Пара «зараз / о котрій» — сегменти з тим самим «обраним», що й
        // режими: мʼякий бурштин, роль `Tab`, мішень під палець.
        val nowChosen = departSec == null
        SheetSegments(Modifier.weight(1f)) {
            SheetSegment(
                label = stringResource(R.string.route_depart_now),
                selected = nowChosen,
                onClick = { onDepart(null) },
            )
            SheetSegment(
                label = if (departSec != null) {
                    stringResource(R.string.route_depart_at, fmtSec(departSec))
                } else {
                    stringResource(R.string.route_depart_pick)
                },
                selected = !nowChosen,
                onClick = { pickTime(ctx) { hh, mm -> onDepart(hh * 3600 + mm * 60) } },
            )
        }
    }
}

/** Обидва кінці плану на обраний час; «≈» на прибутті — коли час модельний. */
@Composable
private fun DepartArriveLine(itin: Itinerary) {
    val arrive = (if (itin.hasModelledTimes) "≈" else "") + fmtSec(itin.arriveSec)
    SheetNote(
        stringResource(R.string.route_depart_arrive, fmtSec(itin.departSec), arrive),
        maxLines = 1,
        tnum = true,
    )
}

// Тут був `QuietActions` — обліт і «скинути» двома чипами по краях рядка.
// Вони не зникли, а переїхали до [RouteActions] разом із «маневрами» і
// «надіслати»: пʼять дій в одній колії замість стосу пігулок різної ширини.
// Урок, який `QuietActions` носив у своєму KDoc, лишається в силі й там:
// значок БЕЗ слова тут уже провалився один раз — кнопка була 48dp, з назвою і
// в дереві доступності, і водночас невидима, бо йшла `inkSecondary` по
// світлому склу. Тому кожна комірка ряду дій має і знак, і слово.

// ── чиста математика смуги ───────────────────────────────────────────────

/**
 * Імʼя мети трьома щаблями: назва, яку дала сама ціль → найближче поселення з
 * довідника → нічого. Кинута пальцем точка назви не має, і смуга колись
 * показувала «49,97368, …» — обрізані координати, тобто ні місце, ні число.
 */
private fun destinationName(info: MapViewModel.RouteInfo): String? =
    info.destName?.takeIf { it.isNotBlank() }
        ?: local.phantom.companion.core.geo.place.PlaceNaming
            .settlementAt(info.destLat, info.destLon)
            ?.takeIf { it.isNotBlank() }

/** Чим цей варіант вирізняється — і тільки те, що дані витримують. */
internal sealed interface VariantMark {
    data object Fastest : VariantMark
    data object Shortest : VariantMark
    data class Lit(val percent: Int) : VariantMark
}

internal fun variantMarks(options: List<RoutePlanner.Option>): List<VariantMark?> {
    if (options.size < 2) return List(options.size) { null }
    val fastest = strictMinIndex(options.map { it.variant.durationSeconds })
    val shortest = strictMinIndex(options.map { it.variant.distanceMeters })
        ?.takeIf { it != fastest }
    return options.indices.map { i ->
        when {
            fastest != null && i == fastest -> VariantMark.Fastest
            shortest != null && i == shortest -> VariantMark.Shortest
            else -> (options[i].intel as? RoutePlanner.Intel.Ready)
                ?.report?.litFraction
                ?.let { VariantMark.Lit((it * 100).roundToInt()) }
        }
    }
}

/** Нічий мінімум — не характер: спільне перше місце не називають словом. */
private fun strictMinIndex(values: List<Double>): Int? {
    val min = values.minOrNull() ?: return null
    return values.indices.singleOrNull { values[it] == min }
}

internal fun transferCount(itinerary: Itinerary?): Int =
    itinerary?.legs?.count { it is Itinerary.Leg.Transfer } ?: 0

@StringRes
internal fun profileLabelRes(profile: RouteProfile): Int = when (profile) {
    RouteProfile.FOOT -> R.string.route_profile_foot
    RouteProfile.CAR -> R.string.route_profile_car
    RouteProfile.BIKE -> R.string.route_profile_bike
    RouteProfile.TRANSIT -> R.string.route_profile_transit
}

internal fun profileIcon(profile: RouteProfile): ImageVector = when (profile) {
    RouteProfile.FOOT -> MapGlyph.FOOT.asImageVector()
    RouteProfile.CAR -> MapGlyph.CAR.asImageVector()
    RouteProfile.BIKE -> MapGlyph.BIKE.asImageVector()
    RouteProfile.TRANSIT -> MapGlyph.BUS.asImageVector()
}

/**
 * Корона панелі: спершу ІМʼЯ мети, під ним — числа.
 *
 * Заголовком стояли сирі градуси («49.8337, 18.1606»), навіть коли внизу тієї ж
 * панелі мета вже звалась «VŠB CPIT». Градуси — не назва місця: вони нічого не
 * кажуть тому, хто дивиться, і займають найпомітніший рядок.
 *
 * Координати НЕ зникають: їх читають вголос у рацію, і це єдине, що відоме про
 * точку, кинуту на землю пальцем. Вони лишаються другим рядком, у моно, там,
 * де для них є ширина — тобто рівно там, де вони корисні й нікому не заважають.
 */
@Composable
private fun RouteCrown(name: String?, coords: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PhantomSpace.s4, vertical = PhantomSpace.s1),
    ) {
        SheetText(
            name ?: stringResource(R.string.route_dest_point),
            PhantomText.headline,
            maxLines = 1,
        )
        SheetNote(text = coords, maxLines = 1, tnum = true)
    }
}

private fun fmtCoords(lat: Double, lon: Double): String =
    // Крапка, не кома, і чотири знаки, не пʼять. Українська локаль давала
    // «49,97368, 28,79453» — три коми в рядку, який людина читає вголос у
    // рацію і не чує, де кінчається широта. Чотири знаки — це ≈11 м, більше
    // для мети маршруту не має сенсу.
    String.format(java.util.Locale.US, "%.4f, %.4f", lat, lon)

@Composable
private fun intelLine(opt: RoutePlanner.Option?): String? = when (val intel = opt?.intel) {
    is RoutePlanner.Intel.Ready -> intel.report.classShares.firstOrNull()
        ?.let { (klass, share) ->
            "$klass ${(share * 100).toInt()}%" +
                (
                    intel.report.litFraction?.let { lit ->
                        " · " + stringResource(R.string.route_intel_lit, "${(lit * 100).toInt()}%")
                    } ?: ""
                    )
        }
    RoutePlanner.Intel.Loading -> stringResource(R.string.route_intel_loading_line)
    else -> null
}

// ── НИТКА — the leg-by-leg transit timeline ─────────────────────────────

@Composable
private fun ThreadTimeline(itin: Itinerary) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    val tight = remember(itin) {
        local.phantom.companion.core.data.transit.ConnectionRisk.tight(
            itin,
            local.phantom.companion.core.data.transit.TransitDelayMemory.shared,
        )
    }
    itin.legs.forEachIndexed { i, leg ->
        when (leg) {
            is Itinerary.Leg.Access -> {
                val mode = res.getString(
                    if (leg.mode == AccessMode.DRIVE) R.string.route_thread_drive
                    else R.string.route_thread_walk,
                )
                TimelineRow(
                    colour = c.text2,
                    glyph = if (leg.mode == AccessMode.DRIVE) MapGlyph.CAR else MapGlyph.FOOT,
                    glyphName = mode,
                    title = "$mode ${fmtDistance(res, leg.meters.toDouble())} · " +
                        fmtDuration(res, leg.seconds.toDouble()) +
                        (if (leg.estimated) " ≈" else ""),
                    sub = leg.toStopName.ifBlank {
                        res.getString(
                            if (i == 0) R.string.route_thread_to_stop else R.string.route_thread_to_goal,
                        )
                    } + (
                        if (leg.parkingSeconds > 0) {
                            " · " + res.getString(R.string.route_thread_parking, leg.parkingSeconds / 60)
                        } else {
                            ""
                        }
                        ),
                    dashed = true,
                )
            }
            is Itinerary.Leg.Ride -> {
                val mode = res.getString(modeNameRes(leg.mode))
                TimelineRow(
                    // Той самий колір, яким нитка лягла на мапу (MapTransitLayers),
                    // — інакше хребет у смузі сперечався б із лінією на склі.
                    colour = TransitModeTint.fromHex(transitLegHex(leg.colour, leg.mode))
                        ?: TransitModeTint.of(leg.mode),
                    glyph = modeGlyph(leg.mode),
                    glyphName = mode,
                    title = listOfNotNull(
                        "$mode ${leg.routeLabel}".trim(),
                        leg.headsign?.takeIf { it.isNotBlank() }?.let { res.getString(R.string.route_thread_toward, it) },
                    ).joinToString(" "),
                    sub = rideTimesLine(leg) { res.getString(R.string.route_thread_stops, it) } +
                        (leg.boardPlatform?.takeIf { it.isNotBlank() }?.let {
                            " · " + res.getString(
                                if (leg.mode == TransitMode.RAIL || leg.mode == TransitMode.METRO) R.string.route_thread_track
                                else R.string.route_thread_platform,
                                it,
                            )
                        } ?: "") +
                        (leg.liveDelaySec?.takeIf { it >= 60 }?.let { " · " + res.getString(R.string.route_thread_late, it / 60) } ?: "") +
                        (tight[i]?.let { " · " + res.getString(R.string.route_thread_tight, it) } ?: ""),
                    badge = leg.routeLabel,
                    dashed = false,
                )
            }
            is Itinerary.Leg.Transfer -> {
                val word = res.getString(R.string.route_thread_transfer)
                TimelineRow(
                    colour = c.text2,
                    glyph = MapGlyph.STOP,
                    glyphName = word,
                    title = "$word · ${fmtDuration(res, leg.seconds.toDouble())}",
                    sub = listOf(leg.fromStopName, leg.toStopName)
                        .filter { it.isNotBlank() }
                        .joinToString(" → "),
                    dashed = true,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(
    colour: Color,
    glyph: MapGlyph,
    glyphName: String,
    title: String,
    sub: String,
    dashed: Boolean,
    badge: String? = null,
) {
    val c = LocalPhantomColors.current
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = PhantomSpace.s1)) {
        // The spine: a leg-coloured bar; walks read thinner + translucent.
        Box(
            Modifier
                .width(if (dashed) SPINE_THIN else SPINE_THICK)
                .heightIn(min = SPINE_MIN)
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (dashed) colour.copy(alpha = RECESSED) else colour),
        )
        Spacer(Modifier.width(PhantomSpace.s2))
        local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon(
            glyph = glyph,
            tint = c.text2,
            modifier = Modifier.size(TIMELINE_GLYPH).semantics { contentDescription = glyphName },
        )
        Spacer(Modifier.width(PhantomSpace.s2))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (badge != null) {
                    SheetNote(
                        badge,
                        color = TransitModeTint.inkOn(colour),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        tnum = true,
                        modifier = Modifier
                            .clip(RoundedCornerShape(PhantomRadius.chip))
                            .background(colour)
                            .padding(horizontal = 5.dp),
                    )
                    Spacer(Modifier.width(PhantomSpace.s2))
                }
                SheetBody(title, fontWeight = FontWeight.Medium, maxLines = 1)
            }
            SheetNote(sub, maxLines = 2, tnum = true)
        }
    }
}

private fun modeGlyph(mode: TransitMode): MapGlyph = mode.glyph()

@StringRes
internal fun modeNameRes(mode: TransitMode): Int = when (mode) {
    TransitMode.METRO -> R.string.route_mode_metro
    TransitMode.TRAM -> R.string.route_mode_tram
    TransitMode.TROLLEYBUS -> R.string.route_mode_trolleybus
    TransitMode.BUS -> R.string.route_mode_bus
    TransitMode.RAIL -> R.string.route_mode_rail
    TransitMode.FUNICULAR -> R.string.route_mode_funicular
    TransitMode.FERRY -> R.string.route_mode_ferry
    TransitMode.OTHER -> R.string.route_mode_other
}

/**
 * Рядок однієї поїздки в нитці: «зупинка час → зупинка час · N зупинок».
 *
 * Винесено з композабла, щоб знак «≈» можна було перевірити тестом. Він тут
 * не прикраса: стоїть рівно тоді, коли хвилини модельовані, і впевнена цифра
 * замість нього — це брехня, якої не видно. Рядок «· N зупинок» не малюється
 * при нулі: «0 зупинок» — теж твердження, і воно було б вигаданим.
 */
internal fun rideTimesLine(leg: Itinerary.Leg.Ride, stopsLabel: (Int) -> String): String {
    val t = if (leg.timesAreModelled) "≈" else ""
    val stops = if (leg.intermediateStops > 0) " · " + stopsLabel(leg.intermediateStops) else ""
    return "${leg.fromStopName} $t${fmtSec(leg.departSec)} → " +
        "${leg.toStopName} $t${fmtSec(leg.arriveSec)}$stops"
}

private fun fmtSec(sec: Int): String {
    val s = sec % 86_400
    return "%d:%02d".format(s / 3600, (s % 3600) / 60)
}

private val ICON = 20.dp
private val SEAM = 1.dp

/** Наскільки вставна пігулка обраного відступає від краю комірки. */
private val SELECT_INSET = 4.dp

/** Притишене: вимкнена колія режимів, пішохідна ланка нитки. */
private const val RECESSED = 0.55f
private val BAND_ROW = 48.dp
private val PROFILE_ROW = 56.dp
private val VARIANT_ROW = 48.dp

/** Колія дій — тієї ж висоти, що й колія профілів: одна родина, один зріст. */
private val ACTION_ROW = PROFILE_ROW
private val GRABBER_ZONE = 16.dp
private val GRABBER_W = 32.dp
private val GRABBER_H = 4.dp
private val THREAD_MAX = 200.dp
private val SPINE_THIN = 2.dp
private val SPINE_THICK = 4.dp
private val SPINE_MIN = 32.dp

/** Скільки треба протягти, щоб це був намір, а не тремтіння пальця на тапі. */
private const val DRAG_TRIGGER_PX = 24f

/** OSRM більше трьох із `alternatives=true` не дає. */
private const val MAX_VARIANTS = 3

private val TIMELINE_GLYPH = 18.dp

@Composable
internal fun ParkAndWalkLine(paw: local.phantom.companion.core.net.map.ParkAndWalkLeg) {
    val c = LocalPhantomColors.current
    val res = LocalContext.current.resources
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(CircleShape)
            .background(c.raised)
            .padding(horizontal = PhantomSpace.s3, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PawLeg(MapGlyph.CAR, fmtDistance(res, paw.driveDistanceMeters), c.text)
        SheetNote("→", color = c.text3)
        PawLeg(MapGlyph.PARKING, paw.parkingName ?: stringResource(R.string.map_route_parking), c.text)
        SheetNote("→", color = c.text3)
        PawLeg(MapGlyph.FOOT, fmtDistance(res, paw.walkDistanceMeters), c.text)
    }
}

@Composable
private fun PawLeg(glyph: MapGlyph, text: String, color: Color) {
    local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon(
        glyph = glyph,
        tint = LocalPhantomColors.current.text2,
        modifier = Modifier.size(TIMELINE_GLYPH),
    )
    Spacer(Modifier.width(PhantomSpace.s1))
    SheetNote(text, color = color, maxLines = 1, tnum = true)
}
