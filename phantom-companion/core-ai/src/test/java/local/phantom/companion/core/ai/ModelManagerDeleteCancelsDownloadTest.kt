package local.phantom.companion.core.ai

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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 05.09 privacy audit — «видалити» in Settings is the operator's only control
 * over a fetch he never asked for (Supertonic at boot, Vosk on first mic
 * touch). Before this test, [ModelManager.startDelete] joined an in-flight
 * download job instead of replacing it — [ModelManager.launchExclusive] reads
 * an active job in [ModelManager.jobs] as work to await, not work to cancel
 * — so tapping delete mid-transfer silently did nothing until the transfer
 * finished or failed on its own. See phantom-silently-does-nothing.
 */
class ModelManagerDeleteCancelsDownloadTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Serves nothing, forever — a download that stays observably in-flight. */
    private fun hangingClient(hits: AtomicInteger, gate: CompletableDeferred<Unit>) = HttpClient(
        MockEngine { _ ->
            hits.incrementAndGet()
            gate.await()
            respond(content = ByteArray(0), status = HttpStatusCode.OK)
        }
    )

    private suspend fun awaitDownloading(mgr: ModelManager, type: ModelType): Boolean =
        withTimeoutOrNull(5_000) {
            while (mgr.states.value[type]?.isDownloading != true) delay(5)
            true
        } == true

    @Test
    fun `delete mid-download actually stops the transfer instead of joining it`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()          // never completed — hangs the fetch
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // YAMNET 12.09 переїхав у нашу роздачу, тож у каталозі в нього ВІДНОСНИЙ
        // ключ, і без бази звантаження не почнеться взагалі — а цей тест міряє
        // саме скасування вже РОЗПОЧАТОГО. Тому база тут обовʼязкова.
        val mgr = ModelManager(
            client = hangingClient(hits, gate),
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { "https://storage.example" },
            verifyDownloads = false,
        )

        mgr.startDownload(ModelType.YAMNET)
        assertTrue("the download must actually be in flight before we try to stop it",
            awaitDownloading(mgr, ModelType.YAMNET))

        mgr.startDelete(ModelType.YAMNET)

        val stopped = withTimeoutOrNull(5_000) {
            while (mgr.states.value[ModelType.YAMNET]?.isDownloading != false) delay(5)
            true
        } == true
        assertTrue(
            "startDelete must cancel the running download, not silently join it and wait it out",
            stopped,
        )
        assertFalse(
            "the model must read as absent once delete has actually run",
            mgr.states.value[ModelType.YAMNET]?.isPresent == true,
        )
        assertFalse(
            "the model file must not be left on disk",
            mgr.getModelFile(ModelType.YAMNET).exists(),
        )
        appScope.cancel()
    }
}
