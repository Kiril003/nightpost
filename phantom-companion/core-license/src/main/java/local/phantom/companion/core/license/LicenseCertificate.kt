package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

data class LicenseCertificate(
    val version: Int,
    val licenseId: String,
    val serial: String,
    val tier: LicenseTier,
    val deviceFingerprint: String,
    val updatesUntil: String,
    val issuedAt: String,
) {
    companion object {

        fun parse(text: String): LicenseCertificate? {
            val obj = SignedDocument.parse(text) ?: return null
            return from(obj)
        }

        fun from(obj: JsonObject): LicenseCertificate? {
            // Заява про активацію підписана тим самим ключем і має ті самі поля,
            // тож без цього рядка її можна підсунути замість сертифіката.
            if (obj.str("kind") == ActivationStatement.KIND) return null
            val licenseId = obj.str("license_id") ?: return null
            val serial = obj.str("serial") ?: return null
            val fingerprint = obj.str("device_fingerprint") ?: return null
            return LicenseCertificate(
                version = obj.str("v")?.toIntOrNull() ?: 1,
                licenseId = licenseId,
                serial = serial,
                tier = LicenseTier.fromWire(obj.str("tier")),
                deviceFingerprint = fingerprint,
                updatesUntil = obj.str("updates_until").orEmpty(),
                issuedAt = obj.str("issued_at").orEmpty(),
            )
        }
    }
}

internal fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.content }
