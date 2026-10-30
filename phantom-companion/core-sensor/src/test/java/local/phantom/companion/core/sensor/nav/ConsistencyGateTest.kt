package local.phantom.companion.core.sensor.nav

import local.phantom.companion.core.sensor.GpsSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PRECISION ENGINE, Section 4/5 — the consistency gate against the real capture.
 *
 * The scenario numbers are lifted from the live Kyiv REB event of 2026-07-21
 * (`~/phantom_ai/_briefs/field-evidence/reb-20260721/reb-rich.tsv`): the fused
 * provider frozen on a spoofed Lima fix with its hAcc exploding into the
 * kilometres, while the network provider held true in Kyiv the whole time.
 *
 * The bar the brief sets: verdict SPOOFED, the puck held at the last Kyiv
 * position and NEVER rendered in Peru, display source = network, and recovery
 * to fused only after it re-agrees with the network. Plus the pivot's own law:
 * a parked, un-spoofed phone must pass through untouched — no hold, no alarm.
 */
class ConsistencyGateTest {

    // — the capture, as data ————————————————————————————————————————————————

    /** The spoofed Lima fix the fused/gps provider was frozen on. */
    private val limaLat = -12.045950
    private val limaLon = -77.060334

    /** Where the network provider actually placed the phone, NW Kyiv. */
    private val kyivLat = 50.5040
    private val kyivLon = 30.4094

    private fun fused(
        lat: Double,
        lon: Double,
        hAccM: Float,
        elapsedMs: Long,
        speedMps: Float? = 0f,
    ) = GpsSample(
        lat = lat, lon = lon, accuracyM = hAccM,
        speedMps = speedMps, bearingDeg = null, altitudeM = null, elapsedMs = elapsedMs,
    )

    private fun net(lat: Double, lon: Double, hAccM: Float, elapsedMs: Long) =
        GpsSample(lat, lon, hAccM, null, null, null, elapsedMs)

    /** True Kyiv is +50°, spoofed Lima is −12° — a display latitude north of the
     *  equator can never be the Peru fix. The cheapest possible "never rendered
     *  in Peru" assertion, and it cannot be gamed by a rounding error. */
    private fun isKyivNotLima(v: ConsistencyGate.Verdict) {
        assertTrue("display slid toward Peru: lat=${v.displayLat}", v.displayLat > 40.0)
        assertTrue("display slid toward Peru: lon=${v.displayLon}", v.displayLon in 29.0..31.0)
    }

    // — the headline replay ——————————————————————————————————————————————————

    @Test
    fun replay_peru_spoof_holds_kyiv_never_renders_peru() {
        val gate = ConsistencyGate()
        // hAcc climbing exactly as the fused provider reported it aging.
        val fusedHAcc = listOf(19497.3f, 24139.4f, 28993.4f, 33621.8f, 38246.8f, 918193.6f)
        // network hAcc jitter, straight from the capture.
        val netHAcc = listOf(68.4f, 20.77f, 104.1f, 19.2f, 56.1f, 64.1f)

        fusedHAcc.forEachIndexed { i, hAcc ->
            val nowMs = 20_000L * (i + 1)
            val v = gate.update(
                // fused: frozen Lima fix — same elapsedMs every frame (et never advances).
                fused = fused(limaLat, limaLon, hAcc, elapsedMs = 1_000L, speedMps = 29.02f),
                // network: fresh Kyiv fix — elapsedMs advancing.
                network = net(
                    kyivLat + (i % 3) * 1e-4, kyivLon - (i % 2) * 1e-4,
                    netHAcc[i], elapsedMs = 10_000L + i * 20_000L,
                ),
                nowMs = nowMs,
            )
            assertTrue("frame $i must be SPOOFED", v.spoofed)
            assertEquals(ConsistencyGate.Source.NETWORK_HOLD, v.source)
            isKyivNotLima(v)
        }
        assertTrue(gate.spoofed)
    }

