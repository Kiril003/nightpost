package local.phantom.companion.feature.precision

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import local.phantom.companion.core.geo.precision.BearingSmoother
import local.phantom.companion.core.geo.precision.HmmRoadMatcher
import local.phantom.companion.core.geo.precision.UltraPrecisionEkfEngine
import local.phantom.companion.core.gnss.FixSource
import local.phantom.companion.core.gnss.GnssBlackBox
import local.phantom.companion.core.gnss.GnssEpoch
import local.phantom.companion.core.gnss.JamSpoofMetrics
import local.phantom.companion.core.gnss.RawGnssSource
import local.phantom.companion.core.indoor.pdr.PedestrianSnapshot
import local.phantom.companion.core.mobility.TransportMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Жива збірка стану позиціонування з реальних датчиків телефона.
 *
 * Це місце, де вся математика `:core-gnss` та `:core-geo` (EKF, complementary
 * bearing smoother) нарешті торкається живого сигналу: сирі епохи з
 * [RawGnssSource] проходять через [JamSpoofMetrics], фільтруються ЕКФ Калмана
 * [UltraPrecisionEkfEngine] і згладжуються через [BearingSmoother].
 *
 * ЧОГО ТУТ НЕМАЄ, попри те що рядок вище це стверджував: узгодження з графом
 * доріг. [HmmRoadMatcher] створюється без аргументів, його `roadNetwork` за
 * замовчуванням порожній, і перший же рядок `match()` віддає вхід незміненим.
 * Граф не заповнює ніхто в усьому дереві, тож `FixSource.ROAD_MATCH` («Дорога»)
 * не може бути виставлений НІКОЛИ. Це не лагодиться тут і не лагодиться до
 * бети — граф треба звідкись узяти; поки що це названо, а не вдавано.
 */
class LivePrecisionSource(context: Context) : PrecisionStateSource {

    private val appContext = context.applicationContext
    private val raw = RawGnssSource(appContext)
    private val motion = LiveMotionSource(appContext)
    private val pedestrian = LivePedestrianSource(appContext)
    private val lm = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    // EKF Engine, HMM Road Matcher & Bearing Smoother
    private val ekfEngine = UltraPrecisionEkfEngine()
    // Порожній граф: `match()` повертає вхід і `isMatched = false` завжди.
    private val roadMatcher = HmmRoadMatcher()
    private val bearingSmoother = BearingSmoother()

    private val rtt by lazy { RttCapability.probe(appContext) }

