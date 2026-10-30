package local.phantom.companion.core.data.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Repository facade over [ProfileDao]. Hides the
 * Profile ↔ ProfileEntity / PairedHost ↔ PairedHostEntity mapping so
 * UI layers (`feature-onboarding`, `feature-launcher`, `app`) deal
 * exclusively with the domain types.
 *
 * Concurrency: every mutation is a single suspending DAO call (or a
 * Room `@Transaction`). The store does not own a coroutine scope —
 * the call site (typically a ViewModel) supplies one. Reads use Flow
 * so Compose can re-collect on configuration change without leaking.
 *
 * Active-profile invariant: exactly one row in `profiles` may carry
 * `is_active = 1`. The DAO's [ProfileDao.setActive] runs the swap
 * inside a transaction. [create] and [update] do **not** flip the
 * flag automatically — onboarding finalisation calls [setActive]
 * explicitly when the new profile is the one to surface in the UI.
 */
class ProfileStore(private val dao: ProfileDao) {

    /**
     * Persist a brand-new profile + its initial paired-host list
     * (which may be empty during onboarding before the QR scan
     * step). Called from `feature-onboarding` finalisation.
     */
    suspend fun create(profile: Profile) {
        dao.insertProfile(profile.toEntity())
        profile.pairedHosts.forEach { host ->
            dao.upsertHost(host.toEntity(profile.id))
        }
    }

    /**
     * Replace the profile row in place. Hosts use
     * `OnConflictStrategy.REPLACE`, so passing the full list here
     * upserts each one — but it does **not** delete hosts removed
     * from the in-memory list. Use [removeHost] to revoke.
     */
    suspend fun update(profile: Profile) {
        dao.updateProfile(profile.toEntity())
        profile.pairedHosts.forEach { host ->
            dao.upsertHost(host.toEntity(profile.id))
        }
    }

    /**
     * Deletes the profile row + cascades to `paired_hosts` via the
     * foreign-key declaration on [PairedHostEntity].
     */
    suspend fun delete(id: String) {
        dao.deleteById(id)
    }

    suspend fun getById(id: String): Profile? {
        val entity = dao.getById(id) ?: return null
        return entity.hydrate()
    }

    suspend fun getActive(): Profile? {
        val entity = dao.getActive() ?: return null
        return entity.hydrate()
    }

    suspend fun setActive(id: String) {
        dao.setActive(id)
    }

    suspend fun count(): Int = dao.count()

    suspend fun allProfiles(): List<Profile> =
        dao.getAllProfiles().map { it.hydrate() }

    suspend fun adoptProfiles(profiles: List<Profile>) {
        dao.replaceAll(profiles.map { it.toEntity() })
    }

    /**
     * Stream of every profile on the device, hydrated with its
     * paired hosts. Each emission re-loads the joined hosts — fine
     * for the launcher's single-shot list view; downstream code
     * that needs sub-second host updates should observe a single
     * profile via [observeActive] instead.
     */
    fun observeAll(): Flow<List<Profile>> = flow {
        dao.observeAll().collect { entities ->
            emit(entities.map { it.hydrate() })
        }
    }

    fun observeActive(): Flow<Profile?> = flow {
        dao.observeActive().collect { entity ->
            emit(entity?.hydrate())
        }
    }

    suspend fun upsertHost(profileId: String, host: PairedHost) {
        dao.upsertHost(host.toEntity(profileId))
    }

    suspend fun removeHost(profileId: String, hostId: String) {
        dao.deleteHost(profileId, hostId)
    }

    private suspend fun ProfileEntity.hydrate(): Profile {
        val hosts = dao.getHostsForProfile(id).map { it.toDomain() }
        return toDomain(hosts)
    }
}
