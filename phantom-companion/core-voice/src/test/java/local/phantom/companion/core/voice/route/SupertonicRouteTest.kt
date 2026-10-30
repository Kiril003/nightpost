package local.phantom.companion.core.voice.route

import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.voice.audio.PcmSink
import local.phantom.companion.core.voice.audio.PcmStreamPlayer
import local.phantom.companion.core.voice.supertonic.PhraseChunker
import local.phantom.companion.core.voice.supertonic.SupertonicSynth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

internal class RecordingSink : PcmSink {
    val written = CopyOnWriteArrayList<FloatArray>()
    var startedRate = 0
    override fun start(sampleRate: Int) { startedRate = sampleRate }
    override fun write(samples: FloatArray, offset: Int, length: Int) {
        written.add(samples.copyOfRange(offset, offset + length))
    }
    override fun awaitDrained() = Unit
    override fun flush() = Unit
    override fun release() = Unit
}

/** Each segment becomes PCM [segmentIndex+1 samples of value index+1]. */
internal class FakeSynth(
    private val failLoad: Boolean = false,
) : SupertonicSynth {
    val synthesized = CopyOnWriteArrayList<Pair<String, String>>()
    private var loaded = false
    override val sampleRate = 1000
    override val isLoaded get() = loaded
    // Підробка мусить моделювати те саме, що справжній синт: `canLoad` — це
    // «файли на місці», тобто load() удасться. failLoad = зіпсуті/відсутні асети.
    override val canLoad get() = loaded || !failLoad
    override fun load() {
        if (failLoad) throw IllegalStateException("assets missing")
        loaded = true
    }
    override fun synthesize(text: String, lang: String): FloatArray {
        synthesized.add(text to lang)
        val n = synthesized.size
        return FloatArray(n) { n.toFloat() }
    }
    override fun close() { loaded = false }
}

class SupertonicRouteTest {

    @Test
    fun `ORT sessions are released after the voice goes idle`() = kotlinx.coroutines.runBlocking {
        val synth = FakeSynth()
        val route = SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() }, idleReleaseMs = 50L)
        route.speak("привіт", "uk-UA")
        assertTrue("одразу після фрази рушій ще теплий", synth.isLoaded)
        val deadline = System.currentTimeMillis() + 3_000
        while (synth.isLoaded && System.currentTimeMillis() < deadline) kotlinx.coroutines.delay(20)
        assertFalse("сотні МБ ORT не тримаються між фразами", synth.isLoaded)
    }

    @Test
    fun `release frees the engine now and the next phrase reloads it`() = runTest {
        val synth = FakeSynth()
        val route = SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() })
        route.speak("раз", "uk-UA")
        route.release()
        assertFalse(synth.isLoaded)
        assertTrue(route.speak("два", "uk-UA").isSuccess)
    }

    @Test
    fun `speaks all segments in order with inter-segment silence`() = runTest {
        val sink = RecordingSink()
        val synth = FakeSynth()
        val route = SupertonicRoute(
            synth, PcmStreamPlayer { sink },
            chunker = PhraseChunker(maxChars = 30, rampLimits = listOf(15)),
            // Контракт змінено 31.08: пауза стала лямбдою, бо людина крутить
            // її посеред мовлення й наступна фраза мусить слухатись. Сторожа
            // ПЕРЕПИСАНО під новий контракт, а не знято — він і далі стереже
            // те саме: що пауза справді вставляється між сегментами.
            interSegmentSilenceMs = { 100 },
        )
        val result = route.speak("Перша фраза тут. Друга фраза, довша і з комою всередині.", "uk-UA")
        assertTrue(result.isSuccess)
        assertTrue("expected pipelined multi-segment synthesis", synth.synthesized.size >= 2)
        assertEquals(1000, sink.startedRate)
        // silence chunks: 100ms at 1000 Hz = 100 zero samples between segments
        val silences = sink.written.filter { chunk -> chunk.all { it == 0f } && chunk.size == 100 }
        assertEquals(synth.synthesized.size - 1, silences.size)
        // first audible chunk is segment 1's PCM (value 1f) — order preserved
        val audible = sink.written.filter { chunk -> chunk.any { it != 0f } }
        assertEquals(synth.synthesized.size, audible.size)
        assertEquals(1f, audible.first().first())
    }

    @Test
    fun `locale is reduced to bare language tag`() = runTest {
        val synth = FakeSynth()
        val route = SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() })
        route.speak("привіт", "uk-UA")
        assertEquals("uk", synth.synthesized.single().second)
    }

    @Test
    fun `load failure surfaces as Result failure for RoutedTts fallback`() = runTest {
        val route = SupertonicRoute(FakeSynth(failLoad = true), PcmStreamPlayer { RecordingSink() })
        assertFalse(route.isReady())
        assertTrue(route.speak("привіт", "uk-UA").isFailure)
    }

    @Test
    fun `blank text succeeds without touching the synth`() = runTest {
        val synth = FakeSynth()
        val route = SupertonicRoute(synth, PcmStreamPlayer { RecordingSink() })
        assertTrue(route.speak("   ", "uk-UA").isSuccess)
        assertEquals(0, synth.synthesized.size)
    }

    // HIGHWAY / voice — the nav-guidance channel must reach the sink only
    // when a caller asks for it by name; a plain two-arg speak() (every
    // chat call in the app) must never carry it.
    @Test
    fun `isNavigation only reaches the sink on the nav-aware overload`() = runTest {
        val flagsSeen = mutableListOf<Boolean>()
        val player = PcmStreamPlayer { isNavigation -> flagsSeen += isNavigation; RecordingSink() }
        val route = SupertonicRoute(FakeSynth(), player)

        route.speak("привіт", "uk-UA")
        assertEquals(listOf(false), flagsSeen)

        route.speak("поверніть праворуч", "uk-UA", isNavigation = true)
        assertEquals(listOf(false, true), flagsSeen)
    }
}
