package local.phantom.companion.core.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Єдиний дотик модуля до платформи Android.
 *
 * Усе цінне в `:core-gnss` — математика й пороги — свідомо живе над простими
 * типами й перевіряється на JVM. Цей файл — тонкий переклад: він бере
 * `GnssMeasurementsEvent` (сирі псевдовідстані, фази, доплер, C/N0 по кожному
 * супутнику й частоті) та `GnssStatus` (кути місця й азимути) і зшиває їх у
 * [GnssEpoch]. Жодного рішення тут не ухвалюється — тільки переклад.
 *
 * Чому саме сирі вимірювання, а не `Location`: штатний `Location` — уже
 * пережований чіпсетом розвʼязок, згладжений і притягнутий до доріг, без сліду
 * того, ЯК він отримався. Під РЕБ це найгірший вхід — підмінений сигнал
 * приходить туди як звичайний «точний» fix. Тут ми на рівень нижче, де брехню
 * ще видно.
 *
 * Окремо знімається мережеве положення ([networkAnchor]) — незалежний від
 * супутників фізичний канал. Розбіжність між ним і супутниковим розвʼязком і є
 * найнадійнішим детектором підміни (21.07.2026 вона склала 12 270 км).
 */
class RawGnssSource(private val context: Context) {

    private val lm: LocationManager? =
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Чи є дозвіл на точне розташування — без нього сирі виміри не видають. */
    val hasPermission: Boolean
        get() = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Гарячий потік епох сирих вимірювань.
     *
     * Кути місця/азимути приходять окремим колбеком `GnssStatus`; тримаємо їхній
     * останній знімок і домішуємо до кожної епохи — без напрямку фазовий вимір
     * не перетворюється на рух.
     */
    @SuppressLint("MissingPermission")
    fun epochs(): Flow<GnssEpoch> = callbackFlow {
        val manager = lm
        if (manager == null || !hasPermission) {
            close()
            return@callbackFlow
        }
        val handler = Handler(Looper.getMainLooper())
        val sky = HashMap<String, SkyEntry>() // ключ constellation-svid → напрямок

        val statusCb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                sky.clear()
                for (i in 0 until status.satelliteCount) {
                    val c = constellationOf(status.getConstellationType(i))
                    val key = "${c.name}-${status.getSvid(i)}"
                    // GnssStatus завжди повертає кут місця й азимут (може бути 0
                    // до збору альманаху); окремих «has» для них немає.
                    sky[key] = SkyEntry(
                        elevation = status.getElevationDegrees(i).toDouble(),
                        azimuth = status.getAzimuthDegrees(i).toDouble(),
                    )
                }
            }
        }

        val measCb = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                trySend(toEpoch(event, sky))
            }
        }

        manager.registerGnssStatusCallback(statusCb, handler)
        manager.registerGnssMeasurementsCallback(measCb, handler)

        awaitClose {
            manager.unregisterGnssStatusCallback(statusCb)
            manager.unregisterGnssMeasurementsCallback(measCb)
        }
    }

    /**
     * Незалежний мережевий якір для перевірки розбіжності.
     *
     * Свідомо `NETWORK_PROVIDER`, а не fused: fused отруюється підміненим GPS
     * (21.07 fused пішов у Перу разом із супутниками), а мережа лишилась вірною.
     */
    @SuppressLint("MissingPermission")
    fun networkAnchor(onFix: (Location) -> Unit): AutoCloseable? {
        val manager = lm ?: return null
        if (!hasPermission || !manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) return null
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) = onFix(location)
            @Deprecated("compat") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }
        manager.requestLocationUpdates(
            LocationManager.NETWORK_PROVIDER, NETWORK_INTERVAL_MS, 0f, listener, Looper.getMainLooper(),
        )
        return AutoCloseable { manager.removeUpdates(listener) }
    }

    private fun toEpoch(event: GnssMeasurementsEvent, sky: Map<String, SkyEntry>): GnssEpoch {
        val clock = event.clock
        val obs = event.measurements.map { m ->
            val c = constellationOf(m.constellationType)
            val direction = sky["${c.name}-${m.svid}"]
            val adr = m.accumulatedDeltaRangeState
            SatObservation(
                svid = m.svid,
                constellation = c,
                band = bandOf(m),
                cn0DbHz = m.cn0DbHz,
                pseudorangeRateMps = m.pseudorangeRateMetersPerSecond,
                pseudorangeRateUncertaintyMps = m.pseudorangeRateUncertaintyMetersPerSecond,
                accumulatedDeltaRangeM = m.accumulatedDeltaRangeMeters,
                accumulatedDeltaRangeUncertaintyM = m.accumulatedDeltaRangeUncertaintyMeters,
                adrValid = adr and GnssMeasurement.ADR_STATE_VALID != 0,
                cycleSlip = (adr and GnssMeasurement.ADR_STATE_CYCLE_SLIP != 0) ||
                    (adr and GnssMeasurement.ADR_STATE_RESET != 0),
                multipathSuspect = m.multipathIndicator == android.location.GnssMeasurement.MULTIPATH_INDICATOR_DETECTED,
                elevationDeg = direction?.elevation,
                azimuthDeg = direction?.azimuth,
                usedInFix = m.cn0DbHz > 0.0,
            )
        }
        return GnssEpoch(
            elapsedRealtimeNanos = event.clock.timeNanos,
            observations = obs,
            clockBiasNanos = if (clock.hasBiasNanos()) clock.biasNanos else null,
            clockDriftNanosPerSec = if (clock.hasDriftNanosPerSecond()) clock.driftNanosPerSecond else null,
            hardwareClockDiscontinuityCount = clock.hardwareClockDiscontinuityCount,
        )
    }

    private fun bandOf(m: android.location.GnssMeasurement): Band {
        if (!m.hasCarrierFrequencyHz()) return Band.UNKNOWN
        val mhz = m.carrierFrequencyHz / 1_000_000.0
        return when {
            near(mhz, 1575.42) -> Band.L1
            near(mhz, 1176.45) -> Band.L5
            near(mhz, 1227.60) -> Band.L2
            near(mhz, 1207.14) -> Band.E5B
            near(mhz, 1561.10) -> Band.B2
            else -> Band.UNKNOWN
        }
    }

    private fun near(v: Double, target: Double, tolMhz: Double = 10.0) = kotlin.math.abs(v - target) <= tolMhz

    private fun constellationOf(type: Int): Constellation = when (type) {
        GnssStatus.CONSTELLATION_GPS -> Constellation.GPS
        GnssStatus.CONSTELLATION_GLONASS -> Constellation.GLONASS
        GnssStatus.CONSTELLATION_GALILEO -> Constellation.GALILEO
        GnssStatus.CONSTELLATION_BEIDOU -> Constellation.BEIDOU
        GnssStatus.CONSTELLATION_QZSS -> Constellation.QZSS
        GnssStatus.CONSTELLATION_SBAS -> Constellation.SBAS
        GnssStatus.CONSTELLATION_IRNSS -> Constellation.IRNSS
        else -> Constellation.UNKNOWN
    }

    private data class SkyEntry(val elevation: Double?, val azimuth: Double?)

    private companion object {
        const val NETWORK_INTERVAL_MS = 4_000L
    }
}

// GnssMeasurement константи ADR_STATE_* доступні як статичні поля класу.
private typealias GnssMeasurement = android.location.GnssMeasurement
