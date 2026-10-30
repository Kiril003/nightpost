package local.phantom.companion.core.license

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Усі вектори нижче вийшли з підписувача сервера (platform-site/server/
// license_crypto.py) ключем із насіння `bytes(range(32))`, тож будь-хто
// відтворює їх однією командою, а не мусить вірити цьому файлу на слово.
class ServerVectorTest {

    private val publicKey = "03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8"
    private val fingerprint = "a".repeat(64)
    private val serial = "1e0dab05-e501-463c-9ab2-fbc04180f2fd"

    private val certificate = """
        {"v":1,"license_id":"2ad2919b-ea18-45e2-9f8f-f9040becbaf1",
         "serial":"$serial","tier":"crew",
         "device_fingerprint":"$fingerprint","updates_until":"2028-08-09",
         "issued_at":"2026-08-12T18:44:40.272232+00:00",
         "sig":"j7ysslw5QiBMlAbavi1/kJxQisJM167WBX4h1GWeRkmV1WaNfSHE26XMhPAKi3zFYyMaSeywFC3gs+GbctjQBw=="}
    """.trimIndent()

    private val policy = """
        {"v":1,"issued_at":"2026-08-12T18:44:40.277560+00:00","min_build":7,
         "free":["core.chat"],"revoked":[],"blocked":[],"notice":"",
         "sig":"6qT31K8rN9YwcJ3udMUoFHySeI6ezWb/Kfk7TehRaTOkj5chP7ZGZsqNPrV6jhb++jsJSp0V5uDHa8lqqtvECA=="}
    """.trimIndent()

    // Той самий підписувач, але політика з оголошеною збіркою — те, що віддає
    // POST /admin/policy/build. Вкладений обʼєкт `latest` тут не декоративний:
    // канонізація перед підписом у Python і в SignedDocument сортує ключі на
    // КОЖНОМУ рівні, і розійтись вони можуть саме на вкладеності.
    private val policyWithBuild = """
        {"blocked":[],"free":["core.chat"],"issued_at":"2026-08-13T09:12:44.118293+00:00",
         "latest":{"build":42,"filename":"phantom-1.4.0.apk","platform":"android",
         "requires":"licence",
         "sha256":"9f2c0f3ac7b8e51d4a6b90c2e7d3f815a0b4c6d8e9f0a1b2c3d4e5f60718293a",
         "size":41234567,"url":"https://phantom-os.dev/account","version":"1.4.0"},
         "min_build":7,"notice":"","revoked":[],
         "sig":"plsEfUbbD6AYhPBIrXPghZHOa9mR0Yi5xBGBhBaF9F0UBREpvBMR3ApE6Uapml3kPlVm6ZnNKkrv0cHn0Cv9Cw==",
         "v":1}
    """.trimIndent()

    private val activeStatement = """
        {"v":1,"kind":"activation","license_id":"2ad2919b-ea18-45e2-9f8f-f9040becbaf1",
         "serial":"$serial","device_fingerprint":"$fingerprint",
         "status":"active","issued_at":"2026-08-12T21:04:11.512874+00:00",
         "sig":"B1DZoWGeEnf1aVgIxhCq2e+1R6FRYMZBpq8pGJ+qj5wRKWfcAsevJosz56XLQGfHXqkzXxGrihDnkQMoCk3DAg=="}
    """.trimIndent()

    private val deactivatedStatement = """
        {"v":1,"kind":"activation","license_id":"2ad2919b-ea18-45e2-9f8f-f9040becbaf1",
         "serial":"$serial","device_fingerprint":"$fingerprint",
         "status":"deactivated","issued_at":"2026-08-12T22:19:07.884301+00:00",
         "sig":"5jdS9QoCnHCQYMjQP0FlY9a6nguGEvs+1ZY6504rB+9Qm8xpa2sGHd7rBxypm8vHmOZ2uzA7RKJacNlPXuBkDg=="}
    """.trimIndent()

    private val unknownStatement = """
        {"v":1,"kind":"activation","license_id":"2ad2919b-ea18-45e2-9f8f-f9040becbaf1",
         "serial":"$serial","device_fingerprint":"$fingerprint",
         "status":"unknown","issued_at":"2026-08-12T23:41:55.203118+00:00",
         "sig":"lr74sxiFb3OeFl+vufg+i5klX1IwjXD+NTzaICh1xMA8pyY4PvPNQWyURsdM+JCjg9TD+DkXp+Ix7K1aYhDYBg=="}
    """.trimIndent()

    private val now = 1_760_000_000_000L

    @Test
    fun `a certificate issued by the real server verifies here`() {
        assertTrue(SignedDocument.verify(certificate, publicKey))
        assertEquals(LicenseTier.Crew, LicenseCertificate.parse(certificate)!!.tier)
    }

    @Test
    fun `a policy published by the real server verifies here`() {
        val parsed = BuildPolicy.verified(policy, publicKey)!!
        assertEquals(7, parsed.minBuild)
        assertEquals(setOf(LicenseFeature.Chat), parsed.free)
    }

    @Test
    fun `the whole gate accepts the real pair`() {
        val verdict = decide()

        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertEquals(LicenseTier.Crew, verdict.tier)
        assertTrue(verdict.has(LicenseFeature.CrewSync))
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
        assertFalse(verdict.reactivateRequired)
    }

    @Test
    fun `one changed character in the real certificate is enough to reject it`() {
        val bumped = certificate.replace("\"crew\"", "\"unit\"")

        assertFalse(SignedDocument.verify(bumped, publicKey))
    }

    @Test
    fun `the statements the real server signs parse into the three states`() {
        val states = listOf(activeStatement, deactivatedStatement, unknownStatement).map {
            ActivationStatement.verified(it, publicKey, fingerprint, serial)!!.status
        }

        assertEquals(
            listOf(ActivationStatus.Active, ActivationStatus.Deactivated, ActivationStatus.Unknown),
            states,
        )
    }

    // Цей вектор — справді звужувальна політика: сервер підписав free:["core.chat"].
    // Тобто це не вигаданий випадок, а те, що вже лежить на боці сервера.
    @Test
    fun `the free list the real server signed is shorter, and still takes nothing`() {
        assertEquals(setOf(LicenseFeature.Chat), BuildPolicy.verified(policy, publicKey)!!.free)

        val verdict = decide()

        assertTrue(verdict.freeFeatures.containsAll(LicenseModel.DEFAULT_FREE))
        assertFalse(verdict.trialOnly(LicenseFeature.Voice))
    }

    @Test
    fun `a real deactivation strips the real certificate but not chat or voice`() {
        val statement = ActivationStatement.verified(deactivatedStatement, publicKey, fingerprint, serial)!!
        val ledger = LicenseLedger.EMPTY.record(statement, now)

        val verdict = decide(ledger = ledger)

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
        assertTrue(verdict.reactivateRequired)
        assertEquals(LicenseModel.DEFAULT_FREE, verdict.features)
    }

    @Test
    fun `the build the real server announces is the build this app would offer`() {
        val parsed = BuildPolicy.verified(policyWithBuild, publicKey)!!
        val offered = UpdateOffer.forThisBuild(
            parsed,
            installedBuild = 41,
            licensed = true,
            developerBuild = false,
        )!!

        assertEquals(42, offered.build)
        assertEquals("phantom-1.4.0.apk", offered.filename)
        assertEquals(41234567L, offered.sizeBytes)
        assertEquals(
            "9f2c0f3ac7b8e51d4a6b90c2e7d3f815a0b4c6d8e9f0a1b2c3d4e5f60718293a",
            offered.sha256,
        )
        assertTrue(offered.requiresLicence)
        // Оголошення нічого не забирає: min_build із того самого документа
        // й далі робить свою окрему справу.
        assertEquals(7, parsed.minBuild)
    }

    @Test
    fun `one changed byte of the real announcement is enough to silence it`() {
        val bumped = policyWithBuild.replace("\"build\":42", "\"build\":99")

        assertNull(BuildPolicy.verified(bumped, publicKey))
    }

    @Test
    fun `a real statement is not accepted as a certificate`() {
        assertNull(LicenseCertificate.parse(activeStatement))
        assertNull(ActivationStatement.verified(certificate, publicKey, fingerprint, serial))
    }

    private fun decide(ledger: LicenseLedger = LicenseLedger.EMPTY) = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = certificate,
            policyJson = policy,
            publicKeyHex = publicKey,
            deviceFingerprint = fingerprint,
            buildNumber = 7,
            nowMs = now,
            lastCheckInMs = now,
            firstRunMs = 1_759_000_000_000L,
            ledger = ledger,
        ),
    )
}
