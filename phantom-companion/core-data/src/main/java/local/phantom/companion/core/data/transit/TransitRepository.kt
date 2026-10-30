package local.phantom.companion.core.data.transit

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.transit.metro.MetroFeed
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.data.transit.surface.SurfaceFeed
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.transit.GtfsRealtimeClient
import local.phantom.companion.core.net.transit.RawVehiclePosition
import local.phantom.companion.core.net.transit.VehicleOccupancy
import java.util.Calendar

/**
 * Where a position came from. Law 1 (D8): «live» is a property of the SOURCE,
 * never a guess from an id prefix — the default is the honest one.
 *
 * [SIMULATED] no longer has a producer: the headway model used to interpolate
 * trains and buses along their lines and hand them out as positions, which put
 * a vehicle on the map at a metre where nobody had ever looked. The value stays
 * as the default so a source that forgets to declare itself is read as the
 * worst case, not as live.
 */
enum class TransitOrigin { LIVE_FEED, SIMULATED }

/** One vehicle's last-known fix — what the UI's interpolation engine glides towards. */
data class TransitVehicle(
    val vehicleId: String,
    val tripId: String?,
    val routeId: String?,
    val lat: Double,
    val lon: Double,
    val bearingDegrees: Float?,
    val occupancy: VehicleOccupancy,
    val fixEpochMs: Long,
    val speedKmh: Float? = null,
    val origin: TransitOrigin = TransitOrigin.SIMULATED,
    /**
     * What the vehicle IS, carried from the source that knows. Never inferred
     * from [vehicleId]/[routeId] text: a real GTFS-RT tram whose id happens to
     * read "bus_17" is still a tram. Unknown stays [TransitMode.OTHER].
     */
    val mode: TransitMode = TransitMode.OTHER,
    /** Номер з таблички, або null. [routeId] людині не показується ніколи. */
    val routeLabel: String? = null,
) {
    val isLiveGps: Boolean get() = origin == TransitOrigin.LIVE_FEED
}

/** A single change against the repository's cache. */
sealed interface TransitDelta {
    data class Added(val vehicle: TransitVehicle) : TransitDelta
    data class Updated(val vehicle: TransitVehicle, val previous: TransitVehicle) : TransitDelta
    data class Removed(val vehicleId: String) : TransitDelta
}

/**
 * Ground Transit Engine — позиції транспорту з перевіреного GTFS-RT.
 *
 * ЄДИНЕ джерело міток тут — живий фід. Раніше репозиторій умів другий режим:
 * коли фіда не було, він розгортав інтервали в рейси, лінійно інтерполював
 * машину між зупинками й віддавав результат як позицію. Мітка стояла там, де
 * її ніхто не бачив, і рухалась так, як ніхто не їхав. Цього режиму більше
 * немає — не тому, що він був погано підписаний, а тому, що позиція, якої не
 * виміряли, не позиція.
 *
 * [surfaceFeed] і [metroFeed] лишаються лише для [coverage]: вони кажуть, чи
 * має ярус моделі що сказати про розклади в цій точці. Флоту вони не дають.
 */
