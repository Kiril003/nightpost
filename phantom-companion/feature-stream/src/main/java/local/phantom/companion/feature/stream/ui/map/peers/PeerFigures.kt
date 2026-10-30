package local.phantom.companion.feature.stream.ui.map.peers

import androidx.annotation.PluralsRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import local.phantom.companion.core.data.peers.PeerPoint
import local.phantom.companion.core.data.peers.expiredAt
import local.phantom.companion.core.data.peers.freshAt
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapViewModel
import local.phantom.companion.feature.stream.ui.map.dossier.haversineMeters
import local.phantom.companion.feature.stream.ui.map.escapeJsonString

internal data class PeerMinuteTag(
    @PluralsRes val pluralsRes: Int,
    val minutes: Int,
)

/** [fresh] — уже рішення: вік плюс вирок, запечатаний у core-data. */
internal data class PeerFigure(
    val nodeId: String,
    /** Плюмбінг під фільтр груп (хвиля 4); UI на ньому поки немає. */
    val threadId: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val atMs: Long,
    val verified: Boolean,
    val fresh: Boolean,
    val tag: String = "",
    @PluralsRes val tagPluralsRes: Int? = null,
    val tagMinutes: Int? = null,
)

private const val FIGURE_WALK_M_PER_MIN = 80.0

internal fun peerFigureMinuteTag(
    point: PeerPoint,
    nowMs: Long,
    fresh: Boolean,
    origin: Pair<Double, Double>? = null,
): PeerMinuteTag? = when {
    fresh && origin != null -> {
        val dist = haversineMeters(origin.first, origin.second, point.lat, point.lon)
        PeerMinuteTag(
            pluralsRes = R.plurals.map_minutes_away,
            minutes = maxOf(1, (dist / FIGURE_WALK_M_PER_MIN).roundToInt()),
        )
    }
    !fresh && point.atMs > 0L && point.atMs < nowMs -> {
        PeerMinuteTag(
            pluralsRes = R.plurals.map_minutes_ago,
            minutes = maxOf(1L, (nowMs - point.atMs) / 60_000L).toInt(),
        )
    }
    else -> null
}

internal fun defaultFormatMinuteTag(pluralsRes: Int, minutes: Int): String = when (pluralsRes) {
    R.plurals.map_minutes_ago -> "$minutes min ago"
    else -> "$minutes min away"
}

internal fun peerFigureTag(
    point: PeerPoint,
    nowMs: Long,
    fresh: Boolean,
    origin: Pair<Double, Double>? = null,
    formatTag: (Int, Int) -> String = ::defaultFormatMinuteTag,
): String {
    val spec = peerFigureMinuteTag(point, nowMs, fresh, origin) ?: return ""
    return formatTag(spec.pluralsRes, spec.minutes)
}

internal fun peerFigures(
    points: List<PeerPoint>,
    nowMs: Long,
    origin: Pair<Double, Double>? = null,
    formatTag: (Int, Int) -> String = ::defaultFormatMinuteTag,
): List<PeerFigure> = points
    // Строк шерінгу — обіцянка тому, хто ділився, а не порада рендеру.
    .filterNot { it.expiredAt(nowMs) || it.isWhere() }
    .map { p ->
        val isFresh = p.freshAt(nowMs)
        val spec = peerFigureMinuteTag(p, nowMs, isFresh, origin)
        PeerFigure(
            nodeId = p.nodeId,
            threadId = p.threadId,
            name = p.name,
            lat = p.lat,
            lon = p.lon,
            atMs = p.atMs,
            verified = p.verified,
            fresh = isFresh,
            tag = spec?.let { formatTag(it.pluralsRes, it.minutes) }.orEmpty(),
            tagPluralsRes = spec?.pluralsRes,
            tagMinutes = spec?.minutes,
        )
    }

/** Координати — `[довгота, широта]`: переставлені кладуть киянина в Казахстан. */
internal fun peerFiguresGeoJson(figures: List<PeerFigure>): String {
    val features = figures.joinToString(",") { f ->
        val props = buildString {
            append("\"nodeId\":\"").append(escapeJsonString(f.nodeId)).append("\",")
            append("\"threadId\":\"").append(escapeJsonString(f.threadId)).append("\",")
            append("\"name\":\"").append(escapeJsonString(f.name)).append("\",")
            append("\"tag\":\"").append(escapeJsonString(f.tag)).append("\",")
            append("\"verified\":").append(f.verified).append(",")
            append("\"fresh\":").append(f.fresh).append(",")
            append("\"atMs\":").append(f.atMs)
        }
        "{\"type\":\"Feature\",\"properties\":{$props}," +
            "\"geometry\":{\"type\":\"Point\",\"coordinates\":[${f.lon},${f.lat}]}}"
    }
    return "{\"type\":\"FeatureCollection\",\"features\":[$features]}"
}

/** Такт перечитування ВІКУ. Не анімація: чужа точка не рухається між тіками. */
private const val PEER_AGE_TICK_MS = 5_000L

@Composable
internal fun rememberPeerFiguresGeoJson(
    viewModel: MapViewModel,
    origin: Pair<Double, Double>? = null,
): String {
    val points by viewModel.peerPoints.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Годинник заводиться лише поки на землі хтось є, і гасне, коли всі точки
    // застаріли й строків не лишилось: на порожньому потоці — жодного такту.
    LaunchedEffect(points) {
        // Перечитати ПЕРЕД першим кадром батча: інакше вік мірявся б проти
        // миті, коли відкрили екран, і точка з дороги читалась би свіжішою.
        nowMs = System.currentTimeMillis()
        while (points.any { !it.isWhere() && (it.freshAt(nowMs) || it.share.expiresAtMs != null) }) {
            delay(PEER_AGE_TICK_MS)
            nowMs = System.currentTimeMillis()
            viewModel.sweepPeers()
        }
    }

    val resources = LocalContext.current.resources
    return remember(points, nowMs, origin, resources) {
        peerFiguresGeoJson(
            peerFigures(points, nowMs, origin) { resId, mins ->
                resources.getQuantityString(resId, mins, mins)
            },
        )
    }
}

/** Слово свіжості міняється щохвилини, не щосекунди: частіший такт лише перемальовував би ту саму пляму. */
private const val WHERE_TICK_MS = 30_000L

@Composable
internal fun rememberPeerWhereGeoJson(viewModel: MapViewModel, showWorld: Boolean = false, meet: Pair<Double, Double>? = null): String {
    val points by viewModel.peerPoints.collectAsStateWithLifecycle()
    val world by viewModel.people.world.collectAsStateWithLifecycle()
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(points) {
        nowMs = System.currentTimeMillis()
        while (points.any { it.isWhere() && !it.expiredAt(nowMs) }) {
            delay(WHERE_TICK_MS)
            nowMs = System.currentTimeMillis()
        }
    }
    return remember(points, nowMs, showWorld, world, meet) { peerWhereGeoJson(points, nowMs, if (showWorld) world.boxes else emptyList(), meet) }
}
