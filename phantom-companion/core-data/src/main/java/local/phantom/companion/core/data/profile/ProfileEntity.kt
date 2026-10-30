package local.phantom.companion.core.data.profile

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Room mirror of [Profile]. The split between domain ([Profile]) and
 * persistence ([ProfileEntity] + [PairedHostEntity]) lets the domain
 * model carry behaviour-only fields (`pairedHosts: List<PairedHost>`)
 * while the table stays normalised — the joined collection is
 * rebuilt by [ProfileStore] on read.
 *
 * Schema notes:
 *   • Unique index on `display_name` so onboarding cannot create two
 *     profiles with the same display name on the same device. The
 *     server-side multi-profile schema uses the same constraint.
 *   • `is_active` is a Boolean column not a foreign key — exactly one
 *     row is allowed to have it set, enforced by
 *     [ProfileDao.setActive] inside a transaction (clear all → set
 *     one). Kept simple on purpose; a partial index would be tighter
 *     but Room/KSP does not yet emit them cleanly.
 *   • `behavioral_model_encrypted_b64` stores the AES-256-GCM blob as
 *     base64 so the row remains text-serialisable for backup tooling
 *     and equality tests stay value-based.
 */
@Entity(
    tableName = "profiles",
    indices = [Index(value = ["display_name"], unique = true)],
)
data class ProfileEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "role") val role: Role,
    @ColumnInfo(name = "avatar_uri") val avatarUri: String?,
    @ColumnInfo(name = "created_at_epoch_ms") val createdAtEpochMs: Long,
    @ColumnInfo(name = "pin_hash_argon2") val pinHashArgon2: String,
    @ColumnInfo(name = "biometric_enrolled") val biometricEnrolled: Boolean,
    @ColumnInfo(name = "nfc_uid") val nfcUid: String?,
    @ColumnInfo(name = "voice_print_hash_hex") val voicePrintHashHex: String?,
    @ColumnInfo(name = "familiar_rarity") val familiarRarity: FamiliarRarity,
    @ColumnInfo(name = "behavioral_model_encrypted_b64") val behavioralModelEncryptedBase64: String,
    @ColumnInfo(name = "trust_score") val trustScore: Float,
    @ColumnInfo(name = "is_active") val isActive: Boolean,
)

/**
 * Per-profile paired host. `(profile_id, host_id)` is the composite
 * primary key — a host pairs once per profile. Cascade delete drops
 * every host row when the profile is removed so `paired_hosts`
 * cannot leak orphaned credentials when a profile is wiped.
 *
 * `capabilities_csv` stores the [HostCapability] set as a comma-
 * separated string. Room/KSP can emit `Set<...>` converters
 * automatically only when the element type is primitive; using a
 * CSV column lets [ProfileConverters.capabilitiesFromCsv] stay
 * tolerant of unknown values during forward-compat upgrades (future
 * channels just round-trip through the column even before the
 * client knows how to interpret them).
 */
