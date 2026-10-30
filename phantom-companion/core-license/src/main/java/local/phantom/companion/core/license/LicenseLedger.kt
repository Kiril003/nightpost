package local.phantom.companion.core.license

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Те, що сервер уже сказав ПІДПИСАНО, і що мусить пережити і перезапуск, і
// втрату самого файлу політики. Живе там само, де сховище ключа.
data class LicenseLedger(
    val policyIssuedAtMs: Long = 0L,
    val policyMinBuild: Int = 0,
    val policyBlocksDevice: Boolean = false,
    val standing: ActivationStatus? = null,
    val standingAtMs: Long = 0L,
    val standingSerial: String? = null,
    val affirmedAtMs: Long = 0L,
    val goneStreak: Int = 0,
) {

    val gone: Boolean get() = standing?.gone == true

    // Сервер досяжний і підписано не визнає цю активацію — це не «немає мережі».
    val denied: Boolean get() = goneStreak >= LicenseModel.GONE_CONFIRMATIONS

    fun goneFor(serial: String?): Boolean =
        gone && (standingSerial == null || serial == null || standingSerial == serial)

    fun record(statement: ActivationStatement, nowMs: Long): LicenseLedger {
        if (statement.issuedAtMs < standingAtMs) return this
        val active = statement.status == ActivationStatus.Active
        return copy(
            standing = statement.status,
            standingAtMs = statement.issuedAtMs,
            standingSerial = statement.serial,
            // Штамп заяви — з годинника сервера, а пільгу рахують за годинником
            // пристрою, тож якір мусить бути з того самого годинника, що й звірка.
            affirmedAtMs = if (active) nowMs else affirmedAtMs,
            goneStreak = if (active) 0 else goneStreak + 1,
        )
    }

    fun record(policy: BuildPolicy, deviceFingerprint: String, licenseId: String?): LicenseLedger {
        if (policy.issuedAtMs < policyIssuedAtMs) return this
        val revoked = licenseId != null && licenseId in policy.revoked
        return copy(
            policyIssuedAtMs = policy.issuedAtMs,
            policyMinBuild = policy.minBuild,
            policyBlocksDevice = policy.blocked.any { it.equals(deviceFingerprint, ignoreCase = true) },
            standing = if (revoked) ActivationStatus.Revoked else standing,
            standingAtMs = if (revoked) maxOf(standingAtMs, policy.issuedAtMs) else standingAtMs,
            // Політика говорить про ліцензію, не про окремий серійник.
            standingSerial = if (revoked) null else standingSerial,
        )
    }

    // Сервер щойно видав сертифікат — відкликаному він би його не видав.
    fun activated(certificate: LicenseCertificate, nowMs: Long): LicenseLedger = copy(
        standing = ActivationStatus.Active,
        standingAtMs = maxOf(standingAtMs, BuildPolicy.parseInstantMs(certificate.issuedAt)),
        standingSerial = certificate.serial,
        affirmedAtMs = nowMs,
        goneStreak = 0,
    )

    fun encode(): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("policy_issued_at", policyIssuedAtMs)
            put("policy_min_build", policyMinBuild)
            put("policy_blocks_device", policyBlocksDevice)
            standing?.let { put("standing", it.wire) }
            put("standing_at", standingAtMs)
            standingSerial?.let { put("standing_serial", it) }
            put("affirmed_at", affirmedAtMs)
            put("gone_streak", goneStreak)
        },
    )

    companion object {

        val EMPTY = LicenseLedger()

        const val STORAGE_KEY = "standing_v1"

        private val json = Json { ignoreUnknownKeys = true }

        fun decode(text: String?): LicenseLedger {
            val obj = text?.takeIf { it.isNotBlank() }?.let(SignedDocument::parse) ?: return EMPTY
            return LicenseLedger(
                policyIssuedAtMs = obj.str("policy_issued_at")?.toLongOrNull() ?: 0L,
                policyMinBuild = obj.str("policy_min_build")?.toIntOrNull() ?: 0,
                policyBlocksDevice = obj.str("policy_blocks_device")?.toBoolean() ?: false,
                standing = ActivationStatus.fromWire(obj.str("standing")),
                standingAtMs = obj.str("standing_at")?.toLongOrNull() ?: 0L,
                standingSerial = obj.str("standing_serial"),
                affirmedAtMs = obj.str("affirmed_at")?.toLongOrNull() ?: 0L,
                goneStreak = obj.str("gone_streak")?.toIntOrNull() ?: 0,
            )
        }
    }
}