    @SuppressLint("MissingPermission")
    override fun state(): Flow<PrecisionUiState> = callbackFlow {
        val metrics = JamSpoofMetrics()
        val writer = BlackBoxWriter(appContext)
        val blackBox = GnssBlackBox(sink = writer::write)
        val boxLock = Any()
        var wasArmed = false
        var recordedNs = Long.MIN_VALUE
        var lastEpoch: GnssEpoch? = null
        var gpsFix: Location? = null
        var networkFix: Location? = null
        var mode: TransportMode = TransportMode.UNKNOWN
        var walk: PedestrianSnapshot? = null

        fun publish(): PrecisionUiState {
            val divergence = divergenceKm(gpsFix, networkFix)
            val source = when {
                lastEpoch?.observations?.isNotEmpty() == true -> FixSource.GNSS_FUSED
                networkFix != null -> FixSource.NETWORK
                walk?.carriesPosition == true -> FixSource.DEAD_RECKONING
                else -> FixSource.NONE
            }
            val integrity = lastEpoch?.let {
                metrics.evaluate(it, networkDivergenceKm = divergence, fixSource = source)
            }

            // publish() смикають чотири джерела при незмінному lastEpoch: без
            // замка і звірки ns та сама секунда лягла б у файл чотири рази.
            val epochNow = lastEpoch
            if (epochNow != null && integrity != null) {
                synchronized(boxLock) {
                    if (epochNow.elapsedRealtimeNanos != recordedNs) {
                        recordedNs = epochNow.elapsedRealtimeNanos
                        blackBox.offer(epochNow, integrity, divergence)
                        if (wasArmed && !blackBox.isArmed) writer.rotate()
                        wasArmed = blackBox.isArmed
                    }
                }
            }

            val st = PrecisionUiState.from(
                epoch = lastEpoch,
                integrity = integrity,
                fixSource = source,
                accuracyM = gpsFix?.accuracy?.toDouble(),
                mode = mode,
                networkDivergenceKm = divergence,
                pedestrian = walk,
                rttAvailability = rtt,
            )
            trySend(st)
            return st
        }

        val walkJob = launch {
            pedestrian.snapshots().collect { snapshot ->
                walk = snapshot
                publish()
            }
        }

        val manager = lm
        if (manager == null || !raw.hasPermission) {
            publish()
            awaitClose { walkJob.cancel() }
            return@callbackFlow
        }

        val epochsFlow = raw.epochs()
        val epochJob = launch {
            epochsFlow.collect { epoch ->
                lastEpoch = epoch
                publish()
            }
        }

        val motionJob = launch {
            motion.verdicts().collect { verdict ->
                mode = verdict.mode
                publish()
            }
        }

        val gpsListener = simpleListener { loc ->
            // Pass raw GPS fix into EKF engine for sub-meter filtering
            val ekfFix = ekfEngine.updateGnssFix(
                lat = loc.latitude,
                lon = loc.longitude,
                alt = loc.altitude,
                accuracyM = loc.accuracy,
                speedMps = if (loc.hasSpeed()) loc.speed else 0f,
                bearingDeg = if (loc.hasBearing()) loc.bearing else 0f,
                timestampMs = loc.time,
            )

            // Smooth bearing via complementary filter
            val smoothedBearing = bearingSmoother.filter(
                rawBearingDeg = ekfFix.bearingDegrees,
                speedMps = ekfFix.speedMps,
                gnssBearingDeg = if (loc.hasBearing()) loc.bearing else null,
            )

            // Match against road network via HMM
            val roadMatch = roadMatcher.match(
                lat = ekfFix.latitude,
                lon = ekfFix.longitude,
                headingDeg = smoothedBearing,
            )

            // Update GPS location with EKF & Road-matched coordinates
            val enhancedLoc = Location(loc).apply {
                if (roadMatch.isMatched) {
                    latitude = roadMatch.snappedLat
                    longitude = roadMatch.snappedLon
                } else {
                    latitude = ekfFix.latitude
                    longitude = ekfFix.longitude
                }
                altitude = ekfFix.altitudeMeters
                // Підлогу на похибці ставить сам ЕКФ (див. estAccuracy там):
                // одне місце, і його видно всім споживачам, зокрема
                // trustedForAnchor.
                accuracy = ekfFix.accuracyMeters
                bearing = smoothedBearing
                speed = ekfFix.speedMps
            }

            gpsFix = enhancedLoc

            if (enhancedLoc.hasSpeed()) motion.offerSpeed(enhancedLoc.speed.toDouble())
            val st = publish()

            if (st.trustedForAnchor) {
                pedestrian.offerTrustedFix(
                    accuracyM = enhancedLoc.accuracy.toDouble(),
                    bearingRad = if (enhancedLoc.hasBearing()) Math.toRadians(enhancedLoc.bearing.toDouble()) else null,
                    speedMps = if (enhancedLoc.hasSpeed()) enhancedLoc.speed.toDouble() else null,
                )
            }
        }

        val networkListener = simpleListener { loc -> networkFix = loc; publish() }

        if (manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gpsListener, Looper.getMainLooper())
        }
        if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 4_000L, 0f, networkListener, Looper.getMainLooper())
        }

        publish() // початковий знімок, щоб екран не блимав порожнечею

        awaitClose {
            walkJob.cancel()
            epochJob.cancel()
            motionJob.cancel()
            manager.removeUpdates(gpsListener)
            manager.removeUpdates(networkListener)
        }
    }

    private fun simpleListener(onFix: (Location) -> Unit) = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location)
        @Deprecated("compat") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private companion object {
        const val EARTH_R_KM = 6371.0088

        /** Гаверсинус між двома позиціями, км; `null`, якщо якоїсь немає. */
        fun divergenceKm(a: Location?, b: Location?): Double? {
            if (a == null || b == null) return null
            val p1 = Math.toRadians(a.latitude)
            val p2 = Math.toRadians(b.latitude)
            val dp = p2 - p1
            val dl = Math.toRadians(b.longitude - a.longitude)
            val h = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
            return 2 * EARTH_R_KM * asin(sqrt(h).coerceIn(0.0, 1.0))
        }
    }
}
