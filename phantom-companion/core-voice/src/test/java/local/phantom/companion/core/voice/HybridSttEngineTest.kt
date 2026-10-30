package local.phantom.companion.core.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HybridSttEngineTest {

    private class FakeStt(
        override val displayName: String,
        private val transcribeResult: String? = null,
        private val drainResult: String? = null,
    ) : SttEngine {
        private val _partialResults = MutableSharedFlow<String>(extraBufferCapacity = 1)
        override val partialResults = _partialResults.asSharedFlow()

        private val _finalResults = MutableSharedFlow<String>(extraBufferCapacity = 1)
        override val finalResults = _finalResults.asSharedFlow()

        var feedCount = 0
        var resetCount = 0
        var transcribeCount = 0

        override fun feed(chunk: ByteArray) {
            feedCount++
        }

        suspend fun emitPartial(text: String) = _partialResults.emit(text)
        suspend fun emitFinal(text: String) = _finalResults.emit(text)

        override suspend fun transcribe(audio: ByteArray): String? {
            transcribeCount++
            return transcribeResult
        }

        override suspend fun drain(): String? = drainResult

        override fun reset() {
            resetCount++
        }

        override fun release() {}
    }

    @Test
    fun `collects partials from vosk`() = runTest {
        val vosk = FakeStt("vosk")
        val accurate = FakeStt("accurate")
        val hybrid = HybridSttEngine(vosk, accurate, this)

        launch { vosk.emitPartial("при") }
        assertEquals("при", hybrid.partialResults.first())

        hybrid.release()
    }

    @Test
    fun `vosk finals are not forwarded — finalize is the single emission point`() = runTest {
        // Forwarding Vosk's silence-detected finals gave every consumer of
        // finalResults a second (fragment) transcript per utterance — each
        // one submitted a paid AI turn. finalize() alone emits finals now.
        // Сегменти всередині репліки накопичує сам розпізнавач, під своїм
        // замком (VoskSttEngine.drain) — тут дублер просто віддає їх разом.
        val vosk = FakeStt("vosk", drainResult = "перевір маршрут")
        val hybrid = HybridSttEngine(vosk, null, this)

        val received = mutableListOf<String>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            hybrid.finalResults.collect { received += it }
        }

        vosk.emitFinal("перевір")
        assertEquals("vosk final must not pass through", emptyList<String>(), received)

        val result = hybrid.finalize()
        assertEquals("перевір маршрут", result)
        assertEquals(listOf("перевір маршрут"), received)

        collector.cancel()
        hybrid.release()
    }

    /**
     * Живий діалог тримається на цьому: закриття репліки не має коштувати
     * повторного розбору всього сказаного. Якщо цей тест колись почне
     * дозволяти transcribe() на гарячому шляху — повернеться пауза, лінійна
     * від довжини фрази.
     */
    @Test
    fun `finalize drains the running recognizer instead of re-decoding the buffer`() = runTest {
        val vosk = FakeStt("vosk", transcribeResult = "переслухано з нуля", drainResult = "злито")
        val hybrid = HybridSttEngine(vosk, null, this)

        hybrid.feed(ByteArray(32_000))
        val result = hybrid.finalize()

        assertEquals("злито", result)
        assertEquals("буфер не мав переслуховуватись", 0, vosk.transcribeCount)

        hybrid.release()
    }

    @Test
    fun `the accurate engine is off the hot path`() = runTest {
        val vosk = FakeStt("vosk", drainResult = "від воска")
        val accurate = FakeStt("accurate", transcribeResult = "від точного")
        val hybrid = HybridSttEngine(vosk, accurate, this)

        assertEquals("від воска", hybrid.finalize())
        assertEquals(0, accurate.transcribeCount)

        hybrid.release()
    }

    @Test
    fun `a non-streaming engine still closes an utterance`() = runTest {
        val vosk = FakeStt("fake", transcribeResult = "canned")
        val hybrid = HybridSttEngine(vosk, null, this)

        assertEquals("canned", hybrid.finalize())
        assertTrue(vosk.transcribeCount > 0)

        hybrid.release()
    }
}
