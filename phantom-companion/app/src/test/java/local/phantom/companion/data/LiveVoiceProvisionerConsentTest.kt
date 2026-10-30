package local.phantom.companion.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import local.phantom.companion.core.ai.ModelManager
import local.phantom.companion.core.ai.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 05.09 privacy audit (phantom-phone-downloads-without-a-key) — the coordinator's
 * correction: a Stream Notice that arrives after ~400 MB is already moving is not
 * consent, it is a confession. [LiveVoiceProvisioner] must not decide [LiveVoiceState.Fetch]
 * — and therefore must never touch the network — until `requested` says the operator
 * asked, from either the CapabilityStrip prompt or the model row in Налаштування → Голос.
 */
class LiveVoiceProvisionerConsentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Hangs every request forever — enough to observe "a fetch started", never enough to finish. */
    private fun hangingClient(hits: AtomicInteger, gate: CompletableDeferred<Unit>) = HttpClient(
        MockEngine { _ ->
            hits.incrementAndGet()
            gate.await()
            respond(content = ByteArray(0), status = HttpStatusCode.OK)
        }
    )

    // База обовʼязкова: без MODELS_BASE_URL жоден наш файл не має адреси, і
    // перенос не почався б навіть після дотику — тоді ці тести міряли б
    // налаштування збірки замість згоди, задля якої вони існують.
    private fun manager(client: HttpClient, appScope: CoroutineScope) =
        ModelManager(
            client = client,
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { "https://example.test/models" },
        )

    private suspend fun awaitSupertonicDownloading(mgr: ModelManager): Boolean =
        withTimeoutOrNull(5_000) {
            while (mgr.states.value[ModelType.SUPERTONIC]?.isDownloading != true) delay(5)
            true
        } == true

    @Test
    fun `a fresh install on Wi-Fi with room to spare never touches the network unasked`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(hangingClient(hits, gate), appScope)

        val prov = LiveVoiceProvisioner(
            models = mgr,
            requested = MutableStateFlow(false),
            online = MutableStateFlow(true),
            metered = MutableStateFlow(false),
            allowMobile = MutableStateFlow(false),
            freeBytes = { 4L * 1024 * 1024 * 1024 },
            scope = appScope,
        )
        prov.arm()

        // Every condition an old build would have called "go" is true here —
        // online, unmetered, plenty of disk — and still nothing must fire.
        delay(300)
        assertEquals(LiveVoiceState.NotRequested, prov.state.value)
        assertEquals("no request means no request to a server either", 0, hits.get())
        assertTrue(mgr.states.value[ModelType.SUPERTONIC]?.isDownloading != true)
        appScope.cancel()
    }

    @Test
    fun `flipping the request flag is what starts the transfer, not the network appearing`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(hangingClient(hits, gate), appScope)
        val requested = MutableStateFlow(false)

        val prov = LiveVoiceProvisioner(
            models = mgr,
            requested = requested,
            online = MutableStateFlow(true),
            metered = MutableStateFlow(false),
            allowMobile = MutableStateFlow(false),
            freeBytes = { 4L * 1024 * 1024 * 1024 },
            scope = appScope,
        )
        prov.arm()
        delay(100)
        assertEquals(0, hits.get())

        // This is the operator's tap — CapabilityStrip's onRequestVoice or the
        // Model row's onDownload, both of which only ever set this same flag.
        requested.value = true

        assertTrue("the tap must actually start the transfer", awaitSupertonicDownloading(mgr))
        appScope.cancel()
    }

    @Test
    fun `a request on a metered network still waits for Wi-Fi, exactly as before`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(hangingClient(hits, gate), appScope)

        val prov = LiveVoiceProvisioner(
            models = mgr,
            requested = MutableStateFlow(true),
            online = MutableStateFlow(true),
            metered = MutableStateFlow(true),
            allowMobile = MutableStateFlow(false),
            freeBytes = { 4L * 1024 * 1024 * 1024 },
            scope = appScope,
        )
        prov.arm()

        withTimeoutOrNull(2_000) { while (prov.state.value !is LiveVoiceState.WaitingWifi) delay(5) }
        assertTrue(prov.state.value is LiveVoiceState.WaitingWifi)
        assertEquals("asking is not the same as spending mobile data", 0, hits.get())
        appScope.cancel()
    }
}
