package local.phantom.companion.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Zero-click hearing: the STT model must self-provision on first voice use,
 * exactly the way [AiBootstrapper] provisions the brain — and never before.
 */
class HearingProvisionerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Serves 1 KB after a gate that (unless completed) hangs the fetch, so a
     *  download stays observably in-flight without ever finishing/unzipping. */
    private fun gatedClient(hits: AtomicInteger, gate: CompletableDeferred<Unit>) = HttpClient(
        MockEngine { _ ->
            hits.incrementAndGet()
            gate.await()
            respond(
                content = ByteArray(1024) { 7 },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "1024"),
            )
        }
    )

    /**
     * КОНТРАКТ ЗМІНЕНО 12.09, і сторож переписано під новий, а не знято.
     *
     * Раніше запис Vosk ніс власну адресу на alphacephei.com, тож звантаження
     * було можливе завжди — і саме тому реліз тихо тягнув 143,9 МБ на
     * холодному старті. Тепер у каталозі лише ключ, а адресу дає
     * MODELS_BASE_URL; без неї не качається НІЩО. Тест мусить сказати, звідки
     * бере, бо цього тепер вимагає продукт.
     */
    private fun manager(client: HttpClient, appScope: CoroutineScope) =
        ModelManager(
            client = client,
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { "https://storage.example/models" },
        )

    /** True once a VOSK transfer is observably in flight (race-free via the
     *  states StateFlow), false if none starts within the deadline. */
    private suspend fun awaitVoskDownloading(mgr: ModelManager): Boolean =
        withTimeoutOrNull(5_000) {
            while (mgr.states.value[ModelType.VOSK]?.isDownloading != true) delay(5)
            true
        } == true

    @Test
    fun `ensureHearing pulls Vosk when missing`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()          // never completed — hangs the fetch
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(gatedClient(hits, gate), appScope)

        val prov = HearingProvisioner(models = mgr, scope = appScope)
        prov.ensureHearingNow(urgent = true)

        assertTrue("ensureHearing must fire a VOSK download when it is missing",
            awaitVoskDownloading(mgr))
        appScope.cancel()
    }

    /**
     * Друга половина того самого контракту: БЕЗ адреси сховища слух не
     * качається взагалі — і мовчазного відкоту на чужий хост немає.
     * Саме цієї гілки бракувало, коли реліз пішов на alphacephei.
     */
    @Test
    fun `without a storage address hearing does not fetch at all`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = ModelManager(
            client = gatedClient(hits, gate),
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { "" },
        )

        val prov = HearingProvisioner(models = mgr, scope = appScope)
        prov.ensureHearingNow(urgent = true)
        delay(300)

        assertEquals("без адреси сховища жодного запиту в мережу бути не може", 0, hits.get())
        assertFalse(
            "слух не сміє вдавати звантаження, якого нема звідки почати",
            mgr.states.value[ModelType.VOSK]?.isDownloading == true,
        )
        appScope.cancel()
    }

    @Test
    fun `present model is not re-downloaded`() = runBlocking {
        // Розкладка справжньої моделі: наявність теки вже не рахується за
        // модель — ModelManager шукає всередині `am/`. Половина розпакованого
        // архіву колись видавалась готовим слухом, і рушій падав на старті.
        File(tmp.root, "models/vosk/am").apply { mkdirs() }
        File(tmp.root, "models/vosk/am/final.mdl").writeText("x")

        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(gatedClient(hits, gate), appScope)

        val prov = HearingProvisioner(models = mgr, scope = appScope)
        assertEquals(HearingState.Ready, prov.state.value)

        val state = prov.ensureHearingNow(urgent = true)
        assertEquals("already present → Ready, no fetch", HearingState.Ready, state)
        delay(200)
        assertEquals("a present model must never touch the network", 0, hits.get())
        appScope.cancel()
    }

    @Test
    fun `network reconnect alone does not provision without intent`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(gatedClient(hits, gate), appScope)

        val prov = HearingProvisioner(models = mgr, scope = appScope)
        val online = MutableStateFlow(false)
        prov.observeNetwork(online)

        online.value = true                              // reconnect, but voice never touched
        delay(200)
        assertEquals("no eager download at cold start / on bare reconnect", 0, hits.get())
        assertFalse(mgr.states.value[ModelType.VOSK]?.isDownloading == true)
        appScope.cancel()
    }

    @Test
    fun `metered defers unless urgent`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(gatedClient(hits, gate), appScope)

        val prov = HearingProvisioner(
            models = mgr,
            scope = appScope,
            isMetered = { true },
        )

        assertEquals(
            "metered + not urgent → park Offline, spend nothing",
            HearingState.Offline,
            prov.ensureHearingNow(urgent = false),
        )
        delay(150)
        assertEquals("no VOSK fetch on a deferred metered sweep", 0, hits.get())
        assertFalse(mgr.states.value[ModelType.VOSK]?.isDownloading == true)

        // Intent overrides the meter, just like the brain.
        prov.ensureHearingNow(urgent = true)
        assertTrue("an actively-listening operator overrides the meter",
            awaitVoskDownloading(mgr))
        appScope.cancel()
    }

    @Test
    fun `offline parks then a wanted fetch resumes on reconnect`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mgr = manager(gatedClient(hits, gate), appScope)

        var up = false
        val prov = HearingProvisioner(models = mgr, scope = appScope, isOnline = { up })
        val online = MutableStateFlow(false)
        prov.observeNetwork(online)

        // Voice touched while offline → parks, arms the watcher, no fetch yet.
        prov.ensureHearing(urgent = true)
        withTimeout(2_000) { while (prov.state.value != HearingState.Offline) delay(5) }
        assertEquals(0, hits.get())

        // Network returns → the wanted fetch resumes by itself.
        up = true
        online.value = true
        assertTrue("reconnect resumes the fetch the operator already wanted",
            awaitVoskDownloading(mgr))
        appScope.cancel()
    }
}
