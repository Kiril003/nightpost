package local.phantom.companion.feature.onboarding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.phantom.companion.core.data.profile.PairedHostEntity
import local.phantom.companion.core.data.profile.Profile
import local.phantom.companion.core.data.profile.ProfileDao
import local.phantom.companion.core.data.profile.ProfileEntity
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.net.crypto.PinHasher
import local.phantom.companion.feature.onboarding.domain.OnboardingEvent
import local.phantom.companion.feature.onboarding.domain.OnboardingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        store: ProfileStore = ProfileStore(FakeProfileDao()),
    ): OnboardingViewModel = OnboardingViewModel(
        profileStore = store,
        ioDispatcher = testDispatcher,
        pinHashParams = PinHasher.Params.Test,
        idGenerator = { TEST_PROFILE_ID },
        now = { TEST_EPOCH_MS },
    )

    @Test fun `initial state is empty NameStep`() {
        val vm = newViewModel()
        val s = vm.state.value as OnboardingState.NameStep
        assertEquals("", s.draftName)
    }

    @Test fun `one tap after the name lands in the chats with an active profile`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        var taps = 0
        while (vm.state.value !is OnboardingState.Complete && taps < 2) {
            assertTrue("крок ${taps + 1}: ${vm.state.value}", vm.state.value is OnboardingState.NameStep)
            vm.onEvent(OnboardingEvent.NameSubmitted("Кирило"))
            taps++
            advanceUntilIdle()
        }

        val done = vm.state.value as OnboardingState.Complete
        assertEquals(1, taps)
        assertEquals(TEST_PROFILE_ID, done.profileId)
        assertEquals("Кирило", done.displayName)

        val saved = store.getById(TEST_PROFILE_ID)
        assertNotNull(saved)
        assertTrue(saved!!.isActive)
        assertEquals("Кирило", saved.displayName)
    }

    @Test fun `a profile made without a PIN opens to no PIN at all`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        vm.onEvent(OnboardingEvent.NameSubmitted("Anna"))
        advanceUntilIdle()
        val hash = store.getById(TEST_PROFILE_ID)!!.pinHashArgon2
        listOf("0000", "1234", "123456").forEach {
            assertFalse("$it не має відмикати профіль без PIN", PinHasher.verify(it, hash))
        }
    }

    @Test fun `name is trimmed before it is saved`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        vm.onEvent(OnboardingEvent.NameSubmitted("  Anna  "))
        advanceUntilIdle()
        assertEquals("Anna", store.getById(TEST_PROFILE_ID)!!.displayName)
    }

    @Test fun `whitespace-only name is accepted and saved as empty`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        vm.onEvent(OnboardingEvent.NameSubmitted("    "))
        advanceUntilIdle()
        val done = vm.state.value as OnboardingState.Complete
        assertEquals("", done.displayName)
        assertEquals("", store.getById(TEST_PROFILE_ID)!!.displayName)
    }

    @Test fun `name above CAP_QR_NAME surfaces Error`() {
        val vm = newViewModel()
        val tooLong = "x".repeat(local.phantom.companion.core.net.peer.PeerName.CAP_QR_NAME + 1)
        vm.onEvent(OnboardingEvent.NameSubmitted(tooLong))
        assertTrue(vm.state.value is OnboardingState.Error)
    }

    @Test fun `name above MAX_DISPLAY_NAME_LENGTH surfaces Error`() {
        val vm = newViewModel()
        val tooLong = "x".repeat(Profile.MAX_DISPLAY_NAME_LENGTH + 1)
        vm.onEvent(OnboardingEvent.NameSubmitted(tooLong))
        assertTrue(vm.state.value is OnboardingState.Error)
    }

    @Test fun `a second tap while the profile is being written does not write two`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        vm.onEvent(OnboardingEvent.NameSubmitted("Anna"))
        vm.onEvent(OnboardingEvent.NameSubmitted("Anna"))
        advanceUntilIdle()
        assertEquals(1, store.count())
    }

    @Test fun `Restart returns to a clean NameStep`() {
        val vm = newViewModel()
        vm.onEvent(OnboardingEvent.NameSubmitted("x".repeat(Profile.MAX_DISPLAY_NAME_LENGTH + 1)))
        vm.onEvent(OnboardingEvent.Restart)
        val s = vm.state.value as OnboardingState.NameStep
        assertEquals("", s.draftName)
    }

    @Test fun `error from ProfileStore is surfaced as Error and not swallowed`() = runTest(testDispatcher) {
        val vm = newViewModel(ProfileStore(ExplodingProfileDao()))
        vm.onEvent(OnboardingEvent.NameSubmitted("Anna"))
        advanceUntilIdle()
        val err = vm.state.value as OnboardingState.Error
        assertTrue("error message must surface", err.message.isNotEmpty())
    }

    // ── Fakes ────────────────────────────────────────────────────────

    private class FakeProfileDao : ProfileDao {
        private val profiles = LinkedHashMap<String, ProfileEntity>()
        private val hosts = LinkedHashMap<Pair<String, String>, PairedHostEntity>()
        private val emptyFlow = MutableStateFlow<List<ProfileEntity>>(emptyList())
        private val emptyActive = MutableStateFlow<ProfileEntity?>(null)

        override suspend fun insertProfile(profile: ProfileEntity) {
            profiles[profile.id] = profile
        }
        override suspend fun updateProfile(profile: ProfileEntity) {
            profiles[profile.id] = profile
        }
        override suspend fun deleteById(id: String) {
            profiles.remove(id)
        }
        override suspend fun getById(id: String): ProfileEntity? = profiles[id]
        override fun observeAll(): Flow<List<ProfileEntity>> = emptyFlow
        override fun observeActive(): Flow<ProfileEntity?> = emptyActive
        override suspend fun getActive(): ProfileEntity? =
            profiles.values.firstOrNull { it.isActive }
        override suspend fun count(): Int = profiles.size
        override suspend fun getAllProfiles(): List<ProfileEntity> = profiles.values.toList()
        override suspend fun deleteAllProfiles() {
            profiles.clear()
            hosts.clear()
        }
        override suspend fun setActive(id: String) {
            clearActive(); markActive(id)
        }
        override suspend fun clearActive() {
            val updated = profiles.mapValues { (_, e) ->
                if (e.isActive) e.copy(isActive = false) else e
            }
            profiles.clear(); profiles.putAll(updated)
        }
        override suspend fun markActive(id: String) {
            val target = profiles[id] ?: return
            profiles[id] = target.copy(isActive = true)
        }
        override suspend fun upsertHost(host: PairedHostEntity) {
            hosts[host.profileId to host.hostId] = host
        }
        override suspend fun getHostsForProfile(profileId: String): List<PairedHostEntity> =
            hosts.values.filter { it.profileId == profileId }
        override suspend fun deleteHost(profileId: String, hostId: String) {
            hosts.remove(profileId to hostId)
        }
    }

    private class ExplodingProfileDao : ProfileDao {
        override suspend fun insertProfile(profile: ProfileEntity) {
            throw IllegalStateException("simulated DB write failure")
        }
        override suspend fun updateProfile(profile: ProfileEntity) {}
        override suspend fun deleteById(id: String) {}
        override suspend fun getById(id: String): ProfileEntity? = null
        override fun observeAll(): Flow<List<ProfileEntity>> = MutableStateFlow(emptyList())
        override fun observeActive(): Flow<ProfileEntity?> = MutableStateFlow(null)
        override suspend fun getActive(): ProfileEntity? = null
        override suspend fun count(): Int = 0
        override suspend fun getAllProfiles(): List<ProfileEntity> = emptyList()
        override suspend fun deleteAllProfiles() {}
        override suspend fun setActive(id: String) {}
        override suspend fun clearActive() {}
        override suspend fun markActive(id: String) {}
        override suspend fun upsertHost(host: PairedHostEntity) {}
        override suspend fun getHostsForProfile(profileId: String): List<PairedHostEntity> = emptyList()
        override suspend fun deleteHost(profileId: String, hostId: String) {}
    }

    private companion object {
        const val TEST_PROFILE_ID = "test-profile-onboarding-1"
        const val TEST_EPOCH_MS = 1_700_000_000_000L
    }
}
