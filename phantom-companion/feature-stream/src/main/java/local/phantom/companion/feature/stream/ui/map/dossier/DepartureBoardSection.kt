package local.phantom.companion.feature.stream.ui.map.dossier

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import local.phantom.companion.core.data.transit.TransitDelayMemory
import local.phantom.companion.core.data.transit.surface.Polyline5
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.design.tokens.PhantomSpace
import local.phantom.companion.feature.stream.ui.map.SheetBody
import local.phantom.companion.feature.stream.ui.map.SheetNote
import local.phantom.companion.feature.stream.ui.map.SheetSection
import local.phantom.companion.feature.stream.ui.map.SheetSmall
import local.phantom.companion.feature.stream.ui.map.transit.TransitModeTint
import local.phantom.companion.core.net.transit.TransitousStopClient
import local.phantom.companion.core.net.transit.TransitousTransitClient
import local.phantom.companion.feature.stream.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private sealed interface BoardState {
    data object Loading : BoardState
    data object Silent : BoardState
    data class Ready(val board: TransitousStopClient.DepartureBoard) : BoardState
}

@Composable
internal fun DepartureBoardSection(
    lat: Double,
    lon: Double,
    airport: Boolean,
    onShowTrip: (DossierTransitLine) -> Unit,
    worldTransitAllowed: Boolean = local.phantom.companion.core.net.map.GeoEndpoints.config.worldTransitOptIn,
) {
    val client = remember { TransitousStopClient() }
    val memory = TransitDelayMemory.shared
    var state by remember(lat, lon, worldTransitAllowed) {
        mutableStateOf<BoardState>(if (worldTransitAllowed) BoardState.Loading else BoardState.Silent)
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var openKey by remember(lat, lon) { mutableStateOf<String?>(null) }

    LaunchedEffect(lat, lon, worldTransitAllowed) {
        if (!worldTransitAllowed) return@LaunchedEffect
        while (true) {
            val fresh = client.departureBoard(lat, lon, if (airport) AIRPORT_RADIUS_M else STOP_RADIUS_M)
            if (fresh != null) {
                withContext(Dispatchers.IO) { DepartureBoardModel.learn(fresh, memory) }
                state = BoardState.Ready(fresh)
            } else if (state == BoardState.Loading) {
                state = BoardState.Silent
            }
            repeat(REFRESH_TICKS) {
                nowMs = System.currentTimeMillis()
                delay(TICK_MS)
            }
        }
    }

    val shown = when (val s = state) {
        BoardState.Loading -> BoardShown.Loading
        BoardState.Silent -> BoardShown.Silent
        is BoardState.Ready -> BoardShown.Rows(
            remember(s.board, nowMs) { DepartureBoardModel.rows(s.board, nowMs, memory) },
        )
    }
    DepartureBoard(
        airport = airport,
        shown = shown,
        openKey = openKey,
        onToggle = { key -> openKey = if (openKey == key) null else key },
        tripDetail = { row, rows -> TripDetail(row, rows, nowMs, onShowTrip) },
    )
}

/** Що табло має показати — без мережі й без годинника: їх тримає [DepartureBoardSection]. */
internal sealed interface BoardShown {
    data object Loading : BoardShown
    data object Silent : BoardShown
    data class Rows(val rows: List<BoardRow>) : BoardShown
}

/**
 * Саме табло, без стану. Мережа, годинник і розкриття рейсу живуть у
 * [DepartureBoardSection]; тут лише те, що видно, — тож кадр табло можна
 * намалювати з готових рядків, не ходячи в Transitous.
 */
@Composable
internal fun DepartureBoard(
    airport: Boolean,
    shown: BoardShown,
    openKey: String? = null,
    onToggle: (String) -> Unit = {},
    tripDetail: @Composable (row: BoardRow, all: List<BoardRow>) -> Unit = { _, _ -> },
) {
    Column(Modifier.fillMaxWidth().padding(vertical = PhantomSpace.s2)) {
        SheetSection(stringResource(R.string.dossier_board_title))
        if (airport) {
            Spacer(Modifier.height(PhantomSpace.s1))
            SheetNote(stringResource(R.string.dossier_board_no_flights))
        }
        Spacer(Modifier.height(PhantomSpace.s2))
        when (shown) {
            BoardShown.Loading -> SheetNote(stringResource(R.string.dossier_board_loading))
            BoardShown.Silent -> SheetNote(stringResource(R.string.dossier_board_silent))
            is BoardShown.Rows -> {
                val rows = shown.rows
                val visible = rows.filter { it.past }.takeLast(PAST_ROWS) + rows.filterNot { it.past }.take(UPCOMING_ROWS)
                if (visible.none { !it.past }) {
                    SheetNote(stringResource(R.string.dossier_board_empty))
                }
                visible.forEach { row ->
                    DepartureRow(row) { onToggle(row.key) }
                    if (openKey == row.key) tripDetail(row, rows)
                }
            }
        }
    }
}

@Composable
private fun DepartureRow(row: BoardRow, onTap: () -> Unit) {
    val c = LocalPhantomColors.current
    // Колір лінії — лише на бейджі: правдивий із фіда або тон виду.
    val tint = TransitModeTint.line(row.colourArgb, row.mode)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = PhantomTouchFloor)
            .alpha(if (row.past || row.cancelled) PAST_ALPHA else 1f)
            .clickable(role = Role.Button, enabled = row.tripId != null, onClick = onTap)
            .padding(vertical = PhantomSpace.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .widthIn(min = BADGE_MIN)
                .background(tint, RoundedCornerShape(PhantomRadius.chip))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            SheetBody(
                row.label.ifEmpty { stringResource(modeWordRes(row.mode)) },
                color = TransitModeTint.inkOn(tint),
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                tnum = true,
            )
        }
        Spacer(Modifier.width(PhantomSpace.s3))
        Column(Modifier.weight(1f)) {
            SheetBody(row.headsign ?: stringResource(modeWordRes(row.mode)), maxLines = 1)
            val details = buildList {
                if (row.cancelled) add(stringResource(R.string.dossier_board_cancelled))
                row.lateMinutes?.let { add(stringResource(R.string.dossier_board_late, it)) }
                row.usuallyLateMinutes?.let { add(stringResource(R.string.dossier_board_usually_late, it)) }
                row.platform?.let {
                    add(stringResource(if (row.mode == "train" || row.mode == "subway") R.string.route_thread_track else R.string.route_thread_platform, it))
                }
                if (row.mode == "train" || row.label.isEmpty()) row.operator?.let { add(it) }
            }
            if (details.isNotEmpty()) {
                SheetNote(
                    details.joinToString(" · "),
                    color = if (row.cancelled || row.lateMinutes != null) c.danger else c.text2,
                    maxLines = 1,
                    tnum = true,
                )
            }
        }
        Spacer(Modifier.width(PhantomSpace.s2))
        Column(horizontalAlignment = Alignment.End) {
            val mins = row.inMinutes
            val main = when {
                mins == null -> row.clock.orEmpty()
                mins == 0 -> stringResource(R.string.dossier_board_now)
                else -> stringResource(R.string.dossier_board_in_min, mins)
            }
            SheetBody(
                main,
                color = if (mins != null && mins <= 3) c.success else c.text,
                fontWeight = if (mins != null) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                tnum = true,
            )
            if (mins != null && row.clock != null) {
                SheetSmall(row.clock, tnum = true)
            }
        }
    }
}

