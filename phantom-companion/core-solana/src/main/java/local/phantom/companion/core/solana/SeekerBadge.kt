package local.phantom.companion.core.solana

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import local.phantom.companion.core.license.SignedDocument

/**
 * Знак «Seeker перевірено» для офлайн-купюри. Сервер ліцензій видає його після чесного
 * підпису Sign-In-With-Solana й читання mainnet і підписує тим самим ключем, що й сертифікати
 * ліцензій, — публічна половина вже зашита в застосунок, тож отримувач перевіряє знак
 * без мережі. Знак привʼязаний до особи відправника в месенджері: чужий знак, вклеєний
 * у свою купюру, не пройде.
 *
 * Знак каже одне або обидва: гаманець тримає Seeker Genesis Token ([sgt]) і/або стейкає SKR
 * у програмі Solana Mobile ([skrTier] — рівень, не сума; null — сервер стейк не прочитав).
 * Знак без жодного з двох — не знак.
 */
data class SeekerBadge(
    val wallet: String,
    val identity: String,
    val issuedAt: Long,
    val expires: Long,
    val sgt: Boolean,
    val skrTier: SkrTier?,
    val json: String,
) {

    /** Рівні стейку SKR — ті самі пороги, що й у сервері (seeker.SKR_TIERS). */
    enum class SkrTier(val wire: String) {
        NONE("none"), STAKER("staker"), K1("1k"), K10("10k");

        val stakes: Boolean get() = this != NONE

        companion object {
            fun of(wire: String?): SkrTier? = entries.firstOrNull { it.wire == wire }
        }
    }

    /** Що показати отримувачу: окремі частини, щоб і рідний екран, і веб склали їх своїми словами. */
    enum class Part { SEEKER, SKR_STAKER, SKR_1K, SKR_10K }

    val parts: List<Part>
        get() = listOfNotNull(
            Part.SEEKER.takeIf { sgt },
            when (skrTier) {
                SkrTier.STAKER -> Part.SKR_STAKER
                SkrTier.K1 -> Part.SKR_1K
                SkrTier.K10 -> Part.SKR_10K
                SkrTier.NONE, null -> null
            },
        )

    sealed interface Verdict {
        data class Valid(val badge: SeekerBadge) : Verdict
        /** Підпис не сервера або поле змінено після підпису. */
        data object Forged : Verdict
        data object Expired : Verdict
        /** Знак чесний, але виданий іншій особі, ніж та, що надіслала купюру. */
        data object NotSender : Verdict
        data object Malformed : Verdict
    }

    companion object {
        const val KIND = "seeker_badge"
        /** Годинник телефона відправника міг поспішати; більше — уже не похибка. */
        private const val SKEW_SEC = 600L

        fun parse(json: String): SeekerBadge? {
            val doc = SignedDocument.parse(json) ?: return null
            fun s(k: String) = (doc[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun n(k: String) = (doc[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
            if (n("v") != 1L || s("kind") != KIND) return null
            val sgt = (doc["sgt"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return null
            val wallet = s("wallet") ?: return null
            val identity = s("identity")?.takeIf { it.isNotBlank() } ?: return null
            val issued = n("issued_at") ?: return null
            val expires = n("expires")?.takeIf { it > issued } ?: return null
            // Рівень або є разом із часом перевірки, або обох немає; невідомий рівень — не наш формат.
            val tier = if ("skr_tier" in doc) SkrTier.of(s("skr_tier")) ?: return null else null
            if ((tier != null) != (n("skr_checked_at") != null)) return null
            if (!sgt && tier?.stakes != true) return null
            return SeekerBadge(wallet, identity, issued, expires, sgt, tier, json)
        }

        fun verify(json: String, publicKeyHex: String, senderId: String?, nowSec: Long): Verdict {
            val badge = parse(json) ?: return Verdict.Malformed
            if (publicKeyHex.isBlank() || !SignedDocument.verify(json, publicKeyHex)) return Verdict.Forged
            if (nowSec >= badge.expires || badge.issuedAt > nowSec + SKEW_SEC) return Verdict.Expired
            if (senderId == null || senderId != badge.identity) return Verdict.NotSender
            return Verdict.Valid(badge)
        }

        /** Свій знак ще варто прикладати до купюри? Без мережі й без ключа — лише строк. */
        fun fresh(json: String?, nowSec: Long): Boolean = json?.let(::parse)?.let { nowSec < it.expires } == true
    }
}
