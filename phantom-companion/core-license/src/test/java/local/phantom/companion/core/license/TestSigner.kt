package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonObject
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

class TestSigner {

    private val priv = Ed25519PrivateKeyParameters(SecureRandom())

    val publicHex: String = priv.generatePublicKey().encoded
        .joinToString("") { "%02x".format(it) }

    fun certificate(
        tier: String = "personal",
        licenseId: String = "lic-1",
        serial: String = "s-1",
        fingerprint: String = FINGERPRINT,
        issuedAt: String = "2026-08-10T10:00:00+00:00",
    ) = sign(
        """{"v":1,"license_id":"$licenseId","serial":"$serial","tier":"$tier",""" +
            """"device_fingerprint":"$fingerprint","updates_until":"2027-08-10",""" +
            """"issued_at":"$issuedAt"}""",
    )

    fun policy(
        issuedAt: String = "2026-08-10T10:00:00+00:00",
        minBuild: Int = 0,
        free: List<String> = LicenseModel.DEFAULT_FREE.map { it.key },
        revoked: List<String> = emptyList(),
        blocked: List<String> = emptyList(),
        notice: String? = null,
        /** Готовий блок `latest` як його підписав би сервер, або null. */
        latest: String? = null,
    ) = sign(
        """{"v":1,"issued_at":"$issuedAt","min_build":$minBuild,""" +
            """"free":${free.quoted()},"revoked":${revoked.quoted()},""" +
            """"blocked":${blocked.quoted()},"notice":"${notice.orEmpty()}"""" +
            (latest?.let { ""","latest":$it""" } ?: "") + "}",
    )

    /** Повний, правильно описаний випуск — те, що віддає /admin/policy/build. */
    fun latest(
        platform: String = "android",
        build: Int = 42,
        version: String = "1.4.0",
        filename: String = "phantom-1.4.0.apk",
        url: String = "https://phantom-os.dev/account",
        size: Long = 41_234_567L,
        sha256: String = "b".repeat(64),
        requires: String = "licence",
    ) = """{"platform":"$platform","build":$build,"version":"$version",""" +
        """"filename":"$filename","url":"$url","size":$size,""" +
        """"sha256":"$sha256","requires":"$requires"}"""

    fun statement(
        status: String,
        licenseId: String = "lic-1",
        serial: String = "s-1",
        fingerprint: String = FINGERPRINT,
        issuedAt: String = "2026-08-12T10:00:00+00:00",
        kind: String = ActivationStatement.KIND,
    ) = sign(
        """{"v":1,"kind":"$kind","license_id":"$licenseId","serial":"$serial",""" +
            """"device_fingerprint":"$fingerprint","status":"$status","issued_at":"$issuedAt"}""",
    )

    private fun sign(body: String): String {
        val obj: JsonObject = SignedDocument.parse(body)!!
        val payload = SignedDocument.canonicalBytes(obj)
        val signer = Ed25519Signer().apply { init(true, priv) }
        signer.update(payload, 0, payload.size)
        val sig = java.util.Base64.getEncoder().encodeToString(signer.generateSignature())
        return body.dropLast(1) + ""","sig":"$sig"}"""
    }

    private fun List<String>.quoted() = joinToString(",", "[", "]") { "\"$it\"" }

    companion object {
        const val FINGERPRINT = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
