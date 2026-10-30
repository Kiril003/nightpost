package local.phantom.companion.core.sensor.nav

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.sensor.GpsSample

/**
 * PRECISION ENGINE, Section 4 — the live host for the [ConsistencyGate].
 *
 * The precision pivot put the raw fused fix on the display path. This sits in
 * that path and does one thing: it holds every fused fix up against the
 * independent network anchor before the display trusts it. In the normal case
 * the fused fix passes straight through — the pivot is untouched. Only on
 * positive evidence of a spoof does it intervene, and then the display shows the
 * network-anchored hold, never the teleport.
 *
 * Two triggers feed the gate:
 *  - a fresh fused fix (the common case), and
 *  - a 1 Hz tick, which exists solely to notice the fused feed going *silent* or
 *    *frozen* — the jamming/spoof-freeze case, where nothing new arrives to say
 *    that nothing is arriving (the same reason [PhantomLocator] runs a tick).
 *
 * Android-thin: it owns no sensors, only two [StateFlow]s it was handed. All the
 * judgement is in the pure gate; this is glue plus the per-fix flight-recorder line.
 */
class GuardedLocationSource(
    private val fusedFeed: StateFlow<GpsSample?>,
    private val networkFeed: StateFlow<GpsSample?>,
    private val gate: ConsistencyGate = ConsistencyGate(),
    private val blackBox: BlackBox? = null,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    /**
     * ПОЛЬОВА ПОМИЛКА, 23.08 — the operator's own body (air pressure,
     * vibration), read fresh for every fix. The default says «no witnesses»,
     * which is exactly what a device with no barometer and no IMU lease must
     * produce: the gate then behaves as it did before these checks existed.
     */
    private val body: (Long) -> BodyEvidence = { BodyEvidence() },
    private val onJudged: ((GpsSample?, ConsistencyGate.Verdict, Long) -> Unit)? = null,
) {
    private val _guarded = MutableStateFlow<ConsistencyGate.Verdict?>(null)
    /** The gate's latest decision. The display reads this, not the raw fused fix. */
    val guarded: StateFlow<ConsistencyGate.Verdict?> = _guarded.asStateFlow()

    private val _banner = MutableStateFlow<String?>(null)
    /**
     * The operator-facing sentence, Ukrainian. Non-null whenever the gate is not
     * simply trusting the sky — spoof, no fix, or a fix too vague to steer by.
     *
     * It used to carry only the spoof sentence, so «сигналу немає» and «позиція
     * приблизна» had no way to reach the screen at all and the only thing the
     * operator could ever be told was that he was under attack.
     */
    val banner: StateFlow<String?> = _banner.asStateFlow()

    private val jobs = mutableListOf<Job>()

    /** elapsedMs of the last fused fix routed to the gate, so the tick can tell a
     *  frozen feed (its job) from a fresh fix (the collector's job). */
    private var lastRoutedElapsed = Long.MIN_VALUE

    fun start(scope: CoroutineScope) {
        if (jobs.isNotEmpty()) return
        jobs += scope.launch {
            fusedFeed.collect { fused ->
                if (fused != null && fused.elapsedMs != lastRoutedElapsed) {
                    lastRoutedElapsed = fused.elapsedMs
                    judge(fused)
                }
            }
        }
        jobs += scope.launch {
            while (true) {
                delay(TICK_MS)
                val f = fusedFeed.value
                // Judge from the tick ONLY when the fused feed is not producing
                // fresh fixes — the frozen/silent case this tick exists to catch.
                // A fresh fix is the collector's, and must not be double-counted.
                if (f == null || f.elapsedMs == lastRoutedElapsed) judge(f)
            }
        }
    }

    private fun judge(fused: GpsSample?) {
        val network = networkFeed.value
        val now = nowMs()
        val v = gate.update(fused, network, now, body(now))
        _guarded.value = v
        _banner.value = v.message
        blackBox?.recordGuard(fused, network, v)
        onJudged?.invoke(fused, v, now)
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    fun reset() {
        gate.reset()
        lastRoutedElapsed = Long.MIN_VALUE
        _guarded.value = null
        _banner.value = null
    }

    private companion object {
        const val TICK_MS = 1_000L
    }
}
