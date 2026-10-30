package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 hotfix #2 — pin the LocationContextProvider contract and the
 * formatter that injects coords into the AI prompt.
 */
class LocationContextProviderTest {

    @Test
    fun `formatter emits LOCATION block for valid coords`() {
        val s = LocationContextFormatter.format(LocationFix(50.4501, 30.5234))
        assertEquals("[LOCATION:50.4501,30.5234]", s)
    }

    @Test
    fun `formatter rounds to 4 decimals`() {
        val s = LocationContextFormatter.format(LocationFix(49.123456789, 23.987654321))
        assertEquals("[LOCATION:49.1235,23.9877]", s)
    }

    @Test
    fun `formatter uses dot as decimal separator regardless of locale`() {
        // String.format(Locale.US, ...) avoids comma decimals on uk-UA hosts.
        val s = LocationContextFormatter.format(LocationFix(49.5, 23.5))
        assertEquals("[LOCATION:49.5000,23.5000]", s)
    }

    @Test
    fun `formatter emits unknown when coords are null`() {
        assertEquals("[LOCATION:unknown]", LocationContextFormatter.format(null))
    }

    // ── R1.1 — extended ambient header (accuracy / movement / address) ──

    @Test
    fun `R1_1 extended header carries accuracy movement and address`() {
        val s = LocationContextFormatter.format(
            LocationFix(50.4501, 30.5234, accuracyM = 12.4f, speedMps = 1.4f),
            address = "Хрещатик, Київ",
        )
        assertEquals(
            "[LOCATION:50.4501,30.5234 ±12m рух:іде пішки адреса:\"Хрещатик, Київ\"]",
            s,
        )
    }

    @Test
    fun `R1_1 extras absent keeps pre-R1 byte-identical output`() {
        val s = LocationContextFormatter.format(LocationFix(50.4501, 30.5234), address = null)
        assertEquals("[LOCATION:50.4501,30.5234]", s)
    }

    @Test
    fun `R1_1 blank address is omitted`() {
        val s = LocationContextFormatter.format(LocationFix(50.0, 30.0), address = " ")
        assertEquals("[LOCATION:50.0000,30.0000]", s)
    }

    @Test
    fun `R1_1 movement labels cover all speed bands`() {
        assertNull(LocationContextFormatter.movementLabel(null))
        assertEquals("стоїть", LocationContextFormatter.movementLabel(0.2f))
        assertEquals("іде пішки", LocationContextFormatter.movementLabel(1.5f))
        assertEquals("їде повільно", LocationContextFormatter.movementLabel(5f))
        assertEquals("їде", LocationContextFormatter.movementLabel(15f))
    }

    @Test
    fun `provider returning sensor coords is forwarded`() {
        val sensorCoords = LocationFix(49.8397, 24.0297)
        val provider = LocationContextProvider { sensorCoords }
        assertEquals(sensorCoords, provider.current())
    }

    @Test
    fun `provider falling back through both null sources returns null`() {
        // Models the AppContainer composition: sensor lookup returns
        // null, last-known lookup returns null, composite returns null.
        val sensorLookup: () -> LocationFix? = { null }
        val lastKnownLookup: () -> LocationFix? = { null }
        val provider = LocationContextProvider { sensorLookup() ?: lastKnownLookup() }
        assertNull(provider.current())
    }

    @Test
    fun `provider prefers sensor coords over last known fallback`() {
        val sensorLookup: () -> LocationFix? = { LocationFix(1.0, 2.0) }
        val lastKnownLookup: () -> LocationFix? = { LocationFix(99.0, 99.0) }
        val provider = LocationContextProvider { sensorLookup() ?: lastKnownLookup() }
        assertEquals(LocationFix(1.0, 2.0), provider.current())
    }

    @Test
    fun `provider falls back to last known when sensor null`() {
        val sensorLookup: () -> LocationFix? = { null }
        val lastKnownLookup: () -> LocationFix? = { LocationFix(49.0, 24.0) }
        val provider = LocationContextProvider { sensorLookup() ?: lastKnownLookup() }
        assertEquals(LocationFix(49.0, 24.0), provider.current())
    }

    /* ---- W1-4: DefaultLocationContextProvider chain ---- */

