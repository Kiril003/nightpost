package local.phantom.companion.core.voice.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Бюджет: перший звук відповіді — не пізніше 800 мс від кінця мови людини.
 *
 * Сервер тут підроблений, тож міряється те, що належить телефону: від якого
 * моменту рахується «кінець мови» і чи віддає телефон звук у динамік з першим
 * же шматком, а не чекає кінця відповіді. Мережу й модель міряє апарат
 * (див. docs/live-call-device-plan.md).
 */
class LiveLatencyTest {

    private fun TestScope.session(net: FakeConnector, speaker: FakeSpeaker): LiveCallSession {
        val now = { testScheduler.currentTime }
        val src = LiveTokenSource { at ->
            Mint.Got(LiveToken("tok", "gemini-3.8-live", at + TOKEN_TTL_MS, at + START_WINDOW_MS, LiveToken.Source.PC))
        }
        return LiveCallSession(
            scope = backgroundScope,
            tokens = LiveTokens(listOf(src), now),
            connector = net,
            speaker = speaker,
            bridge = LiveToolBridge(RecordingHands(), ConfirmGate(now)),
            persona = LivePersona.text("", "uk"),
            transcript = CallTranscript(now),
            sink = {},
            now = now,
        )
    }

    /** Людина говорить [speakMs], потім мовчить [silentMs]; кадр — 20 мс. Повертає час останнього гучного кадру. */
    private fun TestScope.talk(s: LiveCallSession, speakMs: Long, silentMs: Long): Long {
        var lastLoud = 0L
        var t = 0L
        while (t < speakMs) {
            lastLoud = testScheduler.currentTime
            s.mic(pcm(true))
            advanceTimeBy(20)
            t += 20
        }
        t = 0
        while (t < silentMs) {
            s.mic(pcm(false))
            advanceTimeBy(20)
            t += 20
        }
        runCurrent()
        return lastLoud
    }

    private fun TestScope.up(s: LiveCallSession, net: FakeConnector) {
        s.start()
        runCurrent()
        net.last.open()
        runCurrent()
        net.last.server(Frames.SETUP_OK)
        runCurrent()
    }

    @Test
    fun `кінець мови рахується від останнього гучного кадру, перший звук — у бюджеті`() = runTest {
        val net = FakeConnector()
        val speaker = FakeSpeaker()
        val s = session(net, speaker)
        up(s, net)
        val lastLoud = talk(s, speakMs = 600, silentMs = 360)
        // Край мови вже спрацював (300 мс тиші) — PHANTOM «думає».
        assertEquals(CallPhase.THINKING, s.state.value.phase)
        // Сервер (модель + мережа) відповідає першим шматком на 520-й мс від кінця мови.
        advanceTimeBy(lastLoud + 520 - testScheduler.currentTime)
        net.last.server(Frames.audio())
        runCurrent()
        assertEquals(listOf(520L), s.latencies)
        assertTrue(s.latencies.single() <= LiveCallSession.FIRST_AUDIO_BUDGET_MS)
        assertEquals(CallPhase.SPEAKING, s.state.value.phase)
    }

    @Test
    fun `звук іде в динамік з першим шматком, а не після кінця відповіді`() = runTest {
        val net = FakeConnector()
        val speaker = FakeSpeaker()
        val s = session(net, speaker)
        up(s, net)
        talk(s, speakMs = 400, silentMs = 320)
        net.last.server(Frames.audio(bytes = 480))
        runCurrent()
        assertEquals("перший шматок уже грає", 480, speaker.playedBytes)
        repeat(9) {
            advanceTimeBy(40)
            net.last.server(Frames.audio(bytes = 480))
        }
        runCurrent()
        assertEquals(4800, speaker.playedBytes)
        assertEquals("відповідь ще не скінчилась", CallPhase.SPEAKING, s.state.value.phase)
        net.last.server(Frames.TURN_DONE)
        runCurrent()
        assertEquals(CallPhase.LISTENING, s.state.value.phase)
        assertEquals("один замір на одну відповідь", 1, s.latencies.size)
    }

    @Test
    fun `повільна відповідь записується як є — понад бюджет видно`() = runTest {
        val net = FakeConnector()
        val speaker = FakeSpeaker()
        val s = session(net, speaker)
        up(s, net)
        val lastLoud = talk(s, speakMs = 300, silentMs = 320)
        advanceTimeBy(lastLoud + 1_250 - testScheduler.currentTime)
        net.last.server(Frames.audio())
        runCurrent()
        assertEquals(1_250L, s.state.value.lastLatencyMs)
        assertTrue(s.state.value.lastLatencyMs!! > LiveCallSession.FIRST_AUDIO_BUDGET_MS)
    }

    @Test
    fun `людина заговорила знову — старий кінець мови не рахується`() = runTest {
        val net = FakeConnector()
        val speaker = FakeSpeaker()
        val s = session(net, speaker)
        up(s, net)
        talk(s, speakMs = 300, silentMs = 320)
        val lastLoud = talk(s, speakMs = 200, silentMs = 320)
        advanceTimeBy(lastLoud + 400 - testScheduler.currentTime)
        net.last.server(Frames.audio())
        runCurrent()
        assertEquals(listOf(400L), s.latencies)
    }
}