    /**
     * THE FALSE ACCUSATION, removed 2026-08-16.
     *
     * hAcc explosion used to be a hard trigger on its own, so a fused fix that
     * merely admitted kilometres of doubt — which is what Android reports on a
     * cold start indoors, before a single satellite is acquired — latched
     * SPOOFED and told the operator he was under electronic attack.
     *
     * «I do not know where I am» and «someone is deceiving me» are different
     * states. The vague fix is DEGRADED, it keeps its own honest radius, and the
     * sentence says «приблизно», not «підміна».
     */
    @Test
    fun a_vague_fix_is_uncertainty_not_an_accusation() {
        val gate = ConsistencyGate()
        val a = gate.update(fused(kyivLat, kyivLon, 12f, 1_000L), network = null, nowMs = 1_000L)
        assertFalse(a.spoofed)
        assertEquals(ConsistencyGate.Status.TRUSTED, a.status)

        val b = gate.update(fused(kyivLat, kyivLon, 20_000f, 2_000L), network = null, nowMs = 2_000L)
        assertFalse("kilometres of doubt is not evidence of a lie", b.spoofed)
        assertEquals(ConsistencyGate.Status.DEGRADED, b.status)
        assertEquals(ConsistencyGate.Reason.HACC_EXPLOSION, b.reason)
        // The doubt is stated, not hidden, and not converted into an accusation.
        assertEquals(20_000f, b.displayHAccM, 0f)
        assertEquals("must say what the doubt means, not its radius", UNCERTAIN_UA, b.message)
        assertFalse("must not cry spoof", b.message!!.contains("підсовують"))
    }

    /**
     * The cold start the owner actually performs: open the app indoors, the chip
     * has nothing yet, the fused provider answers with kilometres. Not one frame
     * of this may accuse anybody, and the latch must never arm — once armed it
     * needs five agreeing fixes to clear, which a phone indoors cannot supply.
     */
    @Test
    fun a_cold_start_indoors_never_latches_spoofed() {
        val gate = ConsistencyGate()
        val coarse = listOf(2_400f, 1_800f, 3_100f, 1_600f, 2_050f, 1_510f)
        coarse.forEachIndexed { i, hAcc ->
            // Scattering ±440 m around the anchor, as a coarse fix does — it
            // jitters, it does not march.
            val jitter = if (i % 2 == 0) 4e-3 else -4e-3
            val v = gate.update(
                fused(kyivLat + jitter, kyivLon - jitter, hAcc, elapsedMs = 1_000L + i * 1_000L),
                // A cell-tower anchor, equally coarse, roughly agreeing.
                net(kyivLat, kyivLon, 500f, elapsedMs = 1_000L + i * 1_000L),
                nowMs = 1_000L + i * 1_000L,
            )
            assertFalse("cold-start frame $i must not be spoofed", v.spoofed)
            assertEquals(ConsistencyGate.Status.DEGRADED, v.status)
        }
        assertFalse("the latch must not arm on uncertainty", gate.spoofed)
    }

    /**
     * Two coarse fixes may sit kilometres apart and both be honest — that is what
     * their radius means. Charging the separation to the speed ceiling read
     * uncertainty as a teleport.
     */
    @Test
    fun scatter_within_the_error_budget_is_not_a_teleport() {
        val gate = ConsistencyGate()
        gate.update(fused(kyivLat, kyivLon, 900f, 1_000L), network = null, nowMs = 1_000L)
        // 1,1 km away one second later — 1100 m/s against a 300 m/s ceiling, but
        // the two fixes admit 900 m each, so nothing unexplained remains.
        val v = gate.update(fused(kyivLat + 1e-2, kyivLon, 900f, 2_000L), network = null, nowMs = 2_000L)
        assertFalse("within the stated doubt, this is scatter, not a jump", v.spoofed)
    }