@Composable
private fun TripDetail(row: BoardRow, all: List<BoardRow>, nowMs: Long, onShowTrip: (DossierTransitLine) -> Unit) {
    val c = LocalPhantomColors.current
    val tripClient = remember { TransitousTransitClient() }
    var leg by remember(row.tripId) { mutableStateOf<TransitousTransitClient.Leg?>(null) }
    var asked by remember(row.tripId) { mutableStateOf(false) }
    LaunchedEffect(row.tripId) {
        leg = row.tripId?.let { tripClient.trip(it) }
        asked = true
    }
    val zone = row.zone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = BADGE_MIN + PhantomSpace.s3, bottom = PhantomSpace.s2),
    ) {
        val (earlier, later) = DepartureBoardModel.siblings(all, row)
        if (earlier.isNotEmpty()) {
            SheetNote(stringResource(R.string.dossier_trip_earlier, earlier.takeLast(3).mapNotNull { it.clock }.joinToString(" · ")), tnum = true)
        }
        if (later.isNotEmpty()) {
            SheetNote(stringResource(R.string.dossier_trip_later, later.take(4).mapNotNull { it.clock }.joinToString(" · ")), tnum = true)
        }
        Spacer(Modifier.height(PhantomSpace.s1))
        val l = leg
        when {
            l == null && !asked -> SheetNote(stringResource(R.string.dossier_trip_loading))
            l == null || l.allCalls.isEmpty() -> SheetNote(stringResource(R.string.dossier_trip_unavailable))
            else -> {
                l.allCalls.forEach { call ->
                    val stopTitle = call.name
                    val at = (call.departureIso ?: call.arrivalIso)?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
                    val here = row.stopName != null && call.name == row.stopName
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .alpha(if (at != null && at < nowMs && !here) PAST_ALPHA else 1f)
                            .padding(vertical = 2.dp),
                    ) {
                        SheetNote(
                            if (at != null && zone != null) Instant.ofEpochMilli(at).atZone(zone).format(CLOCK) else "",
                            modifier = Modifier.widthIn(min = CLOCK_COL),
                            tnum = true,
                        )
                        SheetBody(
                            stopTitle,
                            fontWeight = if (here) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                if (l.points.size >= 2) {
                    SheetBody(
                        stringResource(R.string.dossier_trip_show_map),
                        color = c.accentText,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .heightIn(min = PhantomTouchFloor)
                            .clickable(role = Role.Button) {
                                onShowTrip(
                                    DossierTransitLine(
                                        ref = row.label,
                                        mode = row.mode,
                                        direction = row.headsign,
                                        headwayMinutes = null,
                                        colourArgb = row.colourArgb,
                                        shape = Polyline5.encode(l.points),
                                    ),
                                )
                            }
                            .padding(top = PhantomSpace.s2),
                    )
                }
            }
        }
    }
}

private fun modeWordRes(mode: String): Int = when (mode) {
    "subway" -> R.string.dossier_mode_subway
    "tram" -> R.string.dossier_mode_tram
    "trolleybus" -> R.string.dossier_mode_trolleybus
    "bus" -> R.string.dossier_mode_bus
    "train" -> R.string.dossier_mode_train
    "ferry" -> R.string.dossier_mode_ferry
    else -> R.string.dossier_mode_other
}

private const val STOP_RADIUS_M = 150
private const val AIRPORT_RADIUS_M = 900
private const val PAST_ROWS = 3
private const val UPCOMING_ROWS = 12
private const val PAST_ALPHA = 0.45f
private const val TICK_MS = 20_000L
private const val REFRESH_TICKS = 3
private val BADGE_MIN = 40.dp
private val CLOCK_COL = 52.dp
private val CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
