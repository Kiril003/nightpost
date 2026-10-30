package local.phantom.companion.core.sensor.nav.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PHANTOM-EW — the drift matrix.
 *
 * The findings themselves are written up in `docs/EW_DRIFT_MATRIX.md`; the
 * defects are reproduced against the bare classes in `EwFindingsProbeTest`.
 * What lives here is the sweep (which prints the matrix) and the invariants
 * that must hold no matter how F1–F4 are eventually repaired.
 */
class EwDriftMatrixTest {

    private fun sweep(): List<Verdict> {
        val out = mutableListOf<Verdict>()

        // — Baseline: does the engine track a clean drive at all?
        for (p in DriveProfile.values()) {
            out += EwSimulator(Scenario("clear/${p.name}", p, Sky.Clear())).run()
        }
        for (p in DriveProfile.values()) {
            out += EwSimulator(Scenario("canyon/${p.name}", p, Sky.Canyon())).run()
        }

        // — Blackouts: the tunnel. How fast does dead reckoning rot, by speed?
        for (p in DriveProfile.values()) {
            for (sec in listOf(10, 30, 60, 120, 300)) {
                out += EwSimulator(
                    Scenario("blackout${sec}s/${p.name}", p, Sky.Blackout(sec)),
                ).run()
            }
        }

        // — The mount-yaw question: the phone is not aligned with the road.
        for (yaw in listOf(0.0, 15.0, 40.0, 90.0, 180.0)) {
            out += EwSimulator(
                Scenario(
                    "blackout60s/yaw${yaw.toInt()}",
                    DriveProfile.URBAN,
                    Sky.Blackout(60),
                    mountYawDeg = yaw,
                ),
            ).run()
        }

        // — Spoofing.
        out += EwSimulator(
            Scenario("spoof-teleport/URBAN", DriveProfile.URBAN, Sky.Teleport()),
        ).run()

        // The patient adversary, at speeds the gate cannot call impossible.
        for (drag in listOf(1.0, 5.0, 15.0, 50.0)) {
            out += EwSimulator(
                Scenario(
                    "spoof-drag${drag.toInt()}mps/URBAN",
                    DriveProfile.URBAN,
                    Sky.Drag(drag, 180),
                ),
            ).run()
        }

        out += EwSimulator(
            Scenario("spoof-altitude/URBAN", DriveProfile.URBAN, Sky.AltitudeLie()),
        ).run()

        return out
    }

    /**
     * The invariants that must hold **whatever** is done about F1–F4.
     *
     * Deliberately does NOT pin the defects. A characterisation test that
     * freezes «dead reckoning parks» as expected behaviour would turn the bug
     * into a specification, and the next engineer would have to argue with a
     * green test to fix it. These assert only what is true now and must stay
     * true after the repair.
     */
    @Test
    fun `engine invariants that survive any fix to F1-F4`() {
        // 1. It tracks a clean sky. If this breaks, nothing else matters.
        for (p in DriveProfile.values()) {
            val v = EwSimulator(Scenario("clear/${p.name}", p, Sky.Clear())).run()
            assertTrue(
                "clear-sky tracking fell apart for $p: mean error ${v.meanErrorM} m",
                v.meanErrorM < 25.0,
            )
            assertEquals("a clean sky must never be refused ($p)", 0L, v.refused)
        }

        // 2. The crude spoof — the one the gate exists for — is caught, fast.
        val teleport = EwSimulator(
            Scenario("spoof-teleport", DriveProfile.URBAN, Sky.Teleport()),
        ).run()
        val detect = teleport.timeToDetectS
        assertTrue("a 5 km teleport went undetected", detect != null)
        assertTrue("teleport took ${detect}s to detect", detect!! <= 10.0)

        // 3. The barometer catches a forged altitude. It is the one witness a
        //    distant transmitter cannot bribe, and it must keep testifying.
        val altLie = EwSimulator(
            Scenario("spoof-altitude", DriveProfile.URBAN, Sky.AltitudeLie()),
        ).run()
        assertTrue("a forged altitude climb was not challenged", altLie.refused > 0)

        // 4. Doubt must GROW while the sky is dark. An engine that goes blind
        //    without widening its error bar is the confident dot in the wrong
        //    street — the exact thing the whole design exists to prevent.
        val short = EwSimulator(
            Scenario("b30", DriveProfile.URBAN, Sky.Blackout(30)),
        ).run()
        val long = EwSimulator(
            Scenario("b300", DriveProfile.URBAN, Sky.Blackout(300)),
        ).run()
        assertTrue(
            "uncertainty did not grow with the length of the blackout " +
                "(${short.meanClaimedM} m at 30 s vs ${long.meanClaimedM} m at 300 s)",
            long.meanClaimedM > short.meanClaimedM * 2,
        )
    }

    @Test
    fun `drift matrix — exploratory`() {
        val rows = sweep()
        println(
            "%-28s %7s %8s %8s %9s %7s %7s %6s %6s %9s".format(
                "scenario", "meanE", "maxE", "finalE", "claimed", "worstR", ">1σ", "acc", "ref", "detect",
            ),
        )
        for (v in rows) {
            println(
                "%-28s %7.1f %8.1f %8.1f %9.1f %7.2f %6.0f%% %6d %6d %9s  %s".format(
                    v.scenario,
                    v.meanErrorM,
                    v.maxErrorM,
                    v.finalErrorM,
                    v.meanClaimedM,
                    v.worstHonestyRatio,
                    v.beyond1Sigma * 100,
                    v.accepted,
                    v.refused,
                    v.timeToDetectS?.let { "%.0fs".format(it) } ?: "NEVER",
                    "%s  drift=%.0f/%.0f".format(v.endTrust, v.maxDriftResidualM, v.maxDriftBudgetM),
                ),
            )
        }
    }
}