    @Test
    fun staleness_on_a_lie_is_flagged_as_staleness() {
        val gate = ConsistencyGate()
        // Frame 1: fused Lima vs net Kyiv — divergence (fused not yet known frozen).
        gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 10_000L),
            nowMs = 20_000L,
        )
        // Frame 2: fused et FROZEN (same 1_000L) while net advanced — captured sky.
        val v = gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 30_000L),
            nowMs = 40_000L,
        )
        assertTrue(v.spoofed)
        assertEquals(ConsistencyGate.Reason.STALENESS, v.reason)
    }

    @Test
    fun frozen_but_agreeing_sky_is_a_slow_indoor_hold_not_a_spoof() {
        val gate = ConsistencyGate()
        // Indoors: fused answers slowly (et frozen across ticks) but AGREES with
        // the network. The pivot forbids crying wolf here — no banner, no hold.
        gate.update(
            fused(kyivLat, kyivLon, 40f, elapsedMs = 1_000L),
            net(kyivLat + 2e-4, kyivLon, 60f, elapsedMs = 10_000L),
            nowMs = 20_000L,
        )
        val v = gate.update(
            fused(kyivLat, kyivLon, 40f, elapsedMs = 1_000L), // frozen et
            net(kyivLat + 2e-4, kyivLon, 60f, elapsedMs = 30_000L), // net advanced
            nowMs = 40_000L,
        )
        assertFalse("a slow but agreeing sky must not alarm", v.spoofed)
        assertEquals(ConsistencyGate.Reason.NONE, v.reason)
    }

    @Test
    fun parked_unspoofed_is_passthrough_no_hold() {
        val gate = ConsistencyGate()
        // A still phone: fused jitters within its own accuracy, network agrees.
        repeat(10) { i ->
            val jitter = if (i % 2 == 0) 3e-5 else -3e-5 // ~3 m of multipath scatter
            val f = fused(kyivLat + jitter, kyivLon + jitter, 15f, elapsedMs = 1_000L + i * 1_000L)
            val v = gate.update(
                f,
                net(kyivLat, kyivLon, 45f, elapsedMs = 500L + i * 1_000L),
                nowMs = 1_000L + i * 1_000L,
            )
            assertFalse("parked frame $i must not be spoofed", v.spoofed)
            assertEquals(ConsistencyGate.Source.FUSED, v.source)
            // Pass-through: the displayed position IS the fused fix, unchanged.
            assertEquals(f.lat, v.displayLat, 0.0)
            assertEquals(f.lon, v.displayLon, 0.0)
        }
    }

    @Test
    fun recovery_only_after_reagreement() {
        val gate = ConsistencyGate()
        // Establish the spoof: fused Lima, network Kyiv.
        repeat(3) { i ->
            val v = gate.update(
                fused(limaLat, limaLon, 20_000f, elapsedMs = 1_000L),
                net(kyivLat, kyivLon, 60f, elapsedMs = 10_000L + i * 20_000L),
                nowMs = 20_000L + i * 20_000L,
            )
            assertTrue(v.spoofed)
        }

        // The sky returns, agreeing with the network. Trust must NOT snap back on
        // the first frame — a spoofer can produce one plausible fix.
        var lastSource = ConsistencyGate.Source.NONE
        for (i in 1..ConsistencyGate.RECOVER_AGREE_STREAK) {
            val nowMs = 100_000L + i * 1_000L
            val v = gate.update(
                fused(kyivLat, kyivLon, 12f, elapsedMs = 200_000L + i * 1_000L),
                net(kyivLat, kyivLon, 55f, elapsedMs = 200_000L + i * 1_000L),
                nowMs = nowMs,
            )
            lastSource = v.source
            if (i < ConsistencyGate.RECOVER_AGREE_STREAK) {
                assertTrue("must still hold on agreeing frame $i", v.spoofed)
            } else {
                assertFalse("trust must return on frame $i", v.spoofed)
            }
        }
        assertEquals(ConsistencyGate.Source.FUSED, lastSource)
        assertFalse(gate.spoofed)
    }

    @Test
    fun clean_drive_passes_through() {
        val gate = ConsistencyGate()
        // A moving car, fused and network broadly agreeing (network coarser).
        var lat = kyivLat
        repeat(15) { i ->
            lat += 1e-4 // ~11 m/step north
            val f = fused(lat, kyivLon, 8f, elapsedMs = 1_000L + i * 1_000L, speedMps = 11f)
            val v = gate.update(
                f,
                net(lat + 5e-4, kyivLon, 80f, elapsedMs = 1_000L + i * 1_000L),
                nowMs = 1_000L + i * 1_000L,
            )
            assertFalse("driving frame $i must not be spoofed", v.spoofed)
            assertEquals(ConsistencyGate.Source.FUSED, v.source)
            assertEquals(f.lat, v.displayLat, 0.0)
        }
    }

    /**
     * FIELD REGRESSION — the network provider does NOT track a moving car: its
     * fix lags hundreds of metres behind while still reporting a tight accuracy.
     * That is honest lag, not a spoof, and the old bare-divergence hard-trip
     * demoted the puck to the stale coarse network fix and stamped it dead —
     * pinning the map dot off-road and killing routing on a real drive. The gate
     * must keep the fused fix displayed here; only hAcc / kinematic / staleness
     * may ever demote it.
     */
    @Test
    fun moving_network_lag_is_not_a_spoof() {
        val gate = ConsistencyGate()
        var lat = kyivLat
        repeat(15) { i ->
            lat += 1e-4 // ~11 m/step north
            val f = fused(lat, kyivLon, 8f, elapsedMs = 1_000L + i * 1_000L, speedMps = 13f)
            // Network sits ~300 m BEHIND the true position (27e-4° ≈ 300 m) and
            // still claims a tight 40 m — divergence ~300 m > 40+150, the exact
            // shape that made the divergence trip fire on an honest drive.
            val laggingNet = net(lat - 27e-4, kyivLon, 40f, elapsedMs = 1_000L + i * 1_000L)
            val v = gate.update(f, laggingNet, nowMs = 1_000L + i * 1_000L)
            assertFalse("moving network lag frame $i must not be spoofed", v.spoofed)
            assertEquals(ConsistencyGate.Source.FUSED, v.source)
            assertEquals(f.lat, v.displayLat, 0.0)
            assertEquals(f.lon, v.displayLon, 0.0)
        }
    }

    // — measured on the phone, 2026-08-06 ——————————————————————————————————

    /** ~446 m north of Kyiv — the provider handoff, to scale. */
    private val handoffLat = kyivLat + 4.0e-3

    /**
     * The Android fused provider hands off between GPS and network and relocates
     * hundreds of metres in one second. Measured at 20:36:09 on 2026-08-06: 446 m
     * in one tick, which is 1 600 km/h and trips any bare speed ceiling — while
     * the network anchor stood right next to the new position agreeing with it to
     * 0,00 m. Calling that an attack threw the puck 446 m across the map and back
     * four seconds later, which is the sawtooth the operator photographed.
     *
     * The witness a spoofer cannot reach said yes. That settles it.
     */
    @Test
    fun a_jump_the_network_corroborates_is_a_handoff_not_a_spoof() {
        val gate = ConsistencyGate()
        val a = gate.update(
            fused(kyivLat, kyivLon, 21f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 45f, elapsedMs = 1_000L),
            nowMs = 1_000L,
        )
        assertEquals(ConsistencyGate.Source.FUSED, a.source)

        // One second later, 446 m away — and the network is there too.
        val f = fused(handoffLat, kyivLon, 56f, elapsedMs = 2_000L)
        val v = gate.update(f, net(handoffLat, kyivLon, 55f, elapsedMs = 2_000L), nowMs = 2_000L)

        assertFalse("a corroborated jump is not an attack", v.spoofed)
        assertEquals(ConsistencyGate.Source.FUSED, v.source)
        assertEquals(f.lat, v.displayLat, 0.0)
    }

    /**
     * The cure that was worse than the disease: on a hard trip the gate used to
     * display the network anchor, which sits hundreds of metres from where the
     * sky had the operator. It moved the puck by exactly the distance it had just
     * refused to move it. A hold must HOLD.
     */
    @Test
    fun a_hold_freezes_the_puck_it_does_not_teleport_it_to_the_anchor() {
        val gate = ConsistencyGate()
        gate.update(
            fused(kyivLat, kyivLon, 12f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 45f, elapsedMs = 1_000L),
            nowMs = 1_000L,
        )
        // A real trip: the sky jumps to Lima admitting kilometres, contradicting
        // a healthy network anchor 446 m from the last trusted place.
        val v = gate.update(
            fused(limaLat, limaLon, 20_000f, elapsedMs = 2_000L),
            net(handoffLat, kyivLon, 55f, elapsedMs = 2_000L),
            nowMs = 2_000L,
        )
        assertTrue(v.spoofed)
        assertEquals(ConsistencyGate.Source.HELD, v.source)
        assertEquals("the hold must not move the puck at all", kyivLat, v.displayLat, 1e-9)
    }

    /**
     * Freezing is only honest while it is brief. Through a Kyiv-class capture the
     * operator keeps walking, and a puck nailed to where he was sixteen minutes
     * ago is the bigger lie — past [ConsistencyGate.NETWORK_LEAD_AFTER_MS] the
     * coarse anchor takes over.
     */
    @Test
    fun a_long_hold_lets_the_anchor_lead_rather_than_freeze_a_lie() {
        val gate = ConsistencyGate()
        gate.update(
            fused(kyivLat, kyivLon, 12f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 45f, elapsedMs = 1_000L),
            nowMs = 1_000L,
        )
        var v = gate.update(
            fused(limaLat, limaLon, 20_000f, elapsedMs = 2_000L),
            net(kyivLat, kyivLon, 55f, elapsedMs = 2_000L),
            nowMs = 2_000L,
        )
        assertEquals(ConsistencyGate.Source.HELD, v.source)

        // Still refused, well past the freeze window, and the anchor now places
        // the operator 446 m away — far beyond its own admitted error.
        v = gate.update(
            fused(limaLat, limaLon, 20_000f, elapsedMs = 3_000L),
            net(handoffLat, kyivLon, 55f, elapsedMs = 40_000L),
            nowMs = 2_000L + ConsistencyGate.NETWORK_LEAD_AFTER_MS,
        )
        assertTrue(v.spoofed)
        assertEquals(ConsistencyGate.Source.NETWORK_HOLD, v.source)
        assertEquals(handoffLat, v.displayLat, 1e-9)
    }

    /**
     * THE OWNER'S PHONE, 2026-08-16 (`adb shell dumpsys location`, Galaxy A56):
     *
     *   gps      −12.048219, −77.052376  hAcc 39,3 m, 6 satellites, 56,93 m/s, ~15 h old
     *   network  49.973602, 28.796112    hAcc 100 m  (Ukraine, correct)
     *
     * A tight, satellite-shaped, high-confidence lie — hAcc THIRTY-NINE METRES,
     * nowhere near the 1500 m ceiling. This is the case that proves the hAcc
     * trigger was never what caught a real capture, and that demanding
     * corroboration for it costs nothing: the Peru fix is caught by the sky
     * being frozen on a position an independent witness contradicts.
     */
    @Test
    fun the_owners_peru_fix_is_still_caught_at_thirty_nine_metres() {
        val gate = ConsistencyGate()
        val uaLat = 49.973602
        val uaLon = 28.796112

        // Frame 1: the stale sky arrives looking healthy — one frame is not yet
        // evidence, so it passes through (it is refused the ANCHOR, not the screen).
        val a = gate.update(
            fused(limaLat, limaLon, 39.3f, elapsedMs = 1_000L, speedMps = 56.93f),
            net(uaLat, uaLon, 100f, elapsedMs = 10_000L),
            nowMs = 20_000L,
        )
        assertEquals("39 m is far below the hAcc ceiling", ConsistencyGate.Status.TRUSTED, a.status)

        // Frame 2: the sky has not moved (a 15-hour-old fix never does) while the
        // network advanced. Frozen on a position the network contradicts.
        val v = gate.update(
            fused(limaLat, limaLon, 39.3f, elapsedMs = 1_000L, speedMps = 56.93f),
            net(uaLat, uaLon, 100f, elapsedMs = 30_000L),
            nowMs = 40_000L,
        )
        assertTrue("a frozen sky contradicting the network is a capture", v.spoofed)
        assertEquals(ConsistencyGate.Status.SPOOFED, v.status)
        assertEquals(ConsistencyGate.Reason.STALENESS, v.reason)
        // And Peru is never what gets drawn.
        assertTrue("must never render Peru: lat=${v.displayLat}", v.displayLat > 40.0)
        assertEquals(uaLat, v.displayLat, 1e-9)
    }

    /**
     * A caught spoof that then loses its network anchor has nothing left to show.
     * The old code returned 0,0 with `spoofed = true` — and 0,0 is a real place in
     * the Gulf of Guinea, which any consumer that reads displayLat without asking
     * whether it means anything will happily draw.
     */
    @Test
    fun a_spoof_with_nothing_left_to_show_claims_no_position() {
        val gate = ConsistencyGate()
        gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 10_000L),
            nowMs = 20_000L,
        )
        val latched = gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 30_000L),
            nowMs = 40_000L,
        )
        assertTrue(latched.spoofed)

        // The network witness now vanishes too — jammed, or no cell at all.
        val v = gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            network = null,
            nowMs = 50_000L,
        )
        assertTrue(v.spoofed)
        assertEquals(ConsistencyGate.Source.NONE, v.source)
        assertFalse("0,0 is not a position and must not be offered as one", v.hasPosition)
        assertTrue("must say it does not know", v.messageEn!!.contains("unknown"))
    }

    /** Three states, three sentences. The accusation is never lent to the others. */
    @Test
    fun uncertainty_and_silence_never_borrow_the_spoof_sentence() {
        val degraded = ConsistencyGate().update(
            fused(kyivLat, kyivLon, 3_000f, 1_000L), network = null, nowMs = 1_000L,
        )
        val noFix = ConsistencyGate().update(fused = null, network = null, nowMs = 1_000L)

        assertEquals(ConsistencyGate.Status.DEGRADED, degraded.status)
        assertEquals(ConsistencyGate.Status.NO_FIX, noFix.status)
        listOf(degraded, noFix).forEach {
            assertFalse("«$it» must not accuse", it.message!!.contains("підсовують"))
            assertFalse(it.spoofed)
        }
        assertEquals("Де ти — поки не знаю", noFix.message)
        assertEquals(UNCERTAIN_UA, degraded.message)
    }

    /**
     * A spoof whose hAcc stays plausible walks in as a normal fix on its first
     * frame. It must not become the place a later hold freezes on — the anchor is
     * the last place fused and network AGREED, never merely the last fix shown.
     */
    @Test
    fun a_diverging_fix_never_becomes_the_trusted_anchor() {
        val gate = ConsistencyGate()
        // Frame 1: Lima at a believable hAcc, network true in Kyiv. Displayed
        // (the pivot's rule), but not trusted.
        gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 10_000L),
            nowMs = 20_000L,
        )
        // Frame 2: the sky freezes on that lie — a hard trip.
        val v = gate.update(
            fused(limaLat, limaLon, 120f, elapsedMs = 1_000L),
            net(kyivLat, kyivLon, 60f, elapsedMs = 30_000L),
            nowMs = 40_000L,
        )
        assertTrue(v.spoofed)
        isKyivNotLima(v)
    }
}
