package local.phantom.companion.core.resilience

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.route.GeminiCloudRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Аудит №7: без мережі власний ключ казав «модель промовчала» — порожній Final
 * із черги офлайн-запитів закривав ланцюг, і правила телефона не діставали ходу.
 */
class ResumableCloudOfflineTest {

    @Test
    fun `no network ends in NoNetwork, never in the stream's offline queue`() = runTest {
        var queued = 0
        val cloud = GeminiCloudRoute(
            apiKeyProvider = { "k" },
            httpClient = HttpClient(MockEngine { throw java.io.IOException("offline") }),
            offlineQueueSink = { queued++ },
        )
        val route = ResumableCloud(cloud, StreamResumer(RetryPolicy(maxAttempts = 1, baseDelayMs = 1), delayProvider = {}))

        val out = route.complete(AIIntent.Text("де укриття")).toList()

        assertEquals(0, queued)
        val err = out.single() as AIChunk.ErrorChunk
        assertTrue("${err.cause}", err.cause is AIError.NoNetwork)
    }
}
