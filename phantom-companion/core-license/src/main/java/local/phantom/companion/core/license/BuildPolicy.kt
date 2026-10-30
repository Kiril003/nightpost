package local.phantom.companion.core.license

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

data class BuildPolicy(
    val version: Int,
    val issuedAtMs: Long,
    val minBuild: Int,
    val free: Set<LicenseFeature>,
    val revoked: Set<String>,
    val blocked: Set<String>,
    val notice: String?,
    /** Найсвіжіша викладена збірка, або null — поки жодної не оголошено. */
    val latest: AvailableBuild? = null,
) {
    companion object {

        val DEFAULT = BuildPolicy(
            version = 1,
            issuedAtMs = 0L,
            minBuild = 0,
            free = LicenseModel.DEFAULT_FREE,
            revoked = emptySet(),
            blocked = emptySet(),
            notice = null,
        )

        fun verified(text: String?, publicKeyHex: String?): BuildPolicy? {
            if (text.isNullOrBlank() || publicKeyHex.isNullOrBlank()) return null
            val obj = SignedDocument.parse(text) ?: return null
            if (!SignedDocument.verify(obj, publicKeyHex)) return null
            val free = (obj["free"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.let(LicenseFeature::fromKey) }
                ?.toSet()
                ?: LicenseModel.DEFAULT_FREE
            return BuildPolicy(
                version = obj.str("v")?.toIntOrNull() ?: 1,
                issuedAtMs = parseInstantMs(obj.str("issued_at")),
                minBuild = obj.str("min_build")?.toIntOrNull() ?: 0,
                free = free,
                revoked = obj.strings("revoked"),
                blocked = obj.strings("blocked"),
                notice = obj.str("notice")?.takeIf { it.isNotBlank() },
                latest = AvailableBuild.from(obj["latest"] as? kotlinx.serialization.json.JsonObject),
            )
        }

        private fun kotlinx.serialization.json.JsonObject.strings(key: String): Set<String> =
            (this[key] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.takeIf(String::isNotBlank) }
                ?.toSet()
                .orEmpty()

        internal fun parseInstantMs(text: String?): Long {
            if (text.isNullOrBlank()) return 0L
            return runCatching { java.time.Instant.parse(text).toEpochMilli() }
                .recoverCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
                .getOrDefault(0L)
        }
    }
}
