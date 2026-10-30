package local.phantom.companion.core.voice.route

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.audio.PcmSink
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import local.phantom.companion.core.voice.supertonic.SupertonicSynth
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HIGHWAY / voice — pins which [RoutedTts] entry points are allowed to mark
 * an utterance as navigation. [RoutedTts.speak] is what every chat call in
 * the app uses; it must never touch the nav channel. [RoutedTts.speakUrgent]
 * and [RoutedTts.speakNavigation] are fed exclusively by
 * [local.phantom.companion.core.nav.NavCueSpeaker] and must always mark it.
 */
class RoutedTtsNavigationChannelTest {

    private class SilentSynth : SupertonicSynth {
        override val sampleRate = 1000
        override val isLoaded = true
        override fun load() = Unit
        override fun synthesize(text: String, lang: String) = FloatArray(1)
        override fun close() = Unit
    }

    private class NoopSink : PcmSink {
        override fun start(sampleRate: Int) = Unit
        override fun write(samples: FloatArray, offset: Int, length: Int) = Unit
        override fun awaitDrained() = Unit
        override fun flush() = Unit
        override fun release() = Unit
    }

    private class StubTts(private val ready: Boolean = true) : TtsEngine {
        override val displayName = "stub"
        var lastIsNavigation: Boolean? = null
        override suspend fun isReady() = ready
        override suspend fun speak(text: String, locale: String): Result<Unit> {
            lastIsNavigation = false
            return Result.success(Unit)
        }
        override fun stop() = Unit
    }

    private fun premiumWithCapture(flagsSeen: MutableList<Boolean>): SupertonicRoute {
        val player = PcmStreamPlayer { isNavigation -> flagsSeen += isNavigation; NoopSink() }
        return SupertonicRoute(SilentSynth(), player)
    }

    @Test
    fun `speak never marks the premium engine's output as navigation`() = runTest {
        val flagsSeen = mutableListOf<Boolean>()
        val router = RoutedTts(premium = premiumWithCapture(flagsSeen), systemFallback = StubTts())

        router.speak("як справи")

        assertEquals(listOf(false), flagsSeen)
    }

    @Test
    fun `speakUrgent marks the premium engine's output as navigation`() = runTest {
        val flagsSeen = mutableListOf<Boolean>()
        val premium = premiumWithCapture(flagsSeen)
        // Cold Supertonic never counts as fast enough for speakUrgent, so
        // this drive falls to systemFallback for the actual utterance —
        // premium is asked only via the guaranteed last-resort branch,
        // which is exactly the one that must still mark isNavigation=true.
        val router = RoutedTts(premium = premium, systemFallback = StubTts(ready = false))

        router.speakUrgent("через 200 метрів праворуч")

        assertEquals(listOf(true), flagsSeen)
    }

    @Test
    fun `speakNavigation marks the premium engine's output as navigation`() = runTest {
        val flagsSeen = mutableListOf<Boolean>()
        val router = RoutedTts(premium = premiumWithCapture(flagsSeen), systemFallback = StubTts())

        router.speakNavigation("попереду камера")

        assertEquals(listOf(true), flagsSeen)
    }
}
