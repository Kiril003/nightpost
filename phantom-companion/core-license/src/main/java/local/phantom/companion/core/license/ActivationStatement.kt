package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonObject

enum class ActivationStatus(val wire: String) {
    Active("active"),
    Deactivated("deactivated"),
    Revoked("revoked"),
    Unknown("unknown");

    // Сервер сказав, що активації більше нема. «unknown» — не сказав нічого.
    val gone: Boolean get() = this == Deactivated || this == Revoked

    companion object {
        fun fromWire(value: String?): ActivationStatus? =
            entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

data class ActivationStatement(
    val licenseId: String,
    val serial: String,
    val deviceFingerprint: String,
    val status: ActivationStatus,
    val issuedAtMs: Long,
) {
    companion object {

        const val KIND = "activation"

        fun verified(
            text: String?,
            publicKeyHex: String?,
            deviceFingerprint: String,
            serial: String?,
        ): ActivationStatement? {
            if (text.isNullOrBlank()) return null
            val document = SignedDocument.parse(text) ?: return null
            return verified(document, publicKeyHex, deviceFingerprint, serial)
        }

        fun verified(
            document: JsonObject,
            publicKeyHex: String?,
            deviceFingerprint: String,
            serial: String?,
        ): ActivationStatement? {
            if (publicKeyHex.isNullOrBlank()) return null
            if (document.str("kind") != KIND) return null
            if (!SignedDocument.verify(document, publicKeyHex)) return null
            val licenseId = document.str("license_id") ?: return null
            val statedSerial = document.str("serial") ?: return null
            val fingerprint = document.str("device_fingerprint") ?: return null
            val status = ActivationStatus.fromWire(document.str("status")) ?: return null
            // Заява мусить стосуватися САМЕ цього пристрою й поточного сертифіката,
            // інакше стару заяву переграли б проти свіжої активації.
            if (!fingerprint.equals(deviceFingerprint, ignoreCase = true)) return null
            if (serial != null && statedSerial != serial) return null
            // Без розбірного штампа заяву нічим упорядкувати, а весь захист від
            // переграваня стоїть саме на порядку.
            val issuedAtMs = BuildPolicy.parseInstantMs(document.str("issued_at"))
            if (issuedAtMs <= 0L) return null
            return ActivationStatement(
                licenseId = licenseId,
                serial = statedSerial,
                deviceFingerprint = fingerprint,
                status = status,
                issuedAtMs = issuedAtMs,
            )
        }
    }
}
