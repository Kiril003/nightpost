package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivationStandingTest {

    private val issuer = TestSigner()
    private val stranger = TestSigner()
    private val fingerprint = TestSigner.FINGERPRINT
    private val day = LicenseModel.DAY_MS
    private val now = 1_760_000_000_000L

    private fun heard(status: String, issuedAt: String = "2026-08-12T10:00:00+00:00", serial: String = "s-1") =
        LicenseLedger.EMPTY.record(
            ActivationStatement.verified(
                issuer.statement(status, serial = serial, issuedAt = issuedAt),
                issuer.publicHex,
                fingerprint,
                serial,
            )!!,
            now,
        )

    @Test
    fun `a signed deactivation lands on free and never on trial`() {
        val fresh = decide(cert = null, ledger = LicenseLedger.EMPTY)
        assertEquals("свіжий пристрій усе ще дістає пробу", LicenseReason.Trial, fresh.reason)

        // Перевстановлення стирає сертифікат, але не мітку: саме так дірка й
        // відкривалася назад.
        val reinstalled = LicenseLedger.decode(heard("deactivated").encode())
        val verdict = decide(cert = null, ledger = reinstalled)

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
        assertNull(verdict.trialDaysLeft)
        assertTrue(verdict.reactivateRequired)
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
        assertFalse(verdict.has(LicenseFeature.CrewSync))
    }

    @Test
    fun `a signed revocation is not a trial either`() {
        val verdict = decide(cert = null, ledger = heard("revoked"))

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
        assertNull(verdict.trialDaysLeft)
        assertFalse("переактивація відкликаної ліцензії нікому не поможе", verdict.reactivateRequired)
    }

    @Test
    fun `a signed deactivation outranks a certificate still on disk`() {
        val verdict = decide(cert = issuer.certificate(), ledger = heard("deactivated"))

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
    }

    @Test
    fun `a revocation carried by the policy keeps biting after the certificate is gone`() {
        val policy = issuer.policy(revoked = listOf("lic-1"))
        val ledger = LicenseLedger.decode(
            LicenseLedger.EMPTY
                .record(BuildPolicy.verified(policy, issuer.publicHex)!!, fingerprint, "lic-1")
                .encode(),
        )

        assertEquals(LicenseReason.Revoked, decide(cert = null, ledger = ledger).reason)
    }

    @Test
    fun `a deactivation meant for another device or another certificate is not ours`() {
        val foreign = issuer.statement("deactivated", fingerprint = "b".repeat(64))
        val otherSerial = issuer.statement("deactivated", serial = "s-9")

        assertNull(ActivationStatement.verified(foreign, issuer.publicHex, fingerprint, "s-1"))
        assertNull(ActivationStatement.verified(otherSerial, issuer.publicHex, fingerprint, "s-1"))
        assertNull(
            ActivationStatement.verified(
                stranger.statement("deactivated"), issuer.publicHex, fingerprint, "s-1",
            ),
        )
        assertNull(
            ActivationStatement.verified(
                issuer.statement("deactivated", kind = "certificate"), issuer.publicHex, fingerprint, "s-1",
            ),
        )
    }

    @Test
    fun `a stale active statement cannot overwrite a fresh deactivation`() {
        val gone = heard("deactivated", issuedAt = "2026-08-12T10:00:00+00:00")
        val old = ActivationStatement.verified(
            issuer.statement("active", issuedAt = "2026-08-01T10:00:00+00:00"),
            issuer.publicHex,
            fingerprint,
            "s-1",
        )!!

        val after = gone.record(old, now)

        assertEquals(gone, after)
        assertEquals(LicenseReason.Revoked, decide(cert = null, ledger = after).reason)
    }

    @Test
    fun `the marker only clears where the server itself clears it`() {
        val gone = heard("deactivated")
        val statement = ActivationStatement.verified(
            issuer.statement("active", serial = "s-2", issuedAt = "2026-08-13T10:00:00+00:00"),
            issuer.publicHex,
            fingerprint,
            "s-2",
        )!!

        val byStatement = gone.record(statement, now)
        val byActivation = gone.activated(
            LicenseCertificate.parse(issuer.certificate(serial = "s-2"))!!,
            now,
        )

        assertEquals(LicenseReason.Licensed, decide(cert = issuer.certificate(serial = "s-2"), ledger = byStatement).reason)
        assertEquals(LicenseReason.Licensed, decide(cert = issuer.certificate(serial = "s-2"), ledger = byActivation).reason)
        assertEquals(LicenseReason.Trial, decide(cert = null, ledger = byActivation).reason)
    }

    @Test
    fun `a certificate that is really a statement is not a certificate`() {
        val disguised = issuer.statement("active")

        assertNull(LicenseCertificate.parse(disguised))
        assertEquals(LicenseReason.BadSignature, decide(cert = disguised, ledger = LicenseLedger.EMPTY).reason)
    }

    private fun decide(
        cert: String?,
        ledger: LicenseLedger,
        build: Int = 100,
    ) = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = cert,
            policyJson = null,
            publicKeyHex = issuer.publicHex,
            deviceFingerprint = fingerprint,
            buildNumber = build,
            nowMs = now,
            lastCheckInMs = now,
            firstRunMs = now - 2 * day,
            ledger = ledger,
        ),
    )
}
