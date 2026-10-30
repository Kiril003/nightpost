package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class LicenseGateTest {

    private val issuer = Issuer()
    private val stranger = Issuer()
    private val fingerprint = "a".repeat(64)
    private val day = LicenseModel.DAY_MS
    private val now = 1_760_000_000_000L

    @Test
    fun `canonical bytes match the python signer byte for byte`() {
        val source = """
            {"v":1,"license_id":"abc","tier":"crew","device_fingerprint":"ff",
             "updates_until":"2027-01-01","issued_at":"2026-08-10T10:00:00+00:00",
             "serial":"s-1","note":"Привіт \"світ\"\n\ttab","sig":"dropped"}
        """.trimIndent()
        val note = "\\u041f\\u0440\\u0438\\u0432\\u0456\\u0442 \\\"\\u0441\\u0432\\u0456\\u0442\\\"\\n\\ttab"
        val expected = """{"device_fingerprint":"ff","issued_at":"2026-08-10T10:00:00+00:00",""" +
            """"license_id":"abc","note":"$note","serial":"s-1",""" +
            """"tier":"crew","updates_until":"2027-01-01","v":1}"""

        val actual = String(SignedDocument.canonicalBytes(SignedDocument.parse(source)!!))

        assertEquals(expected, actual)
    }

    @Test
    fun `a real certificate lifts the tier`() {
        val verdict = decide(cert = issuer.certificate(tier = "crew"))

        assertEquals(LicenseTier.Crew, verdict.tier)
        assertEquals(LicenseReason.Licensed, verdict.reason)
        assertTrue(verdict.has(LicenseFeature.CrewSync))
        assertEquals(LicenseModel.OFFLINE_GRACE_DAYS, verdict.graceDaysLeft)
    }

    @Test
    fun `a tampered certificate falls to free, never to nothing`() {
        val forged = issuer.certificate(tier = "personal").replace("\"personal\"", "\"unit\"")

        val verdict = decide(cert = forged)

        assertEquals(LicenseReason.BadSignature, verdict.reason)
        assertEquals(LicenseTier.Free, verdict.tier)
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
    }

    @Test
    fun `a certificate issued for another phone does not travel`() {
        val other = issuer.certificate(fingerprint = "b".repeat(64))

        assertEquals(LicenseReason.ForeignDevice, decide(cert = other).reason)
    }

    @Test
    fun `revoking the licence downgrades the copy already installed`() {
        val cert = issuer.certificate(licenseId = "lic-7")
        val policy = issuer.policy(revoked = listOf("lic-7"))

        val verdict = decide(cert = cert, policy = policy)

        assertEquals(LicenseReason.Revoked, verdict.reason)
        assertTrue(verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
    }

    @Test
    fun `blocking one device needs no certificate at all`() {
        val policy = issuer.policy(blocked = listOf(fingerprint))

        assertEquals(LicenseReason.Blocked, decide(cert = issuer.certificate(), policy = policy).reason)
    }

    @Test
    fun `retiring old builds strips paid features from them`() {
        val policy = issuer.policy(minBuild = 42)

        val verdict = decide(cert = issuer.certificate(), policy = policy, build = 41)

        assertEquals(LicenseReason.BuildRetired, verdict.reason)
        assertEquals(LicenseReason.Licensed, decide(cert = issuer.certificate(), policy = policy, build = 42).reason)
    }

    @Test
    fun `a month of silence ends the paid tier and one check-in restores it`() {
        val silent = now - (LicenseModel.OFFLINE_GRACE_DAYS + 1) * day

        assertEquals(LicenseReason.GraceExpired, decide(cert = issuer.certificate(), lastCheckIn = silent).reason)
        assertEquals(LicenseReason.Licensed, decide(cert = issuer.certificate(), lastCheckIn = now).reason)
    }

    @Test
    fun `a signed policy naming a shorter free list takes nothing away`() {
        val policy = issuer.policy(free = listOf("core.chat"))

        val verdict = decide(cert = null, firstRun = 0L, policy = policy)

        assertEquals(LicenseTier.Free, verdict.tier)
        assertEquals(LicenseModel.DEFAULT_FREE, verdict.features)
        assertTrue(verdict.has(LicenseFeature.Voice))
    }

    @Test
    fun `the server keeps its full power to widen the free set`() {
        val policy = issuer.policy(free = listOf("map.terrain3d", "voice.premium"))

        val verdict = decide(cert = null, firstRun = 0L, policy = policy)

        assertTrue(verdict.has(LicenseFeature.MapTerrain3d))
        assertTrue(verdict.has(LicenseFeature.VoicePremium))
        assertTrue(verdict.features.containsAll(LicenseModel.DEFAULT_FREE))
    }

    // Мапа виходів, а не список випадків: звірка з LicenseReason.entries валить
    // цю перевірку на новому виході з воріт, поки хтось не скаже, що там із
    // чатом і голосом.
    @Test
    fun `no signed policy can take chat or voice off this phone, on any path out`() {
        val silent = now - (LicenseModel.OFFLINE_GRACE_DAYS + 1) * day
        val verdicts = mapOf(
            LicenseReason.Licensed to decide(cert = issuer.certificate(), policy = hostile()),
            LicenseReason.Trial to decide(cert = null, firstRun = now - 2 * day, policy = hostile()),
            LicenseReason.NoCertificate to decide(cert = null, firstRun = 0L, policy = hostile()),
            LicenseReason.BadSignature to decide(
                cert = issuer.certificate(tier = "personal").replace("\"personal\"", "\"unit\""),
                policy = hostile(),
            ),
            LicenseReason.ForeignDevice to decide(
                cert = issuer.certificate(fingerprint = "b".repeat(64)),
                policy = hostile(),
            ),
            LicenseReason.Revoked to decide(
                cert = issuer.certificate(licenseId = "lic-7"),
                policy = hostile(revoked = listOf("lic-7")),
            ),
            LicenseReason.Blocked to decide(
                cert = issuer.certificate(),
                policy = hostile(blocked = listOf(fingerprint)),
            ),
            LicenseReason.BuildRetired to decide(
                cert = issuer.certificate(),
                policy = hostile(minBuild = 42),
                build = 41,
            ),
            LicenseReason.GraceExpired to decide(
                cert = issuer.certificate(),
                policy = hostile(),
                lastCheckIn = silent,
            ),
        )

        assertEquals(
            "зʼявився вихід із воріт, якого ця перевірка не бачить",
            LicenseReason.entries.toSet(),
            verdicts.keys,
        )
        verdicts.forEach { (expected, verdict) ->
            assertEquals(expected, verdict.reason)
            listOf(LicenseFeature.Chat, LicenseFeature.Voice).forEach { feature ->
                assertTrue("$expected забрав ${feature.key}", verdict.has(feature))
                assertTrue(
                    "$expected лишив ${feature.key} робочим, але не вільним",
                    feature in verdict.freeFeatures,
                )
                assertFalse(
                    "$expected обіцяє забрати ${feature.key}, коли скінчиться проба",
                    verdict.trialOnly(feature),
                )
            }
        }
    }

    @Test
    fun `a policy signed by a stranger changes nothing`() {
        val hostile = stranger.policy(free = emptyList(), blocked = listOf(fingerprint))

        val verdict = decide(cert = null, firstRun = 0L, policy = hostile)

        assertEquals(LicenseReason.NoCertificate, verdict.reason)
        assertEquals(LicenseModel.DEFAULT_FREE, verdict.features)
    }

    @Test
    fun `a fresh install runs on trial and it expires`() {
        assertEquals(LicenseReason.Trial, decide(cert = null, firstRun = now - 2 * day).reason)
        assertEquals(
            LicenseReason.NoCertificate,
            decide(cert = null, firstRun = now - (LicenseModel.TRIAL_DAYS + 1) * day).reason,
        )
    }

    @Test
    fun `every refusal keeps the safety floor`() {
        val outcomes = listOf(
            decide(cert = "not json at all", firstRun = 0L),
            decide(cert = issuer.certificate().replace("\"", "'")),
            decide(cert = stranger.certificate()),
            decide(cert = null, firstRun = 0L),
        )

        outcomes.forEach { verdict ->
            assertTrue(verdict.reason.name, verdict.features.containsAll(LicenseModel.SAFETY_FLOOR))
        }
    }

    // Найгірше, що сервер узагалі може підписати: вільного рівня немає зовсім.
    private fun hostile(
        minBuild: Int = 0,
        revoked: List<String> = emptyList(),
        blocked: List<String> = emptyList(),
    ) = issuer.policy(minBuild = minBuild, free = emptyList(), revoked = revoked, blocked = blocked)

    private fun decide(
        cert: String?,
        policy: String? = null,
        build: Int = 100,
        lastCheckIn: Long = now,
        firstRun: Long = now - day,
    ) = LicenseGate.decide(
        LicenseGate.Input(
            certificateJson = cert,
            policyJson = policy,
            publicKeyHex = issuer.publicHex,
            deviceFingerprint = fingerprint,
            buildNumber = build,
            nowMs = now,
            lastCheckInMs = lastCheckIn,
            firstRunMs = firstRun,
        ),
    )

    private inner class Issuer {
        private val priv = Ed25519PrivateKeyParameters(SecureRandom())
        val publicHex: String = priv.generatePublicKey().encoded
            .joinToString("") { "%02x".format(it) }

        fun certificate(
            tier: String = "personal",
            licenseId: String = "lic-1",
            fingerprint: String = this@LicenseGateTest.fingerprint,
        ) = sign(
            """{"v":1,"license_id":"$licenseId","serial":"s-1","tier":"$tier",""" +
                """"device_fingerprint":"$fingerprint","updates_until":"2027-08-10",""" +
                """"issued_at":"2026-08-10T10:00:00+00:00"}""",
        )

        fun policy(
            minBuild: Int = 0,
            free: List<String> = LicenseModel.DEFAULT_FREE.map { it.key },
            revoked: List<String> = emptyList(),
            blocked: List<String> = emptyList(),
        ) = sign(
            """{"v":1,"issued_at":"2026-08-10T10:00:00+00:00","min_build":$minBuild,""" +
                """"free":${free.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                """"revoked":${revoked.joinToString(",", "[", "]") { "\"$it\"" }},""" +
                """"blocked":${blocked.joinToString(",", "[", "]") { "\"$it\"" }}}""",
        )

        private fun sign(body: String): String {
            val obj: JsonObject = SignedDocument.parse(body)!!
            val payload = SignedDocument.canonicalBytes(obj)
            val signer = Ed25519Signer().apply { init(true, priv) }
            signer.update(payload, 0, payload.size)
            val sig = java.util.Base64.getEncoder().encodeToString(signer.generateSignature())
            return body.dropLast(1) + ""","sig":"$sig"}"""
        }
    }
}
