package local.phantom.companion.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.profile.PairedHostEntity
import local.phantom.companion.core.data.profile.ProfileDao
import local.phantom.companion.core.data.profile.ProfileEntity
import local.phantom.companion.core.data.profile.ProfileStore
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.tokens.NightColors
import local.phantom.companion.core.net.crypto.PinHasher
import local.phantom.companion.core.net.peer.PeerName
import local.phantom.companion.feature.onboarding.OnboardingViewModel
import local.phantom.companion.feature.onboarding.ui.OnboardingScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "uk-w411dp-h891dp-xhdpi")
class FirstRunPassUiTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private var previousProxy: ProxySelector? = null
    private val networkCalls = mutableListOf<URI>()

    @Before
    fun setUp() {
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
    fun close() {
        ProxySelector.setDefault(previousProxy)
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun newViewModel(
        store: ProfileStore = ProfileStore(FakeProfileDao()),
    ): OnboardingViewModel = OnboardingViewModel(
        profileStore = store,
        ioDispatcher = Dispatchers.Main,
        pinHashParams = PinHasher.Params.Test,
        idGenerator = { TEST_PROFILE_ID },
        now = { 1_700_000_000_000L },
    )

    private fun pump(frames: Int = 20) {
        repeat(frames) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
    }

    private fun open(content: @Composable () -> Unit): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) { content() }
        }
        pump(30)
        return c.get()
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) =
        roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun tag(a: ComponentActivity, tag: String) =
        screen(a).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun dp(a: ComponentActivity, px: Number) = px.toFloat() / a.resources.displayMetrics.density

    @Test
    fun `one screen - tapping Start with blank name lands in chats with no second step and zero network calls`() {
        val store = ProfileStore(FakeProfileDao())
        val vm = newViewModel(store)
        var completedProfileId: String? = null
        val a = open {
            OnboardingScreen(
                viewModel = vm,
                onComplete = { completedProfileId = it },
            )
        }
        assertEquals(emptyList<URI>(), networkCalls)

        val start = tag(a, "onb-start")
        assertNotNull("amber Почати button missing", start)
        assertTrue("Почати must be >= 48dp", dp(a, start!!.size.height) >= 47f)
        assertTrue("Почати label must be rendered", all(start).any { "Почати" in it.texts() })

        start.config[SemanticsActions.OnClick].action!!.invoke()
        pump(20)

        assertEquals(TEST_PROFILE_ID, completedProfileId)
        assertEquals(emptyList<URI>(), networkCalls)
    }

    @Test
    fun `Restore is absent when restoreAvailable is false and shown when true`() {
        val vm1 = newViewModel()
        val a1 = open {
            OnboardingScreen(
                viewModel = vm1,
                onComplete = {},
            )
        }
        assertNull("Відновити must be absent when restoreAvailable is false", tag(a1, "onb-restore"))
        assertFalse(
            "Відновити text must not appear on screen by default",
            screen(a1).flatMap { it.texts() }.contains("Відновити"),
        )
        close()

        val vm2 = newViewModel()
        var restoreClicks = 0
        val a2 = open {
            OnboardingScreen(
                viewModel = vm2,
                restoreAvailable = true,
                onRestore = { restoreClicks++ },
                onComplete = {},
            )
        }
        val restore = tag(a2, "onb-restore")
        assertNotNull("Відновити must be shown when restoreAvailable is true", restore)
        assertTrue("Відновити must be >= 48dp", dp(a2, restore!!.size.height) >= 47f)
        assertTrue(all(restore).any { "Відновити" in it.texts() })
        restore.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, restoreClicks)
    }

    @Test
    fun `typed name and initials appear on the pass card live and cap at CAP_QR_NAME with counter in text2`() {
        val vm = newViewModel()
        val a = open {
            OnboardingScreen(
                viewModel = vm,
                onComplete = {},
            )
        }

        val card = tag(a, "onb-pass-card")
        val photoSlot = tag(a, "onb-pass-photo")
        val cardName = tag(a, "onb-pass-name")
        val initials = tag(a, "onb-pass-initials")
        val input = tag(a, "onb-name-input")
        val counter = tag(a, "onb-name-counter")

        assertNotNull("pass card missing", card)
        assertNotNull("photo slot missing", photoSlot)
        assertNotNull("card name missing", cardName)
        assertNotNull("initials missing", initials)
        assertNotNull("name input missing", input)
        assertNotNull("name counter missing", counter)

        assertTrue("photo slot must be >= 48dp", dp(a, photoSlot!!.size.width) >= 47f && dp(a, photoSlot.size.height) >= 47f)
        assertNotNull("photo slot must be clickable", photoSlot.config.getOrNull(SemanticsActions.OnClick))
        assertEquals(listOf("Ваше імʼя"), cardName!!.texts())
        assertEquals(listOf("N"), initials!!.texts())
        assertEquals(listOf("0/${PeerName.CAP_QR_NAME}"), counter!!.texts())

        val counterLayouts = mutableListOf<TextLayoutResult>()
        counter.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(counterLayouts)
        assertEquals("counter must be in text2", NightColors.text2, counterLayouts.single().layoutInput.style.color)

        input!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString("Іра Коваль"))
        pump(10)

        val updatedCard = tag(a, "onb-pass-card")!!
        val updatedName = tag(a, "onb-pass-name")!!
        val updatedInitials = tag(a, "onb-pass-initials")!!
        val updatedCounter = tag(a, "onb-name-counter")!!

        assertEquals(listOf("Іра Коваль"), updatedName.texts())
        assertTrue(
            "typed name must sit inside the pass card",
            updatedName.boundsInRoot.top >= updatedCard.boundsInRoot.top &&
                updatedName.boundsInRoot.bottom <= updatedCard.boundsInRoot.bottom,
        )
        assertEquals(listOf("ІК"), updatedInitials.texts())
        assertEquals(listOf("10/${PeerName.CAP_QR_NAME}"), updatedCounter.texts())

        tag(a, "onb-name-input")!!.config[SemanticsActions.SetText].action!!
            .invoke(AnnotatedString("Я".repeat(40)))
        pump(10)

        assertEquals(listOf("Я".repeat(PeerName.CAP_QR_NAME)), tag(a, "onb-pass-name")!!.texts())
        assertEquals(listOf("${PeerName.CAP_QR_NAME}/${PeerName.CAP_QR_NAME}"), tag(a, "onb-name-counter")!!.texts())
    }

    @Test
    fun `photo slot renders picked photo instead of initials and OnboardingDestination wires savePhoto`() {
        val vm = newViewModel()
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val a = open {
            OnboardingScreen(
                viewModel = vm,
                initialPhoto = bmp,
                onComplete = {},
            )
        }
        assertNotNull("picked photo must render in the slot", tag(a, "onb-pass-photo-img"))
        assertNull("initials must not render when a photo is present", tag(a, "onb-pass-initials"))

        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val nav = File(root, "app/src/main/java/local/phantom/companion/navigation/PhantomNavGraph.kt").readText()
        val dest = nav.substringAfter("private fun OnboardingDestination(").substringBefore("\n@Composable")
        assertTrue("OnboardingDestination must store picked photo via savePhoto", "savePhoto(" in dest)
        assertTrue("photo save must outlive the onboarding screen", "container.scope.launch" in dest)
        assertFalse("OnboardingDestination must not create PairFlowClient", "PairFlowClient" in dest)
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

    private companion object {
        const val TEST_PROFILE_ID = "test-profile-pass-ui-1"
    }
}
