package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSilenceTest {

    private val issuer = TestSigner()
    private val fingerprint = TestSigner.FINGERPRINT
    private val day = LicenseModel.DAY_MS
    private val now = 1_760_000_000_000L
    private val grace = LicenseModel.OFFLINE_GRACE_DAYS

    private fun answered(status: String, at: String) = ActivationStatement.verified(
        issuer.statement(status, issuedAt = at),
        issuer.publicHex,
        fingerprint,
        "s-1",
    )!!

    private fun ledger(affirmedDaysAgo: Int, unknownRounds: Int): LicenseLedger {
        var out = LicenseLedger.EMPTY.record(
            answered("active", "2026-08-01T10:00:00+00:00"),
            now - affirmedDaysAgo * day,
        )
        repeat(unknownRounds) { round ->
            out = out.record(answered("unknown", "2026-08-%02dT10:00:00+00:00".format(2 + round)), now)
        }
        return out
    }

    @Test
    fun `a phone that simply cannot reach the server keeps the full grace`() {
        val inside = decide(lastCheckIn = now - grace * day)
        val outside = decide(lastCheckIn = now - (grace + 1) * day)

        assertEquals(LicenseReason.Licensed, inside.reason)
        assertEquals(0, inside.graceDaysLeft)
        assertFalse(inside.reactivateRequired)
        assertEquals(LicenseReason.GraceExpired, outside.reason)
        assertFalse("мовчання мережі — не привід просити активацію", outside.reactivateRequired)
        assertTrue(outside.features.containsAll(LicenseModel.SAFETY_FLOOR))
    }

    @Test
    fun `three signed rounds of no such activation ask for re-activation`() {
        val denied = LicenseLedger.decode(ledger(affirmedDaysAgo = grace + 10, unknownRounds = 3).encode())

        val verdict = decide(lastCheckIn = now, ledger = denied)

        assertEquals(LicenseReason.GraceExpired, verdict.reason)
        assertTrue(verdict.reactivateRequired)
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
    }

    @Test
    fun `a denied device that is still inside its grace works and is asked to re-activate`() {
        val denied = ledger(affirmedDaysAgo = 5, unknownRounds = 3)

        val verdict = decide(lastCheckIn = now, ledger = denied)

        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertEquals(grace - 5, verdict.graceDaysLeft)
        assertTrue(verdict.reactivateRequired)
    }

    @Test
    fun `one strange answer is not a refusal`() {
        val once = ledger(affirmedDaysAgo = grace + 10, unknownRounds = 1)

        val verdict = decide(lastCheckIn = now, ledger = once)

        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertEquals(grace, verdict.graceDaysLeft)
        assertFalse(verdict.reactivateRequired)
    }

    @Test
    fun `a signed active answer resets the count`() {
        val denied = ledger(affirmedDaysAgo = grace + 10, unknownRounds = 3)

        val recovered = denied.record(answered("active", "2026-08-20T10:00:00+00:00"), now)

        assertEquals(0, recovered.goneStreak)
        assertFalse(recovered.denied)
        assertEquals(LicenseReason.Licensed, decide(lastCheckIn = now, ledger = recovered).reason)
    }

    @Test
    fun `an unreachable server never touches the count`() {
        val quiet = ledger(affirmedDaysAgo = 1, unknownRounds = 0)

        assertEquals(0, quiet.goneStreak)
        assertFalse(quiet.denied)
    }

    private fun decide(
        lastCheckIn: Long,
        ledger: LicenseLedger = LicenseLedger.EMPTY,
    ) = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = issuer.certificate(),
            policyJson = null,
            publicKeyHex = issuer.publicHex,
            deviceFingerprint = fingerprint,
            buildNumber = 100,
            nowMs = now,
            lastCheckInMs = lastCheckIn,
            firstRunMs = now - 2 * day,
            ledger = ledger,
        ),
    )
}
