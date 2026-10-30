package local.phantom.companion.core.voice.route

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import local.phantom.companion.core.voice.supertonic.SupertonicSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Питання про готовність не сміє коштувати 398 МБ.
 *
 * `SupertonicRoute.isReady()` робило `if (!synth.isLoaded) synth.load()`, а
 * `load()` створює чотири сесії ONNX: vector_estimator 256 МБ, vocoder 101 МБ,
 * text_encoder 36 МБ, duration_predictor 3,7 МБ. `ContainerBoot` кличе
 * `RoutedTts.refreshPremiumStatus()` на старті — отже ~400 МБ нативної купи
 * бралися ДО першого екрана. «Очі» зміряли на релізі 796 МБ PSS з 690 МБ
 * нативної купи на екрані ПІНу; на апараті з 2-3 ГБ це смерть від
 * lowmemorykiller ще до мапи.
 *
 * Ваги мусить піднімати мовлення, а не запитання про нього.
 */
class ReadinessIsNotLoadingTest {

    private class CountingSynth(private val present: Boolean) : SupertonicSynth {
        var loads = 0
        private var loaded = false
        override val sampleRate = 1000
        override val isLoaded get() = loaded
        override val canLoad get() = loaded || present
        override fun load() { loads++; loaded = true }
        override fun synthesize(text: String, lang: String) = FloatArray(1)
        override fun close() { loaded = false }
    }

    // RecordingSink — уже наявна підробка з SupertonicRouteTest у цьому ж пакеті.
    private fun route(synth: SupertonicSynth) =
        SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() })

    @Test
    fun `isReady не вантажить ваги`() = runTest {
        val synth = CountingSynth(present = true)
        assertTrue("готовність мусить бути true, коли файли на місці", route(synth).isReady())
        assertEquals("isReady завантажило модель — це 398 МБ на старті", 0, synth.loads)
        assertFalse("модель не мусить бути завантаженою після простого питання", synth.isLoaded)
    }

    @Test
    fun `без файлів готовність чесно false і теж без завантаження`() = runTest {
        val synth = CountingSynth(present = false)
        assertFalse(route(synth).isReady())
        assertEquals(0, synth.loads)
    }
}
