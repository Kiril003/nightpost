package local.phantom.companion.core.voice.route

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.TtsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// ---------------------------------------------------------------------------
// Fake TtsEngine helpers
// ---------------------------------------------------------------------------

private class FakeTts(
    private val ready: Boolean,
    private val speakResult: Result<Unit> = Result.success(Unit),
) : TtsEngine {
    override val displayName: String = "fake-${if (ready) "ready" else "not-ready"}"
    var speakCallCount = 0
    var stopCallCount = 0

    override suspend fun isReady(): Boolean = ready

    override suspend fun speak(text: String, locale: String): Result<Unit> {
        speakCallCount++
        return speakResult
    }

    override fun stop() {
        stopCallCount++
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

@OptIn(ExperimentalCoroutinesApi::class)
class RoutedTtsTest {

    // Покроковий дзвінок PHANTOM питає саме живий голос: системний робот не рахується.
    @Test
    fun `premiumReady is about Supertonic only, not the system voice`() = runTest {
        assertFalse(RoutedTts(premium = FakeTts(ready = false), systemFallback = FakeTts(ready = true)).premiumReady())
        assertTrue(RoutedTts(premium = FakeTts(ready = true), systemFallback = FakeTts(ready = false)).premiumReady())
    }

    // 1. premium.isReady=true → speak routes to premium; system fallback NOT called.
    @Test
    fun `premium ready speaks via premium only`() = runTest {
        val premium = FakeTts(ready = true, speakResult = Result.success(Unit))
        val fallback = FakeTts(ready = true)
        val router = RoutedTts(premium = premium, systemFallback = fallback)

        val result = router.speak("Привіт")

        assertTrue(result.isSuccess)
        assertEquals(1, premium.speakCallCount)
        assertEquals(0, fallback.speakCallCount)
    }

    // 2. premium.isReady=true but speak() fails → fallback is called, returns its result.
    @Test
    fun `premium ready but speak fails routes to fallback`() = runTest {
        val premium = FakeTts(
            ready = true,
            speakResult = Result.failure(RuntimeException("ONNX crash")),
        )
        val fallback = FakeTts(ready = true, speakResult = Result.success(Unit))
        val router = RoutedTts(premium = premium, systemFallback = fallback)

        val result = router.speak("Привіт")

        assertTrue(result.isSuccess)
        assertEquals(1, premium.speakCallCount)
        assertEquals(1, fallback.speakCallCount)
    }

    // 3. premium.isReady=false → directly uses system fallback without calling premium.speak.
    @Test
    fun `premium not ready skips to fallback`() = runTest {
        val premium = FakeTts(ready = false)
        val fallback = FakeTts(ready = true, speakResult = Result.success(Unit))
        val router = RoutedTts(premium = premium, systemFallback = fallback)

        val result = router.speak("Вогонь")

        assertTrue(result.isSuccess)
        assertEquals(0, premium.speakCallCount)
        assertEquals(1, fallback.speakCallCount)
    }

    // 4. Neither premium nor fallback ready → isReady=false; speak returns failure.
    @Test
    fun `neither engine ready means isReady false and speak fails`() = runTest {
        val premium = FakeTts(
            ready = false,
            speakResult = Result.failure(IllegalStateException("not ready")),
        )
        val fallback = FakeTts(
            ready = false,
            speakResult = Result.failure(IllegalStateException("not ready")),
        )
        val router = RoutedTts(premium = premium, systemFallback = fallback)

        assertFalse(router.isReady())

        val result = router.speak("тест")
        assertTrue(result.isFailure)
    }

    // 5. stop() calls stop on both engines.
    @Test
    fun `stop invokes stop on both engines`() = runTest {
        val premium = FakeTts(ready = true)
        val fallback = FakeTts(ready = true)
        val router = RoutedTts(premium = premium, systemFallback = fallback)

        router.stop()

        assertEquals(1, premium.stopCallCount)
        assertEquals(1, fallback.stopCallCount)
    }
}
