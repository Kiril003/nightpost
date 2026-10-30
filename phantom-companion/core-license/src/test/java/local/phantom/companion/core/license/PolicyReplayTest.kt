package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PolicyReplayTest {

    private val issuer = TestSigner()
    private val fingerprint = TestSigner.FINGERPRINT
    private val now = 1_760_000_000_000L

    private val stale = issuer.policy(
        issuedAt = "2026-01-01T00:00:00+00:00",
        minBuild = 0,
        notice = "переграно",
    )

    private val current = issuer.policy(
        issuedAt = "2026-08-10T10:00:00+00:00",
        minBuild = 42,
    )

    private fun ledgerAfter(policyJson: String, licenseId: String? = null): LicenseLedger =
        LicenseLedger.EMPTY.record(
            BuildPolicy.verified(policyJson, issuer.publicHex)!!,
            fingerprint,
            licenseId,
        )

    @Test
    fun `a policy older than the highest we ever saw is refused`() {
        val ledger = ledgerAfter(current)

        val verdict = decide(policy = stale, build = 41, ledger = ledger)

        assertEquals(LicenseReason.BuildRetired, verdict.reason)
        assertNull("переграна політика не має говорити з людиною", verdict.notice)
    }

    @Test
    fun `the refusal survives a reinstall that kept only the ledger`() {
        val survived = LicenseLedger.decode(ledgerAfter(current).encode())

        assertEquals(LicenseReason.BuildRetired, decide(policy = stale, build = 41, ledger = survived).reason)
        assertEquals(LicenseReason.BuildRetired, decide(policy = null, build = 41, ledger = survived).reason)
        assertEquals(LicenseReason.Licensed, decide(policy = null, build = 42, ledger = survived).reason)
    }

    @Test
    fun `a name blocked once stays blocked when the file is rolled back`() {
        val ledger = LicenseLedger.decode(
            ledgerAfter(issuer.policy(issuedAt = "2026-08-10T10:00:00+00:00", blocked = listOf(fingerprint)))
                .encode(),
        )

        val verdict = decide(policy = stale, ledger = ledger)

        assertEquals(LicenseReason.Blocked, verdict.reason)
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
    }

    @Test
    fun `a genuinely new device accepts the first policy it ever sees`() {
        val verdict = decide(policy = stale, build = 41, ledger = LicenseLedger.EMPTY)

        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertEquals("переграно", verdict.notice)
    }

    @Test
    fun `a newer policy may lift a retirement published by mistake`() {
        val ledger = ledgerAfter(current)
        val fixed = issuer.policy(issuedAt = "2026-08-11T10:00:00+00:00", minBuild = 0)

        assertEquals(LicenseReason.Licensed, decide(policy = fixed, build = 41, ledger = ledger).reason)
    }

    @Test
    fun `a revocation republished with a bumped stamp still gets through the pin`() {
        val ledger = ledgerAfter(current)
        val republished = issuer.policy(
            issuedAt = "2026-08-11T10:00:00+00:00",
            minBuild = 42,
            revoked = listOf("lic-1"),
        )

        val verdict = decide(policy = republished, build = 42, ledger = ledger)

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
        assertTrue(
            "відкликання мусить лишитися й після того, як сертифікат зітруть",
            ledger.record(BuildPolicy.verified(republished, issuer.publicHex)!!, fingerprint, "lic-1").gone,
        )
    }

    @Test
    fun `moving the phone clock does not lock out the current policy`() {
        val ledger = ledgerAfter(current)
        val year = 365 * LicenseModel.DAY_MS

        listOf(now - year, now + year).forEach { skewed ->
            val verdict = decide(policy = current, build = 42, ledger = ledger, nowMs = skewed, lastCheckIn = skewed)
            assertEquals("годинник зсунуто на $skewed", LicenseReason.Licensed, verdict.reason)
        }
    }

    @Test
    fun `the pin only ever climbs`() {
        val ledger = ledgerAfter(current)
        val older = BuildPolicy.verified(stale, issuer.publicHex)!!

        assertEquals(ledger, ledger.record(older, fingerprint, null))
    }

    @Test
    fun `the ledger survives its own round trip`() {
        val ledger = ledgerAfter(current, licenseId = "lic-1")
            .record(ActivationStatement.verified(issuer.statement("deactivated"), issuer.publicHex, fingerprint, "s-1")!!, now)

        assertEquals(ledger, LicenseLedger.decode(ledger.encode()))
        assertEquals(LicenseLedger.EMPTY, LicenseLedger.decode(null))
        assertEquals(LicenseLedger.EMPTY, LicenseLedger.decode("not json at all"))
    }

    private fun decide(
        policy: String?,
        build: Int = 100,
        ledger: LicenseLedger = LicenseLedger.EMPTY,
        nowMs: Long = now,
        lastCheckIn: Long = now,
    ) = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = issuer.certificate(),
            policyJson = policy,
            publicKeyHex = issuer.publicHex,
            deviceFingerprint = fingerprint,
            buildNumber = build,
            nowMs = nowMs,
            lastCheckInMs = lastCheckIn,
            firstRunMs = nowMs - LicenseModel.DAY_MS,
            ledger = ledger,
        ),
    )
}
