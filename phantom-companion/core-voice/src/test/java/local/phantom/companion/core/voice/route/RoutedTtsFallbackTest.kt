package local.phantom.companion.core.voice.route

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.TtsEngine
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Що вирішує, який голос звучить, після зняття ліцензійного замка: **готовність
 * двигуна, і більше нічого**. Раніше тут перевірявся ключ; VoicePremium пішов у
 * вільний ярус, і разом із ним зникла єдина причина мовчати при робочому синтезі.
 *
 * Ці випадки лишились із тієї доби і стосуються не замка, а падіння: живий голос
 * може бути незавантажений, а маневр однаково мусить прозвучати.
 *
 * Друга половина — про слід. 03.09 тривога прозвучала системним роботом, і
 * ніхто не міг сказати чому: маршрутизатор підміняв голос мовчки. Тепер кожна
 * підміна — значення в [RoutedTts.lastFallback] і подія в [RoutedTts.fallbacks].
 *
 * Третя — про час. Слід був липкий: [RoutedTts.note] писав його, і ніхто не
 * стирав, тож один відступ робив плашку вічною й «відступав годину тому»
 * виглядало як «відступає зараз». Тепер живий голос, заговоривши, стирає слід.
 */
class RoutedTtsFallbackTest {

    private class FakeEngine(
        override val displayName: String,
        var ready: Boolean = true,
        private val succeeds: Boolean = true,
    ) : TtsEngine {
        var spokenCount = 0
        var readyChecks = 0

        override suspend fun isReady(): Boolean {
            readyChecks++
            return ready
        }

        override suspend fun speak(text: String, locale: String): Result<Unit> {
            spokenCount++
            return if (succeeds) Result.success(Unit) else Result.failure(IllegalStateException())
        }

        override fun stop() = Unit
    }

    private fun routed(premium: FakeEngine, system: FakeEngine) =
        RoutedTts(premium = premium, systemFallback = system)

    @Test
    fun `a ready premium engine is the one that speaks`() = runTest {
        val premium = FakeEngine("supertonic")
        val system = FakeEngine("android")
        val tts = routed(premium, system)

        val result = tts.speak("рухайся на північ", "uk-UA")

        assertTrue(result.isSuccess)
        assertEquals(1, premium.spokenCount)
        assertEquals("системний двигун заговорив поверх живого", 0, system.spokenCount)
        assertEquals(TtsRoute.Premium, tts.currentRoute.value)
    }

    @Test
    fun `an unloaded premium engine hands the sentence to the system voice`() = runTest {
        val premium = FakeEngine("supertonic", ready = false)
        val system = FakeEngine("android")

        val result = routed(premium, system).speak("рухайся на північ", "uk-UA")

        assertTrue("замовкли обидва голоси", result.isSuccess)
        assertEquals("незавантажений двигун усе одно намагався говорити", 0, premium.spokenCount)
        assertEquals(1, system.spokenCount)
    }

    @Test
    fun `a manoeuvre cue is spoken even when the live voice is not loaded`() = runTest {
        val premium = FakeEngine("supertonic", ready = false)
        val system = FakeEngine("android")
        val tts = routed(premium, system)

        val result = tts.speakUrgent("через сто метрів праворуч", "uk-UA")

        assertTrue("маневр не прозвучав", result.isSuccess)
        assertEquals(TtsRoute.System, tts.currentRoute.value)
    }

    @Test
    fun `when both engines are down the route says None instead of pretending`() = runTest {
        val premium = FakeEngine("supertonic", ready = false, succeeds = false)
        val system = FakeEngine("android", ready = false, succeeds = false)
        val tts = routed(premium, system)

        val result = tts.speakUrgent("праворуч", "uk-UA")

        assertTrue(result.isFailure)
        assertEquals(TtsRoute.None, tts.currentRoute.value)
    }

    @Test
    fun `the route label tells the truth about which voice is live`() = runTest {
        val live = routed(FakeEngine("supertonic"), FakeEngine("android"))
        live.refreshPremiumStatus()
        assertEquals(TtsRoute.Premium, live.currentRoute.value)

        val degraded = routed(FakeEngine("supertonic", ready = false), FakeEngine("android"))
        degraded.refreshPremiumStatus()
        assertEquals(TtsRoute.System, degraded.currentRoute.value)
    }

    @Test
    fun `an alert read by the system voice says why the live one did not`() = runTest {
        val tts = routed(FakeEngine("supertonic", ready = false), FakeEngine("android"))

        tts.speakNavigation("Повітряна тривога. Київська область.", "uk-UA")

        val handOff = tts.lastFallback.value
            ?: fail("системний голос заговорив, а причини ніхто не записав") as Nothing
        assertEquals(VoiceFallback.Reason.LIVE_NOT_LOADED, handOff.reason)
        assertEquals("Повітряна тривога. Київська область.", handOff.text)
    }

