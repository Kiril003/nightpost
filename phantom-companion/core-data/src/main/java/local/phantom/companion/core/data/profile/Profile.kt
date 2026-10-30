package local.phantom.companion.core.data.profile

import kotlinx.serialization.Serializable

/**
 * Trust role assigned to the operator behind a [Profile]. Mirrors the
 * server-side `UserRole` (`src/shared/types/user.ts`) so a single
 * profile maps cleanly onto a PHANTOM OS account when paired.
 *
 *   • [ROOT]     — full ownership; required for paired-host promotion,
 *                  reveal-from-vault, ROOT-gate co-sign on actions.
 *   • [OPERATOR] — trusted day-to-day user; can drive most surfaces but
 *                  not approve high-risk agent actions on the desktop.
 *   • [GUEST]    — sandboxed; sees only its own GHOST records, never
 *                  another profile's vault.
 */
enum class Role { ROOT, OPERATOR, GUEST }

/**
 * How often the AI Familiar is allowed to manifest in this profile's
 * UI. Mirrors the closed enum in `src/shared/types/familiar.ts` so the
 * desktop and the phone do not drift on rarity semantics.
 *
 *   • [OFF]    — never appears (test-summon button still works).
 *   • [RARE]   — 1-in-8 attempts pass.
 *   • [NORMAL] — 1-in-3 attempts pass (default for fresh profiles).
 *   • [OFTEN]  — 1-in-1.5 attempts pass.
 */
enum class FamiliarRarity {
    OFF, RARE, NORMAL, OFTEN;

    companion object {
        /** Default rarity assigned to a freshly-created profile. */
        val Default: FamiliarRarity get() = NORMAL
    }
}

/**
 * Channels a paired PHANTOM OS host advertises. The phone subscribes
 * only to capabilities the host actually serves so a trimmed-down
 * PC (e.g. one without map / wardriving) does not flood logs with
 * channel-not-found errors.
 *
 * The names mirror `WSClient.channel` strings on the desktop so
 * forward-compat is just adding a new entry here when the desktop
 * ships a new channel.
 */
enum class HostCapability {
    STATE,
    SENSOR,
    AGENT,
    COMMS,
    MAP,
    VOICE,
    PAIR,
    FAMILIAR,
    AGENT_ACTING,
    MAP_ACTING,
}

/**
 * One paired PHANTOM OS instance. A [Profile] may carry several of
 * these — home PC, lab PC, a friend's PC — each with its own
 * device JWT + Ed25519 long-term key. The active phone subscribes to
 * the host marked [isPrimary] first, falls back to the others by
 * descending [lastSeenAtEpochMs].
 *
 * Persistence note: the Ed25519 private key is stored as
 * [deviceEd25519PrivBase64] inside the SQLCipher-encrypted DB row.
 * That is by design — the row itself is encrypted at rest, the key
 * never leaves the phone, and rotating it on revoke is a single
 * row-update.
 */
@Serializable
data class PairedHost(
    val hostId: String,
    val lanUrl: String,
    val relayUrl: String? = null,
    val deviceJwt: String,
    val deviceEd25519PrivBase64: String,
    val lastSeenAtEpochMs: Long,
    val isPrimary: Boolean,
    val capabilities: Set<HostCapability>,
) {
    val baseUrl: String get() = lanUrl

    init {
        require(hostId.isNotBlank()) { "PairedHost.hostId must not be blank" }
        require(lanUrl.isNotBlank()) { "PairedHost.lanUrl must not be blank" }
        require(deviceJwt.isNotBlank()) { "PairedHost.deviceJwt must not be blank" }
        require(deviceEd25519PrivBase64.isNotBlank()) {
            "PairedHost.deviceEd25519PrivBase64 must not be blank"
        }
        require(lastSeenAtEpochMs >= 0L) {
            "PairedHost.lastSeenAtEpochMs must not be negative"
        }
    }
}

/**
 * The on-device identity for one operator. Stored encrypted-at-rest
 * via Room + SQLCipher; never crosses the wire — only the matching
 * server-side `User` row does, and only the public-safe subset of
 * fields (display name + avatar + role) is mirrored.
 *
 * Field correspondence with `docs/MOBILE_PHANTOM_AMBITIOUS.md` §4:
 *
 *   • [createdAtEpochMs]            ↔ doc `createdAt: Instant`
 *     Stored as epoch millis to keep the Room schema portable across
 *     min-SDK 29 builds (java.time is available, but the JSON form
 *     remains stable across the WS sync envelope).
 *   • [behavioralModelEncryptedBase64] ↔ doc `behavioralModel: ByteArray`
 *     The blob is sealed with AES-256-GCM whose key is derived from
 *     `PIN ⊕ biometric-secret`; persisted base64 here so kotlinx-
 *     serialization round-trips cleanly and equality on data classes
 *     stays referentially correct (`ByteArray.equals` is identity).
 *   • [pairedHosts]                 ↔ doc `pairedHosts: List<PairedHost>`
 *     Loaded eagerly by [ProfileStore] from the joined `paired_hosts`
 *     table (see [PairedHostEntity]).
 *   • [isActive]                    — local-only flag set by
 *     [ProfileDao.setActive]. Exactly one profile may be active at a
 *     time; the DAO enforces that invariant inside a transaction.
 *
 * Validation invariants are enforced in [init] so corrupt rows
 * surface as `IllegalArgumentException` at hydration time rather
 * than as confusing UI breakage downstream.
 */
@Serializable
data class Profile(
    val id: String,
    val displayName: String,
    val role: Role,
    val avatarUri: String?,
    val createdAtEpochMs: Long,
    val pinHashArgon2: String,
    val biometricEnrolled: Boolean,
    val nfcUid: String?,
    val voicePrintHashHex: String?,
    val familiarRarity: FamiliarRarity,
    val behavioralModelEncryptedBase64: String,
    val pairedHosts: List<PairedHost>,
    val trustScore: Float,
    val isActive: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "Profile.id must not be blank" }
        require(displayName.isEmpty() || displayName.isNotBlank()) { "Profile.displayName must not be blank" }
        require(displayName.length <= MAX_DISPLAY_NAME_LENGTH) {
            "Profile.displayName must be ≤ $MAX_DISPLAY_NAME_LENGTH chars (got ${displayName.length})"
        }
        require(createdAtEpochMs >= 0L) {
            "Profile.createdAtEpochMs must not be negative"
        }
        require(pinHashArgon2.isNotBlank()) {
            "Profile.pinHashArgon2 must not be blank — every profile owns a PIN, even ROOT"
        }
        require(trustScore in 0f..1f) {
            "Profile.trustScore must be in [0, 1] (got $trustScore)"
        }
    }

    companion object {
        /**
         * Hard cap on the operator-visible display name. 64 characters
         * is enough for "Кирило Молодший Брат" + emoji and short
         * enough that the launcher avatar tile and the StatePill
         * footer never wrap.
         */
        const val MAX_DISPLAY_NAME_LENGTH: Int = 64
    }
}