class TransitRepository(
    private val client: GtfsRealtimeClient? = null,
    private val surfaceFeed: (() -> SurfaceFeed?)? = null,
    private val metroFeed: (() -> MetroFeed?)? = null,
    private val pollIntervalMs: Long = LIVE_POLL_MS,
    /** Where the operator is looking — the point coverage is judged against. */
    private val focus: (() -> Pair<Double, Double>?)? = null,
    /**
     * route_id → mode for the LIVE feed. GTFS-RT carries no `route_type` — it
     * lives in the static GTFS — so without this a live vehicle's mode is
     * honestly unknown rather than guessed from its id.
     */
    private val routeMode: ((routeId: String?) -> TransitMode)? = null,
    private val routeLabel: ((routeId: String?) -> String?)? = null,
    /**
     * ATLAS-3 — глобальний ярус розкладів. Свідомо НЕ бере участі в опитуванні
     * позицій: статичний GTFS не знає, де зараз автобус, і будь-яка мітка з
     * нього була б фантомом. Живить лише хаб, табло й досьє.
     */
    val staticSchedules: TransitlandSource? = null,
    /** Межі опублікованого розкладу пака — лише для [coverage]. */
    private val scheduleBounds: (() -> FeedBounds?)? = null,
) {
    private var scope: CoroutineScope? = null
    private val cache = LinkedHashMap<String, TransitVehicle>()

    private val _vehicles = MutableStateFlow<List<TransitVehicle>>(emptyList())
    val vehicles: StateFlow<List<TransitVehicle>> = _vehicles.asStateFlow()

    private val _deltas = MutableSharedFlow<List<TransitDelta>>(extraBufferCapacity = 8)
    val deltas: SharedFlow<List<TransitDelta>> = _deltas.asSharedFlow()

    private val _feedDegraded = MutableStateFlow(false)
    val feedDegraded: StateFlow<Boolean> = _feedDegraded.asStateFlow()

    /**
     * Чи є тут джерело ПОЗИЦІЙ. Розклад ним не є й ніколи не був — саме тому
     * ця перевірка більше не дивиться на межі моделі: там, де немає живого
     * фіда, немає й міток, скільки б розкладу ми не знали.
     */
    fun coversFocus(): Boolean = client != null

    /**
     * Що чесно можна сказати про транспорт там, куди дивиться оператор.
     *
     * Окремо від [coversFocus]: та відповідає лише за опитування позицій, і
     * ярус розкладів не має права її вмикати — інакше поза модельованим
     * містом почав би крутитися порожній цикл флоту.
     */
    fun coverage(): TransitCoverage {
        val here = focus?.invoke() ?: return TransitCoverage(
            TransitPlane.NONE,
            absence = SourceUnavailable.RequestRejected("точка фокусу невідома"),
        )
        return TransitCoverageResolver.resolve(
            lat = here.first,
            lon = here.second,
            simulatedBounds = simulatedBounds(),
            hasLiveClient = client != null,
            transitlandAvailable = staticSchedules?.available == true,
            scheduleBounds = scheduleBounds?.invoke(),
        )
    }

    fun start() {
        if (scope != null || client == null) return
        val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = ownScope
        ownScope.launch {
            while (isActive) {
                pollOnce()
                delay(pollIntervalMs)
            }
        }
    }

    fun stop() {
        scope?.cancel()
        scope = null
        cache.clear()
        _vehicles.value = emptyList()
    }

    private suspend fun pollOnce() {
        val fresh = client?.fetchVehiclePositions()
        if (fresh == null) {
            _feedDegraded.value = true
            return
        }
        _feedDegraded.value = false

        val seenIds = HashSet<String>(fresh.size)
        val pending = ArrayList<TransitDelta>()

        for (raw in fresh) {
            // Фід віддав координату — вона і є єдиною причиною, з якої мітка
            // існує. Режим живий фід не несе (GTFS-RT не має route_type), тож
            // без [routeMode] він чесно невідомий.
            if (!raw.lat.isFinite() || !raw.lon.isFinite() ||
                raw.lat !in -90.0..90.0 || raw.lon !in -180.0..180.0 ||
                (raw.lat == 0.0 && raw.lon == 0.0)
            ) {
                continue
            }
            seenIds += raw.vehicleId
            val prev = cache[raw.vehicleId]
            val mode = routeMode?.invoke(raw.routeId) ?: TransitMode.OTHER
            val label = routeLabel?.invoke(raw.routeId)?.takeIf { it.isNotBlank() }
            val next = raw.toVehicle(prev, TransitOrigin.LIVE_FEED, mode).copy(routeLabel = label)
            when {
                prev == null -> pending += TransitDelta.Added(next)
                prev.hasChangedFrom(next) -> pending += TransitDelta.Updated(next, prev)
                else -> Unit
            }
            cache[raw.vehicleId] = next
        }

        for (goneId in cache.keys - seenIds) {
            cache.remove(goneId)
            pending += TransitDelta.Removed(goneId)
        }

        if (pending.isNotEmpty()) {
            _vehicles.value = cache.values.toList()
            _deltas.emit(pending)
        }
    }

    private fun simulatedBounds(): FeedBounds? {
        val s = surfaceFeed?.invoke()?.bounds
        val m = metroFeed?.invoke()?.bounds
        return if (s != null && m != null) s.union(m) else s ?: m
    }

    private fun RawVehiclePosition.toVehicle(
        previous: TransitVehicle?,
        origin: TransitOrigin,
        mode: TransitMode,
    ): TransitVehicle {
        val calculatedBearing = if (bearingDegrees != null && bearingDegrees != 0f) {
            bearingDegrees
        } else if (previous != null && (previous.lat != lat || previous.lon != lon)) {
            calculateBearing(previous.lat, previous.lon, lat, lon)
        } else {
            previous?.bearingDegrees ?: 0f
        }

        val epochMs = (timestampEpochSec ?: (System.currentTimeMillis() / 1000L)) * 1000L

        // Real speed from GTFS-RT (speedMps * 3.6f) or computed delta velocity
        val sMps = speedMps
        val computedSpeedKmh = when {
            sMps != null && (sMps * 3.6f) in 0f..85f -> sMps * 3.6f
            previous != null && previous.fixEpochMs < epochMs -> {
                val distM = calculateDistanceM(previous.lat, previous.lon, lat, lon)
                val dtS = (epochMs - previous.fixEpochMs) / 1000.0
                if (dtS in 1.0..120.0) {
                    val rawKmh = ((distM / dtS) * 3.6f).toFloat()
                    if (rawKmh in 0f..85f) rawKmh else previous.speedKmh?.takeIf { it in 0f..85f } ?: 0f
                } else previous.speedKmh
            }
            else -> null
        }

        return TransitVehicle(
            vehicleId = vehicleId,
            tripId = tripId,
            routeId = routeId,
            lat = lat,
            lon = lon,
            bearingDegrees = calculatedBearing,
            occupancy = occupancy,
            fixEpochMs = epochMs,
            speedKmh = computedSpeedKmh,
            origin = origin,
            mode = mode,
        )
    }

    private fun TransitVehicle.hasChangedFrom(next: TransitVehicle): Boolean =
        lat != next.lat ||
            lon != next.lon ||
            bearingDegrees != next.bearingDegrees ||
            occupancy != next.occupancy ||
            speedKmh != next.speedKmh ||
            origin != next.origin ||
            routeLabel != next.routeLabel

    private fun calculateDistanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return r * c
    }

    private fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val dLon = Math.toRadians(lon2 - lon1)
        val y = kotlin.math.sin(dLon) * kotlin.math.cos(Math.toRadians(lat2))
        val x = kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.sin(Math.toRadians(lat2)) -
            kotlin.math.sin(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) * kotlin.math.cos(dLon)
        val brng = Math.toDegrees(kotlin.math.atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }

    companion object {
        const val TAG = "TransitRepository"

        /** Темп опитування перевіреного GTFS-RT. Іншого джерела міток немає. */
        const val LIVE_POLL_MS = 12_000L

        /** Модельний/розкладовий темп ніколи не бʼється частіше за живий фід. */
        const val SIMULATED_POLL_MS = 20_000L

        /** ~28 km of slack so the network does not blink at the city edge. */
        const val COVERAGE_PAD_DEG = 0.25
    }
}