    @Test
    fun `a live voice that breaks on the line is reported as failed, not as absent`() = runTest {
        val tts = routed(FakeEngine("supertonic", succeeds = false), FakeEngine("android"))

        tts.speakNavigation("рухайся на північ", "uk-UA")

        assertEquals(VoiceFallback.Reason.LIVE_FAILED, tts.lastFallback.value?.reason)
    }

    @Test
    fun `живий голос, що заговорив, стирає слід відступу`() = runTest {
        val premium = FakeEngine("supertonic", ready = false)
        val tts = routed(premium, FakeEngine("android"))

        tts.speakNavigation("Повітряна тривога. Київська область.", "uk-UA")
        assertEquals(VoiceFallback.Reason.LIVE_NOT_LOADED, tts.lastFallback.value?.reason)

        premium.ready = true
        tts.speak("як справи", "uk-UA")

        assertNull("плашка каже «зараз робот», коли зараз говорить живий", tts.lastFallback.value)
    }

    @Test
    fun `новий відступ після успіху знову видно`() = runTest {
        val premium = FakeEngine("supertonic", ready = false)
        val tts = routed(premium, FakeEngine("android"))

        tts.speakNavigation("перша", "uk-UA")
        premium.ready = true
        tts.speak("друга", "uk-UA")
        premium.ready = false
        tts.speakNavigation("третя", "uk-UA")

        val miss = tts.lastFallback.value
            ?: fail("успіх стер слід назавжди — наступний відступ нікому показати") as Nothing
        assertEquals(VoiceFallback.Reason.LIVE_NOT_LOADED, miss.reason)
        assertEquals("третя", miss.text)
    }

    /**
     * Жива розмова говорить не через [RoutedTts.speak], а шматками через
     * потік, — тому сюди підставлено справжній [SupertonicRoute], а не фейк:
     * інакше довелось би вірити, що обгортка десь викликається.
     */
    @Test
    fun `промовлений шматок живої розмови теж стирає слід відступу`() = runTest {
        val tts = RoutedTts(
            premium = SupertonicRoute(FakeSynth(), PcmStreamPlayer { RecordingSink() }),
            systemFallback = FakeEngine("android"),
        )

        // Маневр іде системному: живий завантажений, але швидкості ще не довів.
        tts.speakUrgent("через сто метрів праворуч", "uk-UA")
        assertEquals(VoiceFallback.Reason.LIVE_UNPROVEN, tts.lastFallback.value?.reason)

        val span = tts.openStream("uk-UA") ?: fail("потік живого голосу не відкрився") as Nothing
        assertTrue("живий голос не промовив шматок", span.say("привіт"))

        assertNull("жива розмова триває, а плашка досі показує відступ", tts.lastFallback.value)
    }

    @Test
    fun `the live voice speaking leaves no hand-off behind`() = runTest {
        val tts = routed(FakeEngine("supertonic"), FakeEngine("android"))

        tts.speakNavigation("рухайся на північ", "uk-UA")
        tts.speak("як справи", "uk-UA")

        assertNull(tts.lastFallback.value)
    }

    @Test
    fun `a manoeuvre given to the system voice for speed is reported as unproven`() = runTest {
        val tts = routed(FakeEngine("supertonic"), FakeEngine("android"))

        tts.speakUrgent("через сто метрів праворуч", "uk-UA")

        assertEquals(TtsRoute.System, tts.currentRoute.value)
        assertEquals(VoiceFallback.Reason.LIVE_UNPROVEN, tts.lastFallback.value?.reason)
    }

    @Test
    fun `silence is reported as silence`() = runTest {
        val tts = routed(
            FakeEngine("supertonic", ready = false, succeeds = false),
            FakeEngine("android", ready = false, succeeds = false),
        )

        tts.speakNavigation("праворуч", "uk-UA")

        assertEquals(VoiceFallback.Reason.NO_VOICE, tts.lastFallback.value?.reason)
    }

    @Test
    fun `every hand-off reaches whoever listens, in order`() = runTest {
        val tts = routed(FakeEngine("supertonic", ready = false), FakeEngine("android"))
        val seen = mutableListOf<VoiceFallback>()
        val listener = launch(UnconfinedTestDispatcher(testScheduler)) {
            tts.fallbacks.collect { seen += it }
        }

        tts.speak("перша", "uk-UA")
        tts.speakNavigation("друга", "uk-UA")

        assertEquals(listOf("перша", "друга"), seen.map { it.text })
        listener.cancel()
    }
}
