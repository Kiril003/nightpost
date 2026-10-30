package local.phantom.companion.core.data.profile

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Single-DAO surface for the multi-profile store. Kept narrow on
 * purpose — the only mutation paths are
 *   • [insertProfile] / [updateProfile] / [deleteById]
 *   • [setActive] (transactional swap of `is_active`)
 *   • [upsertHost] / [deleteHost]
 *
 * Anything else (listing for the launcher, observing the active
 * profile in Compose) reads through the [observeAll] /
 * [observeActive] Flows so the UI never sees a half-applied state
 * mid-transaction.
 */
@Dao
interface ProfileDao {

    /**
     * Create a new profile. We use [OnConflictStrategy.ABORT] (not
     * REPLACE) — the unique-display-name index is a real invariant
     * the onboarding screen must respect; silently overwriting
     * another profile would be a data-loss bug.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertProfile(profile: ProfileEntity)

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ProfileEntity?

    /**
     * All profiles in creation order (oldest first). The launcher
     * tile carousel renders newest-on-top — it reverses the list at
     * the UI layer so this query stays predictable.
     */
    @Query("SELECT * FROM profiles ORDER BY created_at_epoch_ms ASC")
    fun observeAll(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE is_active = 1 LIMIT 1")
    fun observeActive(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE is_active = 1 LIMIT 1")
    suspend fun getActive(): ProfileEntity?

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun count(): Int

    @Query("SELECT * FROM profiles ORDER BY created_at_epoch_ms ASC")
    suspend fun getAllProfiles(): List<ProfileEntity>

    @Query("DELETE FROM profiles")
    suspend fun deleteAllProfiles()

    @Transaction
    suspend fun replaceAll(profiles: List<ProfileEntity>) {
        deleteAllProfiles()
        profiles.forEach { insertProfile(it) }
    }

    /**
     * Atomic active-profile swap. Splitting clear + mark into two
     * statements inside a [Transaction] is the simplest way to
     * uphold the "exactly one active" invariant without a partial
     * index — Room runs both queries inside a single SQLite
     * transaction so an external observer never sees zero or two
     * active profiles.
     */
    @Transaction
    suspend fun setActive(id: String) {
        clearActive()
        markActive(id)
    }

    @Query("UPDATE profiles SET is_active = 0 WHERE is_active = 1")
    suspend fun clearActive()

    @Query("UPDATE profiles SET is_active = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    // ── Paired hosts ──────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertHost(host: PairedHostEntity)

    /**
     * Hosts for a profile, primary first, then by recency. Ordering
     * matters because [ProfileStore] surfaces this list straight to
     * the link service which iterates until a host accepts the JWT.
     */
    @Query(
        """
        SELECT * FROM paired_hosts
        WHERE profile_id = :profileId
        ORDER BY is_primary DESC, last_seen_at_epoch_ms DESC
        """,
    )
    suspend fun getHostsForProfile(profileId: String): List<PairedHostEntity>

    @Query("DELETE FROM paired_hosts WHERE profile_id = :profileId AND host_id = :hostId")
    suspend fun deleteHost(profileId: String, hostId: String)
}
