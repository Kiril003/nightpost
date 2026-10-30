package local.phantom.companion.core.data.profile

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage for the [ProfileStore] facade. We back the DAO with an
 * in-memory fake so the launcher / onboarding / launcher-switcher flows can
 * be exercised without spinning Robolectric or an emulator — both are
 * fragile on this Radxa aarch64 host.
 *
 * What this test layer guarantees:
 *   • create / update / delete map cleanly between [Profile] ↔
 *     [ProfileEntity] and [PairedHost] ↔ [PairedHostEntity].
 *   • setActive upholds the "exactly one active profile" invariant.
 *   • observeAll / observeActive Flows propagate after each mutation.
 *   • removeHost is targeted (does not collateral-damage other profiles).
 *
 * Schema-level guarantees (cascade-delete via SQLite foreign keys, partial
 * index on `is_active`, SQLCipher key wrapping) live in `androidTest/` and
 * run on-device — they are explicitly out of scope for this JVM suite.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileStoreTest {

    private fun freshHost(
        hostId: String,
        isPrimary: Boolean = false,
        capabilities: Set<HostCapability> = setOf(HostCapability.STATE),
        lastSeenAtEpochMs: Long = 1_700_000_500_000L,
    ): PairedHost = PairedHost(
        hostId = hostId,
        lanUrl = "http://192.168.1.10:8000",
        deviceJwt = "jwt.$hostId",
        deviceEd25519PrivBase64 = "priv.$hostId",
        lastSeenAtEpochMs = lastSeenAtEpochMs,
        isPrimary = isPrimary,
        capabilities = capabilities,
    )

    private fun freshProfile(
        id: String,
        displayName: String = id,
        role: Role = Role.OPERATOR,
        hosts: List<PairedHost> = emptyList(),
    ): Profile = Profile(
        id = id,
        displayName = displayName,
        role = role,
        avatarUri = null,
        createdAtEpochMs = 1_700_000_000_000L,
        pinHashArgon2 = "\$argon2id\$v=19\$m=65536,t=3,p=1\$saltsalt\$$id",
        biometricEnrolled = false,
        nfcUid = null,
        voicePrintHashHex = null,
        familiarRarity = FamiliarRarity.NORMAL,
        behavioralModelEncryptedBase64 = "AAAAAA==",
        pairedHosts = hosts,
        trustScore = 1f,
    )

    @Test fun `create then getById returns the hydrated profile with hosts`() = runTest {
        val dao = FakeProfileDao()
        val store = ProfileStore(dao)
        val hosts = listOf(
            freshHost("home", isPrimary = true, capabilities = setOf(HostCapability.STATE, HostCapability.SENSOR)),
            freshHost("lab", isPrimary = false, capabilities = setOf(HostCapability.MAP)),
        )
        val p = freshProfile("p1", hosts = hosts)
        store.create(p)

        val rehydrated = store.getById("p1")
        assertNotNull(rehydrated)
        assertEquals("p1", rehydrated!!.id)
        // Hosts come back primary-first, then by descending lastSeen.
        assertEquals(listOf("home", "lab"), rehydrated.pairedHosts.map { it.hostId })
        assertEquals(setOf(HostCapability.STATE, HostCapability.SENSOR), rehydrated.pairedHosts[0].capabilities)
    }

    @Test fun `setActive flips exactly one profile's active flag`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(freshProfile("p1"))
        store.create(freshProfile("p2"))
        store.create(freshProfile("p3"))

        store.setActive("p2")
        assertEquals("p2", store.getActive()?.id)
        // The other two must report as inactive.
        assertFalse(store.getById("p1")!!.isActive)
        assertFalse(store.getById("p3")!!.isActive)

        store.setActive("p3")
        assertEquals("p3", store.getActive()?.id)
        assertFalse(store.getById("p2")!!.isActive)
    }

    @Test fun `delete removes profile and cascades to its hosts`() = runTest {
        val dao = FakeProfileDao()
        val store = ProfileStore(dao)
        store.create(freshProfile("p1", hosts = listOf(freshHost("h1"), freshHost("h2"))))
        store.create(freshProfile("p2", hosts = listOf(freshHost("h3"))))

        assertEquals(2, store.count())
        store.delete("p1")
        assertEquals(1, store.count())
        assertNull(store.getById("p1"))

        // p2's hosts must survive.
        val p2 = store.getById("p2")
        assertNotNull(p2)
        assertEquals(listOf("h3"), p2!!.pairedHosts.map { it.hostId })

        // p1's hosts must be gone (cascade).
        assertTrue(dao.getHostsForProfile("p1").isEmpty())
    }

    @Test fun `removeHost is targeted (does not affect other profiles)`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(freshProfile("p1", hosts = listOf(freshHost("home"), freshHost("lab"))))
        store.create(freshProfile("p2", hosts = listOf(freshHost("home"))))

        store.removeHost(profileId = "p1", hostId = "home")

        assertEquals(listOf("lab"), store.getById("p1")!!.pairedHosts.map { it.hostId })
        // p2's `home` must survive — composite key is (profile_id, host_id),
        // so "home" under p2 is a different row.
        assertEquals(listOf("home"), store.getById("p2")!!.pairedHosts.map { it.hostId })
    }

    @Test fun `observeActive emits null then the new active profile`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(freshProfile("p1"))

        // Initial value before any setActive call: nothing active.
        assertNull(store.observeActive().first())

        store.setActive("p1")
        val active = store.observeActive().first()
        assertNotNull(active)
        assertEquals("p1", active!!.id)
        assertTrue(active.isActive)
    }

    @Test fun `observeAll lists every profile in creation order`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(freshProfile("oldest"))
        store.create(freshProfile("middle"))
        store.create(freshProfile("newest"))

        val all = store.observeAll().first()
        assertEquals(listOf("oldest", "middle", "newest"), all.map { it.id })
    }

    @Test fun `update replaces fields and upserts hosts (REPLACE semantics)`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(freshProfile("p1", hosts = listOf(freshHost("home"))))

        val refreshedHost = freshHost("home", isPrimary = true, capabilities = setOf(HostCapability.MAP))
        val patched = freshProfile("p1", displayName = "Кирило", hosts = listOf(refreshedHost))
        store.update(patched)

        val out = store.getById("p1")!!
        assertEquals("Кирило", out.displayName)
        assertEquals(setOf(HostCapability.MAP), out.pairedHosts.single().capabilities)
        assertTrue(out.pairedHosts.single().isPrimary)
    }

    @Test fun `upsertHost replaces an existing host but keeps the others`() = runTest {
        val store = ProfileStore(FakeProfileDao())
        store.create(
            freshProfile(
                "p1",
                hosts = listOf(
                    freshHost("home", isPrimary = true, capabilities = setOf(HostCapability.STATE)),
                    freshHost("lab", isPrimary = false, capabilities = setOf(HostCapability.AGENT)),
                ),
            ),
        )

        store.upsertHost(
            profileId = "p1",
            host = freshHost("home", isPrimary = true, capabilities = setOf(HostCapability.STATE, HostCapability.MAP)),
        )

        val hosts = store.getById("p1")!!.pairedHosts
        assertEquals(2, hosts.size)
        val home = hosts.single { it.hostId == "home" }
        assertEquals(setOf(HostCapability.STATE, HostCapability.MAP), home.capabilities)
        val lab = hosts.single { it.hostId == "lab" }
        assertEquals(setOf(HostCapability.AGENT), lab.capabilities)
    }

    // ── In-memory fake ────────────────────────────────────────────────

    /**
     * Fake [ProfileDao] backed by a `LinkedHashMap` (creation order) for
     * profiles and a `(profileId, hostId)` map for hosts. Mutations push
     * fresh snapshots onto the [MutableStateFlow]s so [observeAll] /
     * [observeActive] return real reactive streams the suspending tests
     * can `.first()` against.
     */
    private class FakeProfileDao : ProfileDao {
        private val profiles = LinkedHashMap<String, ProfileEntity>()
        private val hosts = LinkedHashMap<Pair<String, String>, PairedHostEntity>()
        private val profilesFlow = MutableStateFlow<List<ProfileEntity>>(emptyList())
        private val activeFlow = MutableStateFlow<ProfileEntity?>(null)

        override suspend fun insertProfile(profile: ProfileEntity) {
            check(profile.id !in profiles) { "ABORT: duplicate id ${profile.id}" }
            check(profiles.values.none { it.displayName == profile.displayName }) {
                "ABORT: display_name index would collide on '${profile.displayName}'"
            }
            profiles[profile.id] = profile
            republish()
        }

        override suspend fun updateProfile(profile: ProfileEntity) {
            check(profile.id in profiles) { "update on missing id ${profile.id}" }
            profiles[profile.id] = profile
            republish()
        }

        override suspend fun deleteById(id: String) {
            profiles.remove(id)
            // Cascade — clear hosts for that profile.
            hosts.keys.filter { it.first == id }.forEach { hosts.remove(it) }
            republish()
        }

        override suspend fun getById(id: String): ProfileEntity? = profiles[id]

        override fun observeAll(): Flow<List<ProfileEntity>> = profilesFlow

        override fun observeActive(): Flow<ProfileEntity?> = activeFlow

        override suspend fun getActive(): ProfileEntity? = profiles.values.firstOrNull { it.isActive }

        override suspend fun count(): Int = profiles.size

        override suspend fun getAllProfiles(): List<ProfileEntity> = profiles.values.toList()

        override suspend fun deleteAllProfiles() {
            profiles.clear()
            hosts.clear()
            republish()
        }

        // Override to sidestep any DefaultImpls indirection — the
        // production interface declares `setActive` with a default body
        // (clearActive + markActive), and we want to exercise *that*
        // surface so a regression where Room overrides setActive
        // independently is still caught.
        override suspend fun setActive(id: String) {
            clearActive()
            markActive(id)
        }

        override suspend fun clearActive() {
            val updated = profiles.mapValues { (_, e) ->
                if (e.isActive) e.copy(isActive = false) else e
            }
            profiles.clear()
            profiles.putAll(updated)
            republish()
        }

        override suspend fun markActive(id: String) {
            val target = profiles[id] ?: error("markActive: unknown id $id")
            profiles[id] = target.copy(isActive = true)
            republish()
        }

        override suspend fun upsertHost(host: PairedHostEntity) {
            hosts[host.profileId to host.hostId] = host
        }

        override suspend fun getHostsForProfile(profileId: String): List<PairedHostEntity> =
            hosts.values
                .filter { it.profileId == profileId }
                .sortedWith(
                    compareByDescending<PairedHostEntity> { it.isPrimary }
                        .thenByDescending { it.lastSeenAtEpochMs },
                )

        override suspend fun deleteHost(profileId: String, hostId: String) {
            hosts.remove(profileId to hostId)
        }

        private fun republish() {
            // Sort by created_at_epoch_ms ASC to match the production
            // `observeAll` query.
            val snapshot = profiles.values.sortedBy { it.createdAtEpochMs }
            profilesFlow.value = snapshot
            activeFlow.value = snapshot.firstOrNull { it.isActive }
        }
    }
}
