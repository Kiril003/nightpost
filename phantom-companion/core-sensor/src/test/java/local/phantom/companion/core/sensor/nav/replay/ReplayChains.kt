package local.phantom.companion.core.sensor.nav.replay

import local.phantom.companion.core.indoor.pdr.PedestrianContinuity
import local.phantom.companion.core.indoor.pdr.PedestrianSensors
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import local.phantom.companion.core.sensor.nav.BodyWitness
import local.phantom.companion.core.sensor.nav.ConsistencyGate
import local.phantom.companion.core.sensor.nav.CustomLocationEngine
import local.phantom.companion.core.sensor.nav.FixSmoother
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.core.sensor.nav.PedestrianDisplacement
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

internal data class Shown(val p: En, val claimedM: Double)

internal enum class Alarm { NONE, DOUBT, LOST, JAM, SPOOF }

internal interface Chain {
    val name: String
    val spoofWhy: MutableMap<String, Int>
    fun gps(s: GpsSample, tMs: Long)
    fun network(s: GpsSample, tMs: Long) {}
    fun imu(s: ImuSample, tMs: Long) {}
    fun pressure(hpa: Float, tMs: Long) {}
    fun tick(tMs: Long) {}
    fun shown(tMs: Long): Shown?
    fun alarm(tMs: Long): Alarm
}

/** What the map dot is today: AppContainer.locationProvider over GuardedLocationSource. */
internal class LiveDisplayChain : Chain {
    override val name = "живий показ"
    override val spoofWhy = HashMap<String, Int>()
    private var wasSpoof = false
    private val body = BodyWitness()
    private val gate = ConsistencyGate()
    private val smoother = FixSmoother()
    private var fused: GpsSample? = null
    private var network: GpsSample? = null
    private var judged = Long.MIN_VALUE
    private var verdict: ConsistencyGate.Verdict? = null
    val gateSpoofed: Boolean get() = verdict?.let { it.spoofed || it.refusedFix } == true

    override fun gps(s: GpsSample, tMs: Long) {
        fused = s
        judged = s.elapsedMs
        verdict = gate.update(s, network, tMs, body.evidence(tMs))
    }

    override fun network(s: GpsSample, tMs: Long) { network = s }
    override fun imu(s: ImuSample, tMs: Long) = body.onImu(s, tMs)
    override fun pressure(hpa: Float, tMs: Long) = body.onPressure(hpa, tMs)

    override fun tick(tMs: Long) {
        val f = fused
        if (f == null || f.elapsedMs == judged) verdict = gate.update(f, network, tMs, body.evidence(tMs))
    }

    override fun shown(tMs: Long): Shown? {
        val gv = verdict
        val raw = fused
        return when {
            gv != null && (gv.spoofed || gv.refusedFix) && gv.hasPosition ->
                Shown(enOf(gv.displayLat, gv.displayLon), gv.displayHAccM.toDouble())
            raw != null -> {
                val sm = smoother.smooth(raw.lat, raw.lon, raw.speedMps, raw.elapsedMs)
                Shown(enOf(sm.lat, sm.lon), raw.accuracyM.toDouble())
            }
            gv != null && gv.displayHAccM > 0f -> Shown(enOf(gv.displayLat, gv.displayLon), gv.displayHAccM.toDouble())
            else -> null
        }
    }

    override fun alarm(tMs: Long): Alarm {
        val v = verdict
        val now = v?.status == ConsistencyGate.Status.SPOOFED
        if (now && !wasSpoof) spoofWhy.merge("${v!!.reason.name}@${tMs / 1000}с", 1, Int::plus)
        wasSpoof = now
        return alarmOf(v)
    }

    private fun alarmOf(v: ConsistencyGate.Verdict?): Alarm = when (v?.status) {
        ConsistencyGate.Status.SPOOFED -> Alarm.SPOOF
        ConsistencyGate.Status.DEGRADED -> Alarm.DOUBT
        else -> Alarm.NONE
    }
}

/** PhantomLocator's engine: its trust is the map chip, its fix is not drawn. */
internal class EngineChain(private val walking: Boolean) : Chain {
    override val name = "двигун (чип)"
    override val spoofWhy = HashMap<String, Int>()
    private var wasSpoof = false
    private val engine = CustomLocationEngine()
    private val pdr = PedestrianContinuity(PedestrianSensors(true, true, true, true, true))
    private var lastPdrMs = 0L

    override fun gps(s: GpsSample, tMs: Long) {
        engine.onGps(s, s.elapsedMs.takeIf { it in 1..tMs } ?: tMs, s.altitudeM)
        val f = engine.fix.value
        if (walking && f != null && !f.dead) {
            pdr.onTrustedFix(tMs, f.uncertaintyM.toDouble(), Math.toRadians(f.bearingDeg.toDouble()), f.speedMps.toDouble())
        }
    }

    override fun imu(s: ImuSample, tMs: Long) {
        engine.onImu(s, tMs)
        if (!walking) return
        pdr.onAccelerometer(tMs, s.accelX.toDouble(), s.accelY.toDouble(), s.accelZ.toDouble())
        pdr.onGyroscope(tMs, s.gyroX.toDouble(), s.gyroY.toDouble(), s.gyroZ.toDouble())
        val az = -2.0 * atan2(s.rotZ.toDouble(), s.rotW.toDouble())
        pdr.onAzimuth(tMs, az)
        pdr.onMagneticField(tMs, 20.0 * sin(az), 20.0 * cos(az), -44.0)
        if (tMs - lastPdrMs >= 500) {
            lastPdrMs = tMs
            val snap = pdr.snapshot(tMs)
            val b = snap.displacementBearingRad
            if (snap.informative && b != null && snap.displacementM > 0.0) {
                engine.onPedestrianDisplacement(
                    PedestrianDisplacement(
                        snap.displacementM * sin(b), snap.displacementM * cos(b), snap.accuracyM, true, tMs,
                    ),
                )
            }
        }
    }

    override fun pressure(hpa: Float, tMs: Long) {
        engine.onPressure(hpa, tMs)
        if (walking) pdr.onPressure(tMs, hpa.toDouble())
    }

    override fun tick(tMs: Long) = engine.onTick(tMs)

    override fun shown(tMs: Long): Shown? =
        engine.fix.value?.let { Shown(enOf(it.lat, it.lon), it.uncertaintyM.toDouble()) }

    override fun alarm(tMs: Long): Alarm {
        val now = engine.trust.value == LocationTrust.SPOOFED
        if (now && !wasSpoof) {
            val why = (engine.lastRejection.value ?: "—").replace(Regex("[0-9]+"), "N").take(60)
            spoofWhy.merge("$why@${tMs / 1000}с", 1, Int::plus)
        }
        wasSpoof = now
        return alarmOf()
    }

    private fun alarmOf(): Alarm = when (engine.trust.value) {
        LocationTrust.SPOOFED -> Alarm.SPOOF
        // Since 27.09 the chip words JAMMED as «Сигналу немає», not «глушать».
        LocationTrust.JAMMED -> Alarm.LOST
        LocationTrust.DEGRADED -> Alarm.DOUBT
        else -> Alarm.NONE
    }
}
