package local.phantom.companion.core.voice.route

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 19.10, власник: «чому фантом має роботизований системний голос». Холодний
 * Supertonic ніколи не доводив швидкості, тож кожна термінова фраза йшла
 * системному роботу. Один прогрів після першого кадру — і живий голос доведений.
 */
class RoutedTtsWarmUpTest {

    private class SystemVoice : TtsEngine {
        override val displayName = "android"
        var spoken = 0
        override suspend fun isReady() = true
        override suspend fun speak(text: String, locale: String): Result<Unit> {
            spoken++
            return Result.success(Unit)
        }
        override fun stop() = Unit
    }

    private fun live(synth: FakeSynth = FakeSynth()) = SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() })

    @Test
    fun `after the warm-up an urgent line is spoken by Supertonic, not the system voice`() = runTest {
        val system = SystemVoice()
        val tts = RoutedTts(premium = live(), systemFallback = system)

        assertTrue("прогрів не вдався", tts.warmUp())
        tts.speakUrgent("через сто метрів праворуч", "uk-UA")

        assertEquals(0, system.spoken)
        assertEquals(TtsRoute.Premium, tts.currentRoute.value)
        assertNull(tts.lastFallback.value)
    }

    @Test
    fun `warm-up marks the live voice as the current route`() = runTest {
        val tts = RoutedTts(premium = live(), systemFallback = SystemVoice())

        tts.warmUp()

        assertEquals(TtsRoute.Premium, tts.currentRoute.value)
    }

    @Test
    fun `without Supertonic files the warm-up says no and speaks nothing`() = runTest {
        val system = SystemVoice()
        val tts = RoutedTts(premium = live(FakeSynth(failLoad = true)), systemFallback = system)

        assertFalse(tts.warmUp())
        assertEquals(0, system.spoken)
    }

    @Test
    fun `an assistant reply goes to Supertonic whenever its files are there`() = runTest {
        val system = SystemVoice()
        val tts = RoutedTts(premium = live(), systemFallback = system)

        tts.speak("Так, на звʼязку.", "uk-UA")

        assertEquals(0, system.spoken)
        assertEquals(TtsRoute.Premium, tts.currentRoute.value)
    }

    @Test
    fun `a released engine is cold again, so the turn cue does not wait for a reload`() = runTest {
        val system = SystemVoice()
        val supertonic = live()
        val tts = RoutedTts(premium = supertonic, systemFallback = system)

        tts.warmUp()
        supertonic.release()
        tts.speakUrgent("праворуч", "uk-UA")

        assertEquals(1, system.spoken)
    }
}