@Entity(
    tableName = "paired_hosts",
    primaryKeys = ["profile_id", "host_id"],
    foreignKeys = [
        ForeignKey(
            entity = ProfileEntity::class,
            parentColumns = ["id"],
            childColumns = ["profile_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("profile_id")],
)
data class PairedHostEntity(
    @ColumnInfo(name = "profile_id") val profileId: String,
    @ColumnInfo(name = "host_id") val hostId: String,
    @ColumnInfo(name = "lan_url") val lanUrl: String,
    @ColumnInfo(name = "relay_url") val relayUrl: String?,
    @ColumnInfo(name = "device_jwt") val deviceJwt: String,
    @ColumnInfo(name = "device_priv_b64") val devicePrivBase64: String,
    @ColumnInfo(name = "last_seen_at_epoch_ms") val lastSeenAtEpochMs: Long,
    @ColumnInfo(name = "is_primary") val isPrimary: Boolean,
    @ColumnInfo(name = "capabilities_csv") val capabilitiesCsv: String,
)

/**
 * Type converters for [Role], [FamiliarRarity], and [HostCapability]
 * sets. Serialised as their stable enum names so SQL-level dumps
 * remain human-readable — the on-disk format is a debugging artifact
 * as much as a runtime payload.
 *
 * Forward-compat: unknown enum names from a future schema are
 * silently dropped (capabilities) or fall back to a safe default
 * (role/rarity). This protects the client from crashing when paired
 * with a PHANTOM OS that ships a channel the phone does not yet know
 * about — the row hydrates, the phone simply ignores the channel.
 */
class ProfileConverters {

    @TypeConverter
    fun roleToString(value: Role): String = value.name

    @TypeConverter
    fun stringToRole(value: String): Role =
        runCatching { Role.valueOf(value) }.getOrDefault(Role.OPERATOR)

    @TypeConverter
    fun rarityToString(value: FamiliarRarity): String = value.name

    @TypeConverter
    fun stringToRarity(value: String): FamiliarRarity =
        runCatching { FamiliarRarity.valueOf(value) }.getOrDefault(FamiliarRarity.NORMAL)

    /**
     * Encode a capability set as comma-separated stable names. Empty
     * set → empty string, never `null`, so SQL `WHERE` clauses can
     * stay simple equality checks downstream.
     */
    @TypeConverter
    fun capabilitiesToCsv(value: Set<HostCapability>): String =
        value.joinToString(",") { it.name }

    /**
     * Parse the CSV column back into a set, dropping unknown names
     * (forward-compat with future capabilities the client does not
     * know about yet).
     */
    @TypeConverter
    fun capabilitiesFromCsv(value: String): Set<HostCapability> =
        if (value.isBlank()) {
            emptySet()
        } else {
            value.splitToSequence(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { name ->
                    runCatching { HostCapability.valueOf(name) }.getOrNull()
                }
                .toSet()
        }
}

/**
 * Map a [Profile] domain object onto a [ProfileEntity] row. The
 * `pairedHosts` collection is intentionally not part of the entity —
 * it lives in [PairedHostEntity] rows that [ProfileStore] writes
 * separately so cascading delete and per-host updates work cleanly.
 */
internal fun Profile.toEntity(): ProfileEntity = ProfileEntity(
    id = id,
    displayName = displayName,
    role = role,
    avatarUri = avatarUri,
    createdAtEpochMs = createdAtEpochMs,
    pinHashArgon2 = pinHashArgon2,
    biometricEnrolled = biometricEnrolled,
    nfcUid = nfcUid,
    voicePrintHashHex = voicePrintHashHex,
    familiarRarity = familiarRarity,
    behavioralModelEncryptedBase64 = behavioralModelEncryptedBase64,
    trustScore = trustScore,
    isActive = isActive,
)

/**
 * Hydrate a [ProfileEntity] row plus its joined hosts back into the
 * domain [Profile]. The order in [hosts] is preserved as supplied by
 * [ProfileDao.getHostsForProfile] (primary first, then by descending
 * `last_seen_at_epoch_ms`).
 */
internal fun ProfileEntity.toDomain(hosts: List<PairedHost>): Profile = Profile(
    id = id,
    displayName = displayName,
    role = role,
    avatarUri = avatarUri,
    createdAtEpochMs = createdAtEpochMs,
    pinHashArgon2 = pinHashArgon2,
    biometricEnrolled = biometricEnrolled,
    nfcUid = nfcUid,
    voicePrintHashHex = voicePrintHashHex,
    familiarRarity = familiarRarity,
    behavioralModelEncryptedBase64 = behavioralModelEncryptedBase64,
    pairedHosts = hosts,
    trustScore = trustScore,
    isActive = isActive,
)

internal fun PairedHost.toEntity(profileId: String): PairedHostEntity = PairedHostEntity(
    profileId = profileId,
    hostId = hostId,
    lanUrl = lanUrl,
    relayUrl = relayUrl,
    deviceJwt = deviceJwt,
    devicePrivBase64 = deviceEd25519PrivBase64,
    lastSeenAtEpochMs = lastSeenAtEpochMs,
    isPrimary = isPrimary,
    capabilitiesCsv = ProfileConverters().capabilitiesToCsv(capabilities),
)

internal fun PairedHostEntity.toDomain(): PairedHost = PairedHost(
    hostId = hostId,
    lanUrl = lanUrl,
    relayUrl = relayUrl,
    deviceJwt = deviceJwt,
    deviceEd25519PrivBase64 = devicePrivBase64,
    lastSeenAtEpochMs = lastSeenAtEpochMs,
    isPrimary = isPrimary,
    capabilities = ProfileConverters().capabilitiesFromCsv(capabilitiesCsv),
)
