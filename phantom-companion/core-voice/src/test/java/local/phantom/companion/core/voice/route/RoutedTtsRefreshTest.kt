package local.phantom.companion.core.voice.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * QH3 — pin the active-route StateFlow contract on RoutedTts. The
 * Settings indicator depends on this flow flipping the moment a
 * Supertonic download lands; if a future refactor inverts the
 * speak-vs-refresh interaction, the «голос: …» label silently goes
 * stale and operator can't tell whether the download was picked up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutedTtsRefreshTest {

    private class StubTts(
        var ready: Boolean,
        private val speakResult: Result<Unit> = Result.success(Unit),
    ) : TtsEngine {
        override val displayName: String = "stub-${if (ready) "ready" else "not-ready"}"
        override suspend fun isReady(): Boolean = ready
        override suspend fun speak(text: String, locale: String): Result<Unit> = speakResult
        override fun stop() = Unit
    }

    @Test
    fun `currentRoute starts at None until something refreshes it`() = runTest {
        val router = RoutedTts(
            premium = StubTts(ready = false),
            systemFallback = StubTts(ready = true),
        )
        assertEquals(TtsRoute.None, router.currentRoute.value)
    }

    @Test
    fun `refreshPremiumStatus flips to Premium when premium becomes ready`() = runTest {
        val premium = StubTts(ready = false)
        val router = RoutedTts(
            premium = premium,
            systemFallback = StubTts(ready = true),
        )
        router.refreshPremiumStatus()
        assertEquals(
            "system fallback should be advertised when premium not ready",
            TtsRoute.System,
            router.currentRoute.value,
        )

        // Simulates the Supertonic download landing on disk.
        premium.ready = true
        router.refreshPremiumStatus()
        assertEquals(
            "premium must take over once it advertises ready",
            TtsRoute.Premium,
            router.currentRoute.value,
        )
    }

    @Test
    fun `refreshPremiumStatus reports None when neither engine is ready`() = runTest {
        val router = RoutedTts(
            premium = StubTts(ready = false),
            systemFallback = StubTts(ready = false),
        )
        router.refreshPremiumStatus()
        assertEquals(TtsRoute.None, router.currentRoute.value)
    }

    @Test
    fun `successful premium speak updates currentRoute to Premium`() = runTest {
        val router = RoutedTts(
            premium = StubTts(ready = true),
            systemFallback = StubTts(ready = true),
        )
        router.speak("привіт")
        assertEquals(TtsRoute.Premium, router.currentRoute.value)
    }

    @Test
    fun `failed premium speak then successful fallback updates to System`() = runTest {
        val router = RoutedTts(
            premium = StubTts(ready = true, speakResult = Result.failure(RuntimeException("boom"))),
            systemFallback = StubTts(ready = true, speakResult = Result.success(Unit)),
        )
        router.speak("тест")
        assertEquals(TtsRoute.System, router.currentRoute.value)
    }

    @Test
    fun `both speaks failing updates to None`() = runTest {
        val router = RoutedTts(
            premium = StubTts(ready = true, speakResult = Result.failure(RuntimeException("a"))),
            systemFallback = StubTts(ready = true, speakResult = Result.failure(RuntimeException("b"))),
        )
        router.speak("тест")
        assertEquals(TtsRoute.None, router.currentRoute.value)
    }
}
