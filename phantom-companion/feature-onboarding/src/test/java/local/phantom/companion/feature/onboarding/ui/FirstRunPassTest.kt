package local.phantom.companion.feature.onboarding.ui

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
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
import local.phantom.companion.core.data.profile.ProfileDao
import local.phantom.companion.core.data.profile.ProfileEntity
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.net.crypto.PinHasher
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.feature.onboarding.OnboardingViewModel
import local.phantom.companion.feature.onboarding.domain.OnboardingEvent
import local.phantom.companion.feature.onboarding.domain.OnboardingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FirstRunPassTest {

    private val testDispatcher = StandardTestDispatcher()
    private var previousProxy: ProxySelector? = null
    private val networkCalls = mutableListOf<URI>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        networkCalls.clear()
        previousProxy = ProxySelector.getDefault()
        ProxySelector.setDefault(
            object : ProxySelector() {
                override fun select(uri: URI?): List<Proxy> {
                    if (uri != null) networkCalls += uri
                    return listOf(Proxy.NO_PROXY)
                }

                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                    if (uri != null) networkCalls += uri
                }
            },
        )
    }

    @After
    fun tearDown() {
        ProxySelector.setDefault(previousProxy)
        Dispatchers.resetMain()
    }

    private fun newViewModel(
        store: ProfileStore = ProfileStore(FakeProfileDao()),
    ): OnboardingViewModel = OnboardingViewModel(
        profileStore = store,
        ioDispatcher = testDispatcher,
        pinHashParams = PinHasher.Params.Test,
        idGenerator = { "profile-pass-1" },
        now = { 1_700_000_000_000L },
    )

    private fun uiFile(name: String): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-onboarding").isDirectory) dir = dir.parentFile
        val f = File(
            dir ?: fail("feature-onboarding/ not found") as Nothing,
            "feature-onboarding/src/main/java/local/phantom/companion/feature/onboarding/ui/$name",
        )
        assertTrue("missing $f", f.isFile)
        return f.readText()
    }

    @Test
    fun `one screen - blank name goes straight to Complete with zero network calls`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        assertTrue(vm.state.value is OnboardingState.NameStep)
        assertEquals(emptyList<URI>(), networkCalls)

        vm.onEvent(OnboardingEvent.NameSubmitted(submittedName("   ")))
        advanceUntilIdle()

        val done = vm.state.value as OnboardingState.Complete
        assertEquals("profile-pass-1", done.profileId)
        assertEquals("", done.displayName)
        assertEquals(emptyList<URI>(), networkCalls)

        val saved = store.getActive()
        assertNotNull(saved)
        assertEquals("", saved!!.displayName)
    }

    @Test
    fun `restore button is gated by restoreAvailable defaulting to false`() {
        val screen = uiFile("OnboardingScreen.kt")
        val step = uiFile("NameStep.kt")
        assertTrue("OnboardingScreen must default restoreAvailable = false", "restoreAvailable: Boolean = false" in screen)
        assertTrue("NameStep must default restoreAvailable = false", "restoreAvailable: Boolean = false" in step)
        assertTrue("Restore must be gated behind if (restoreAvailable)", "if (restoreAvailable)" in step)
    }

    @Test
    fun `typed name and initials fill the pass card live and cap at PeerName CAP_QR_NAME`() {
        assertEquals(32, PeerName.CAP_QR_NAME)
        assertEquals("Без імені", passCardName("   ", "Без імені"))
        assertEquals("Іра Коваль", passCardName("  Іра Коваль ", "Без імені"))
        assertEquals("N", passInitials("   "))
        assertEquals("О", passInitials("Оля"))
        assertEquals("ІК", passInitials("Іра Коваль"))

        val vm = newViewModel()
        vm.onEvent(OnboardingEvent.NameSubmitted("Я".repeat(PeerName.CAP_QR_NAME + 1)))
        assertTrue(vm.state.value is OnboardingState.Error)

        val step = uiFile("NameStep.kt")
        assertTrue("NameStep must cap with PeerName.CAP_QR_NAME", "PeerName.CAP_QR_NAME" in step)
        assertFalse("NameStep must use text2, not text3", "c.text3" in step)
    }

    @Test
    fun `the profile never keeps a temporary picker grant as its photo`() = runTest(testDispatcher) {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        vm.onEvent(OnboardingEvent.NameSubmitted("Оля"))
        advanceUntilIdle()

        val saved = store.getActive()
        assertNotNull(saved)
        assertNull("photo lives in the own face store, not as a content:// grant", saved!!.avatarUri)
        assertEquals(emptyList<URI>(), networkCalls)
        assertFalse("the picker uri must not reach the profile", "OnboardingEvent.PhotoPicked" in uiFile("OnboardingScreen.kt"))
    }

    @Test
    fun `FirstRunAsksNothingTest stays green`() {
        val guard = FirstRunAsksNothingTest()
        guard.`onboarding never asks for the screen, accessibility, camera or a PIN`()
        guard.`an empty name still lets the person in`()
    }

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
            clearActive()
            markActive(id)
        }
        override suspend fun clearActive() {
            val updated = profiles.mapValues { (_, e) ->
                if (e.isActive) e.copy(isActive = false) else e
            }
            profiles.clear()
            profiles.putAll(updated)
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
}