    private val sensorFix = LocationFix(50.0, 30.0)
    private val passiveFix = LocationFix(50.1, 30.1)
    private val networkFix = LocationFix(50.2, 30.2)
    private val cloudFix = 50.3 to 30.3

    @Test
    fun `chain branch 1 — sensor wins over everything below`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { sensorFix },
            passiveProvider = { passiveFix },
            networkProvider = { networkFix },
            webGeoIpFallback = WebGeoIpFallback { cloudFix },
        )
        assertEquals(sensorFix, provider.current())
    }

    @Test
    fun `chain branch 2 — passive wins when sensor is null`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { passiveFix },
            networkProvider = { networkFix },
            webGeoIpFallback = WebGeoIpFallback { cloudFix },
        )
        assertEquals(passiveFix, provider.current())
    }

    @Test
    fun `chain branch 3 — network wins when sensor and passive null`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { null },
            networkProvider = { networkFix },
            webGeoIpFallback = WebGeoIpFallback { cloudFix },
        )
        assertEquals(networkFix, provider.current())
    }

    /**
     * THE ROMANIA REGRESSION (owner report, 2026-08-16).
     *
     * His sky was captured and correctly refused; the chain then fell through to
     * an IP-geo lookup, and the registration country of his ISP's address block
     * — Romania — was drawn as his position. Nobody spoofs a Ukrainian device to
     * Romania: that marker was never a measurement, it was a WHOIS record.
     *
     * There is no fourth rung now. Silence must stay silence.
     */
    @Test
    fun `all on-device sources silent yields NO position, never an IP guess`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { null },
            networkProvider = { null },
            webGeoIpFallback = WebGeoIpFallback { cloudFix },
        )
        assertNull(
            "«я не знаю, де ти» is the honest answer; an ISP's country is not",
            provider.current(),
        )
    }

    /** The chain must not even ASK the network when it has nothing on device. */
    @Test
    fun `a silent chain performs no network call`() = kotlinx.coroutines.test.runTest {
        var fetches = 0
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { null },
            networkProvider = { null },
            webGeoIpFallback = WebGeoIpFallback {
                fetches++
                cloudFix
            },
        )
        assertNull(provider.current())
        provider.refreshIfStale()
        assertNull(provider.current())
        assertEquals(
            "a jammed operator must not have his IP disclosed to a geo service",
            0,
            fetches,
        )
    }

    /**
     * The type is the control, not the call order: a rung that hands back a
     * GUESSED fix is skipped even though it answered.
     */
    @Test
    fun `a GUESSED fix is refused even from a rung that answered`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { LocationFix(44.43, 26.10, register = FixRegister.GUESSED) },
            passiveProvider = { passiveFix },
            networkProvider = { null },
        )
        assertEquals(
            "a guess must not shadow a real measurement below it",
            passiveFix,
            provider.current(),
        )
    }

    @Test
    fun `a GUESSED fix cannot be reached by forgetting to mark a fix`() {
        assertEquals(FixRegister.REMEMBERED, LocationFix(50.0, 30.0).register)
        assertTrue(LocationFix(50.0, 30.0).locatesTheDevice)
        assertFalse(LocationFix(50.0, 30.0, register = FixRegister.GUESSED).locatesTheDevice)
    }

    @Test
    fun `all branches null returns null cleanly`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { null },
            networkProvider = { null },
            webGeoIpFallback = null,
        )
        assertNull(provider.current())
    }

    /**
     * GHOST used to be the ONLY thing standing between a jammed operator and a
     * third-party IP lookup. It is now irrelevant to that question, which is the
     * improvement: the default posture does the work, so forgetting to enter
     * GHOST no longer discloses anything.
     */
    @Test
    fun `the cloud fallback is dead in every mode, not only GHOST`() {
        listOf(true, false).forEach { ghost ->
            val provider = DefaultLocationContextProvider(
                sensorProvider = { null },
                passiveProvider = { null },
                networkProvider = { null },
                webGeoIpFallback = WebGeoIpFallback { cloudFix },
                isGhostMode = { ghost },
            )
            assertNull("ghost=$ghost must yield no IP position", provider.current())
        }
    }

    @Test
    fun `GHOST gate — on-device sources still resolve normally`() {
        val provider = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { passiveFix },
            networkProvider = { null },
            webGeoIpFallback = WebGeoIpFallback { cloudFix },
            isGhostMode = { true },
        )
        // GHOST blocks cloud, not on-device — operator's privacy
        // doesn't require dropping coords already on the device.
        assertEquals(passiveFix, provider.current())
    }

    @Test
    fun `provider survives a sensor lookup that throws`() {
        // SecurityException from a missing permission on a paranoid
        // OEM must not crash the prompt-enrichment hot path.
        val provider = DefaultLocationContextProvider(
            sensorProvider = { throw SecurityException("ACCESS_FINE_LOCATION not granted") },
            passiveProvider = { passiveFix },
            networkProvider = { null },
            webGeoIpFallback = null,
        )
        assertEquals(passiveFix, provider.current())
    }

    /**
     * The old version fired an ipapi.co request on every app start. That is the
     * disclosure, independent of whether anything read the answer.
     */
    @Test
    fun `refreshIfStale never fetches, in any mode`() = kotlinx.coroutines.test.runTest {
        listOf(true, false).forEach { ghost ->
            var fetches = 0
            val provider = DefaultLocationContextProvider(
                sensorProvider = { null },
                passiveProvider = { null },
                networkProvider = { null },
                webGeoIpFallback = WebGeoIpFallback {
                    fetches++
                    cloudFix
                },
                isGhostMode = { ghost },
            )
            provider.refreshIfStale()
            provider.refreshIfStale()
            assertEquals("ghost=$ghost must issue no request", 0, fetches)
        }
    }

    // — ZERO-FAIL: the grade, and why the default is the whole point ——————
    //
    // The bug this pins: guidance polls current() at 1 Hz and map-matches
    // whatever it gets. When the GNSS chip has not locked yet (a parking garage,
    // a cold start), the fused rung is null and the chain falls through to a
    // last-known fix of unknown age or a half-hour-old IP lookup. Guidance then
    // finds itself kilometres from its own polyline, calls that «off-route», and
    // reroutes the operator away from a route that was correct.
    //
    // The grade is what lets a consumer tell those apart, and it defaults to
    // COARSE so that a fix must PROVE it was judged before anything steers on it.

    @Test
    fun `an unmarked fix is coarse — the type fails closed`() {
        val fix = LocationFix(50.0, 30.0)
        assertEquals(FixGrade.COARSE, fix.grade)
        assertFalse("an unmarked fix must never be steerable", fix.isNavigationGrade)
    }

    @Test
    fun `only the fused rung is navigation-grade`() {
        val fused = LocationFix(50.45, 30.52, grade = FixGrade.NAVIGATION, accuracyM = 8f)
        val provider = DefaultLocationContextProvider(
            sensorProvider = { fused },
            passiveProvider = { passiveFix },
            networkProvider = { networkFix },
        )
        assertTrue(provider.current()!!.isNavigationGrade)
    }

    @Test
    fun `every fallback rung is refused the wheel`() {
        // Branch 2: passive last-known — real coords, unknown age.
        val onPassive = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { passiveFix },
            networkProvider = { networkFix },
        )
        assertFalse(
            "a last-known fix of unknown age must not steer a drive",
            onPassive.current()!!.isNavigationGrade,
        )

        // Branch 3: network last-known.
        val onNetwork = DefaultLocationContextProvider(
            sensorProvider = { null },
            passiveProvider = { null },
            networkProvider = { networkFix },
        )
        assertFalse(onNetwork.current()!!.isNavigationGrade)

        // There is no branch 4. The IP-geo rung used to sit here and was refused
        // the wheel; it is now refused the map as well — see the Romania
        // regression above.
    }

    @Test
    fun `dead reckoning still steers — inertial nav is the engine working, not failing`() {
        // A tunnel must not stop the drive. The fix is dead, its doubt is
        // growing, and it says so — but PHANTOM-EW judged it, so it keeps the
        // wheel. This is the line between «honest about doubt» and «useless».
        val inertial = LocationFix(
            50.45, 30.52,
            accuracyM = 180f,
            grade = FixGrade.NAVIGATION,
            dead = true,
        )
        assertTrue(inertial.isNavigationGrade)
        assertTrue(inertial.dead)
    }
}
