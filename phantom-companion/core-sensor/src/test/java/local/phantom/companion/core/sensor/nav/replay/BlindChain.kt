package local.phantom.companion.core.sensor.nav.replay

import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import local.phantom.companion.core.sensor.nav.BlindNavigator
import local.phantom.companion.core.sensor.nav.RoadGraph

/** The live display with the blind navigator in the seam AppContainer gives it. */
internal class BlindChain(graph: RoadGraph?, private val raw: Boolean = false) : Chain {
    override val name = if (raw) "навігатор (сирий)" else "новий показ"
    private val live = LiveDisplayChain()
    override val spoofWhy get() = live.spoofWhy
    private val nav = BlindNavigator(graph?.let { g -> { _: Double, _: Double -> g } })

    override fun gps(s: GpsSample, tMs: Long) {
        live.gps(s, tMs)
        nav.onFix(s, tMs, distrusted = live.gateSpoofed)
    }

    override fun network(s: GpsSample, tMs: Long) = live.network(s, tMs)

    override fun imu(s: ImuSample, tMs: Long) {
        live.imu(s, tMs)
        nav.onImu(s, tMs)
    }

    override fun pressure(hpa: Float, tMs: Long) {
        live.pressure(hpa, tMs)
        nav.onPressure(hpa, tMs)
    }

    override fun tick(tMs: Long) {
        live.tick(tMs)
        nav.onTick(tMs)
    }

    override fun shown(tMs: Long): Shown? {
        val est = nav.estimate(tMs)
        if (est != null && (raw || BlindNavigator.shows(est))) return Shown(enOf(est.lat, est.lon), est.uncertaintyM.toDouble())
        return live.shown(tMs)
    }

    override fun alarm(tMs: Long): Alarm = live.alarm(tMs)
}
