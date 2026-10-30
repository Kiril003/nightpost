package local.phantom.companion.core.sensor.nav.sim

import kotlin.math.cos
import kotlin.math.sin
import local.phantom.companion.core.sensor.GpsSample
import local.phantom.companion.core.sensor.ImuSample
import local.phantom.companion.core.sensor.nav.DeadReckoner
import local.phantom.companion.core.sensor.nav.SpoofGate
import org.junit.Test

/**
 * Isolation probes. The matrix says three things are badly wrong; a matrix is
 * a big machine, and a big machine that reports a bug is just as likely to
 * have one. Each probe below reproduces a single claim against a single class,
 * with no simulator in the way.
 */
class EwFindingsProbeTest {

    /**
     * CLAIM B2 — dead reckoning does not drift. It PARKS.
     *
     * A car at a steady 30 m/s produces (almost) no linear acceleration. The
     * reckoner reads «below the noise floor» as «stopped» and bleeds the speed
     * off at 33%/s. So in a tunnel the dot does not wander away from the
     * operator — it stops dead at the tunnel mouth while he keeps driving.
     */
    @Test
    fun `probe B2 — a cruising operator is reckoned to a standstill`() {
        val dr = DeadReckoner()
        dr.seed(speedMps = 30f, bearingDeg = 0f, nowMs = 0L)

        // Perfectly steady cruise: zero linear acceleration, heading due north.
        val cruising = ImuSample(
            accelX = 0f, accelY = 0f, accelZ = 9.81f,
            gyroX = 0f, gyroY = 0f, gyroZ = 0f,
            rotW = 1f, rotX = 0f, rotY = 0f, rotZ = 0f,
        )

        var t = 0L
        val trace = mutableListOf<Pair<Long, Float>>()
        repeat(500) { // 10 s at 50 Hz
            t += 20
            dr.onImu(cruising, t)
            if (t % 1000L == 0L) trace += t / 1000 to dr.speed
        }

        println("── B2: reckoned speed of an operator holding 30 m/s ──")
        trace.forEach { (s, v) -> println("  t=${s}s  speed=%.2f m/s".format(v)) }

        val after10s = dr.speed
        println("  after 10s: %.2f m/s (truth: 30.00)".format(after10s))
    }

    /**
     * CLAIM B1 — one gap in the fixes and the gate never believes the sky again.
     *
     * The teleport rule compares a raw distance against a constant, with no
     * reference to how much TIME passed. After a two-minute tunnel the operator
     * has legitimately moved 1.3 km, so his first real fix is «a teleport» —
     * and because a rejected fix is (correctly!) not allowed to become the new
     * reference, every subsequent real fix is measured against the same stale
     * point and rejected too. The lockout is permanent.
     */
    @Test
    fun `probe B1 — a tunnel locks the gate out of the real sky forever`() {
        val gate = SpoofGate()
        var t = 0L

        fun fixAt(north: Double, acc: Float = 5f) = GpsSample(
            lat = 50.4501 + Math.toDegrees(north / 6_371_000.0),
            lon = 30.5234,
            accuracyM = acc,
            speedMps = 11f,
            bearingDeg = 0f,
        )

        // Driving normally at 11 m/s, one fix per second.
        repeat(10) {
            t += 1_000
            val v = gate.judge(fixAt(11.0 * it), t)
            require(v.accepted) { "warmup fix rejected: ${v.reason}" }
        }
        val beforeTunnel = 11.0 * 9

        // A 120 s tunnel: no fixes at all. The operator keeps driving.
        t += 120_000
        val travelled = 11.0 * 120

        println("── B1: re-acquiring the sky after a 120 s tunnel ──")
        var acceptedAgain = 0
        var rejected = 0
        // The sky comes back and reports the truth, once a second, for a minute.
        repeat(60) { i ->
            t += 1_000
            val truthNorth = beforeTunnel + travelled + 11.0 * i
            val v = gate.judge(fixAt(truthNorth), t)
            if (v.accepted) acceptedAgain++ else rejected++
            if (i < 3 || i == 59) {
                println("  +${i + 1}s: ${if (v.accepted) "ACCEPTED" else "REFUSED — ${v.reason}"}")
            }
        }
        println("  60 real fixes after the tunnel → accepted=$acceptedAgain refused=$rejected")
        println("  consecutiveRejects=${gate.consecutiveRejects}")
    }

    /**
     * CLAIM B4 — the patient spoofer walks straight through the gate.
     *
     * Not a bug in the code: the gate does exactly what it says, rejecting the
     * impossible and not the merely false. This probe simply prices that
     * decision — how far can an adversary move the operator while every single
     * fix remains, individually, entirely plausible?
     */
    @Test
    fun `probe B4 — how far a patient spoofer can drag the operator, unchallenged`() {
        println("── B4: displacement achieved with ZERO rejections ──")
        for (dragMps in listOf(0.5, 1.0, 2.0, 5.0, 10.0)) {
            val gate = SpoofGate()
            var t = 0L
            var rejects = 0
            val minutes = 10
            repeat(minutes * 60) { i ->
                t += 1_000
                // The operator is standing still. The sky slides him sideways.
                val lied = GpsSample(
                    lat = 50.4501,
                    lon = 30.5234 + Math.toDegrees(
                        (dragMps * i) / (6_371_000.0 * cos(Math.toRadians(50.4501))),
                    ),
                    accuracyM = 5f,
                    speedMps = dragMps.toFloat(),
                    bearingDeg = 90f,
                )
                if (!gate.judge(lied, t).accepted) rejects++
            }
            val moved = dragMps * minutes * 60
            println(
                "  drag %5.1f m/s → moved %7.0f m in ${minutes} min, rejections=%d".format(
                    dragMps, moved, rejects,
                ),
            )
        }
    }

    /** Sanity: the gate DOES catch the crude spoof, so the probes above are not vacuous. */
    @Test
    fun `probe control — the crude teleport is still caught`() {
        val gate = SpoofGate()
        var t = 1_000L
        gate.judge(GpsSample(50.4501, 30.5234, 5f, 0f, 0f), t)
        t += 1_000
        val v = gate.judge(GpsSample(50.5501, 30.5234, 5f, 0f, 0f), t) // ~11 km leap
        println("── control: 11 km leap in 1 s → ${if (v.accepted) "ACCEPTED (!!)" else "REFUSED — ${v.reason}"}")
        require(!v.accepted) { "the gate failed to catch an 11 km teleport" }
    }
}
