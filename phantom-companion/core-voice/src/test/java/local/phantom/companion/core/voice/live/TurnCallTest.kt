package local.phantom.companion.core.voice.live

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Покроковий дзвінок без живого голосу: утримав — сказав, відпустив — відповідь. */
class TurnCallTest {

    private class FakeEar(var heard: String?) : TurnCall.Ear {
        var started = 0
        var free = true
        override fun start(): Boolean {
            started++
            return free
        }

        override suspend fun stop(): String? = heard
    }

    private class FakeVoice(var isReady: Boolean) : TurnCall.Voice {
        val spoken = mutableListOf<String>()
        var stopped = 0
        var hold: CompletableDeferred<Unit>? = null
        override suspend fun ready() = isReady
        override suspend fun speak(text: String): Boolean {
            spoken += text
            hold?.await()
            return true
        }

        override fun stop() {
            stopped++
        }
    }

    @Test
    fun `утримав-сказав-відпустив — розум відповідає голосом Supertonic`() = runTest {
        val asked = mutableListOf<String>()
        val voice = FakeVoice(isReady = true)
        val call = TurnCall(backgroundScope, FakeEar("Котра година?"), { asked += it; TurnCall.Answer.Said("Пів на сьому.") }, voice)
        call.press()
        assertEquals(TurnCall.Phase.RECORDING, call.state.value.phase)
        call.release()
        runCurrent()
        assertEquals(listOf("Котра година?"), asked)
        assertEquals(listOf("Пів на сьому."), voice.spoken)
        val s = call.state.value
        assertEquals(TurnCall.Phase.IDLE, s.phase)
        assertEquals("Котра година?", s.owner)
        assertEquals("Пів на сьому.", s.phantom)
        assertFalse(s.textOnly)
    }

    @Test
    fun `голосу немає — чесна відповідь текстом`() = runTest {
        val voice = FakeVoice(isReady = false)
        val call = TurnCall(backgroundScope, FakeEar("привіт"), { TurnCall.Answer.Said("Привіт!") }, voice)
        call.press()
        call.release()
        runCurrent()
        assertTrue(voice.spoken.isEmpty())
        assertTrue(call.state.value.textOnly)
        assertEquals(TurnCall.NO_VOICE, call.state.value.note)
        assertEquals("Привіт!", call.state.value.phantom)
    }

    @Test
    fun `вузький режим — голос у текст, відповідь текстом, навіть коли голос є`() = runTest {
        val voice = FakeVoice(isReady = true)
        val call = TurnCall(backgroundScope, FakeEar("що нового"), { TurnCall.Answer.Said("Тихо.") }, voice, narrow = { true })
        call.press()
        call.release()
        runCurrent()
        assertTrue(voice.spoken.isEmpty())
        assertEquals(TurnCall.NARROW, call.state.value.note)
    }

    @Test
    fun `розуму немає — так і сказано, а не мовчання`() = runTest {
        val call = TurnCall(
            backgroundScope,
            FakeEar("порадь"),
            { TurnCall.Answer.None("Розуму зараз немає: ні компʼютера, ні хмари.") },
            FakeVoice(true),
        )
        call.press()
        call.release()
        runCurrent()
        assertEquals("Розуму зараз немає: ні компʼютера, ні хмари.", call.state.value.phantom)
        assertEquals(TurnCall.Phase.IDLE, call.state.value.phase)
    }

    @Test
    fun `нічого не розчув — розум не питається`() = runTest {
        var asked = 0
        val call = TurnCall(backgroundScope, FakeEar("  "), { asked++; TurnCall.Answer.Said("x") }, FakeVoice(true))
        call.press()
        call.release()
        runCurrent()
        assertEquals(0, asked)
        assertEquals(TurnCall.NOT_HEARD, call.state.value.note)
    }

    @Test
    fun `натиснув, поки PHANTOM говорить, — голос гасне, мікрофон слухає`() = runTest {
        val voice = FakeVoice(true).apply { hold = CompletableDeferred() }
        val ear = FakeEar("розкажи довго")
        val call = TurnCall(backgroundScope, ear, { TurnCall.Answer.Said("Довга історія…") }, voice)
        call.press()
        call.release()
        runCurrent()
        assertEquals(TurnCall.Phase.SPEAKING, call.state.value.phase)
        call.press()
        assertEquals(1, voice.stopped)
        assertEquals(2, ear.started)
        assertEquals(TurnCall.Phase.RECORDING, call.state.value.phase)
    }

    @Test
    fun `мікрофон зайнятий — сказано словами`() = runTest {
        val call = TurnCall(backgroundScope, FakeEar("x").apply { free = false }, { TurnCall.Answer.Said("y") }, FakeVoice(true))
        call.press()
        assertEquals(TurnCall.Phase.IDLE, call.state.value.phase)
        assertEquals(TurnCall.MIC_BUSY, call.state.value.note)
    }
}
