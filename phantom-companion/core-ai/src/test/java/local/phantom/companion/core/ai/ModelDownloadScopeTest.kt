package local.phantom.companion.core.ai

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.atomic.AtomicInteger

/**
 * The download must outlive the UI that started it.
 *
 * The original bug: VoiceSettingsScreen ran the 400 MB Supertonic fetch
 * inside `rememberCoroutineScope()`. That scope is owned by the
 * composition — navigate away from Settings (descend to Stratum W, open
 * a thread) and the composition leaves, the scope is cancelled, and 400 MB
 * of transfer dies with it. `viewModelScope` has the same disease, just a
 * longer leash. A model transfer is application-lifetime work, so
 * [ModelManager] now owns the scope and the UI may only *fire* it.
 *
 * These tests pin exactly that: kill the caller, the download lives.
 */
class ModelDownloadScopeTest {

    // Без бази наш файл не має адреси й перенос не почався б — ці тести про
    // час життя переносу, а не про налаштування збірки.
    private val base = "https://example.test/models"

    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * Wait until the transfer has actually reached the (gated) network call.
     *
     * Bounded on purpose. This used to be a bare `while (hits.get() == 0)
     * delay(5)`: correct when the code works, but a regression that stops the
     * fetch ever reaching the engine turned it into an infinite spin — on CI
     * that is not a red test, it is a job that burns its whole timeout and
     * reports nothing. A deadline makes the same failure legible in seconds.
     */
    private suspend fun awaitFirstHit(hits: AtomicInteger) {
        val reached = withTimeoutOrNull(10_000) {
            while (hits.get() == 0) delay(5)
            true
        }
        assertTrue(
            "the download never reached the HTTP engine within 10 s — " +
                "startDownload() is not firing the request at all",
            reached == true,
        )
    }

    /** Serves each part slowly enough that we can cancel mid-flight. */
    private fun slowClient(
        hits: AtomicInteger,
        gate: CompletableDeferred<Unit>?,
        asked: MutableSet<String>? = null,
    ) = HttpClient(
        MockEngine { request ->
            hits.incrementAndGet()
            asked?.add(request.url.toString())
            gate?.await()
            respond(
                content = ByteArray(1024) { 7 },
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentLength, "1024"),
            )
        }
    )

    @Test
    fun `download survives cancellation of the caller's scope`() = runBlocking {
        val hits = AtomicInteger()
        val asked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = ModelManager(
            client = slowClient(hits, gate, asked),
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { base },
            verifyDownloads = false,
        )

        // A screen's scope — the thing that dies on navigation.
        val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        uiScope.launch { manager.startDownload(ModelType.SUPERTONIC) }
        // Let the fetch reach the (gated) network call, then kill the UI —
        // the operator leaving Settings mid-download.
        awaitFirstHit(hits)
        uiScope.cancel()

        // Now let the transfer proceed. If the download were bound to the
        // composition, it would already be dead and this would time out.
        gate.complete(Unit)
        withTimeout(10_000) {
            while (manager.states.value[ModelType.SUPERTONIC]?.isPresent != true) delay(10)
        }

        // Маніфест — це ДВА переліки, а не один. `parts` несе рушій і базовий
        // голос, `extraParts` — решту девʼять стилів (M2…F5), і качалка обходить
        // обидва (ModelManager: forEachIndexed по parts, forEach по extraParts).
        // Сторож рахував лише перший і червонів 7 проти 16 — не тому, що качалка
        // зламалась, а тому, що контракт розширили, а його не оновили. Число
        // виводиться з маніфесту навмисно: вписаний літерал протухне на
        // наступному стилі й знову виглядатиме як поломка.
        val manifest = ModelType.SUPERTONIC.parts + ModelType.SUPERTONIC.extraParts
        assertEquals(
            "every part must be fetched despite the UI scope dying",
            manifest.size,
            hits.get(),
        )
        // І те, що сторож насправді стереже: жодну частину не проминули. Саме
        // лише число збігалося б і тоді, коли одну взяли двічі, а іншу ні.
        val missed = manifest.map { if (it.isOurs) "$base/${it.url}" else it.url }
            .filterNot { u -> asked.any { it == u } }
        assertTrue("частини маніфесту не завантажені: $missed", missed.isEmpty())
        assertTrue(
            "model must be present on disk",
            manager.states.value[ModelType.SUPERTONIC]?.isPresent == true,
        )
        appScope.cancel()
    }

    @Test
    fun `a second tap joins the running download instead of racing it`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = ModelManager(
            client = slowClient(hits, gate),
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { base },
            verifyDownloads = false,
        )

        val first = manager.startDownload(ModelType.VOSK)
        awaitFirstHit(hits)
        val second = manager.startDownload(ModelType.VOSK)

        assertTrue("impatient double-tap must join, not spawn a rival", first === second)

        gate.complete(Unit)
        withTimeout(10_000) { first.join() }
        assertEquals("only one transfer may touch the file", 1, hits.get())
        appScope.cancel()
    }

    @Test
    fun `cancelDownload clears the spinner without reporting a failure`() = runBlocking {
        val hits = AtomicInteger()
        val gate = CompletableDeferred<Unit>()   // never completed — hangs the fetch
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val manager = ModelManager(
            client = slowClient(hits, gate),
            rootDir = tmp.root,
            scope = appScope,
            modelsBaseUrl = { base },
            verifyDownloads = false,
        )

        val job = manager.startDownload(ModelType.SUPERTONIC)
        awaitFirstHit(hits)
        manager.cancelDownload(ModelType.SUPERTONIC)
        withTimeout(10_000) { job.join() }

        val state = manager.states.value[ModelType.SUPERTONIC]!!
        assertFalse("spinner must clear on abort", state.isDownloading)
        assertEquals("an abort is not an error — no scary red copy", null, state.error)
        appScope.cancel()
    }
}
