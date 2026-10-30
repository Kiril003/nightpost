package local.phantom.companion.core.voice.live

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Машина станів дзвінка: зʼєднання, перебивання, перепідключення, кінець. */
class LiveCallSessionTest {

    class Rig(
        val session: LiveCallSession,
        val net: FakeConnector,
        val speaker: FakeSpeaker,
        val hands: RecordingHands,
        val saved: MutableList<List<CallTranscript.Line>>,
        val minted: () -> Int,
    )

    private fun TestScope.rig(refuse: Boolean = false, maxReconnects: Int = 3): Rig {
        val now = { testScheduler.currentTime }
        var n = 0
        val src = LiveTokenSource { at ->
            if (refuse) Mint.Refused("на компʼютері не задано ключ Gemini")
            else Mint.Got(LiveToken("tok-${++n}", "gemini-3.8-live", at + TOKEN_TTL_MS, at + START_WINDOW_MS, LiveToken.Source.PC))
        }
        val net = FakeConnector()
        val speaker = FakeSpeaker()
        val hands = RecordingHands()
        val saved = mutableListOf<List<CallTranscript.Line>>()
        val session = LiveCallSession(
            scope = backgroundScope,
            tokens = LiveTokens(listOf(src), now),
            connector = net,
            speaker = speaker,
            bridge = LiveToolBridge(hands, ConfirmGate(now)),
            persona = LivePersona.text("Сестру звати Марта.", "uk"),
            transcript = CallTranscript(now),
            sink = { saved += it },
            now = now,
            maxReconnects = maxReconnects,
        )
        return Rig(session, net, speaker, hands, saved) { n }
    }

    private fun TestScope.up(r: Rig) {
        r.session.start()
        runCurrent()
        r.net.last.open()
        runCurrent()
        r.net.last.server(Frames.SETUP_OK)
        runCurrent()
    }

    @Test
    fun `зʼєднання — токен у адресі, setup з персоною й руками, звук лише після setupComplete`() = runTest {
        val r = rig()
        r.session.start()
        runCurrent()
        assertEquals(CallPhase.CONNECTING, r.session.state.value.phase)
        val sock = r.net.last
        assertTrue(sock.url.startsWith(LiveProtocol.WS_URL))
        assertTrue(sock.url.endsWith("access_token=tok-1"))
        r.session.mic(pcm(true))
        assertEquals("до відкриття сокета звук не йде", 0, sock.audioFrames())
        sock.open()
        runCurrent()
        val setup = sock.setups().single()
        assertTrue("models/gemini-3.8-live" in setup)
        assertTrue("Сестру звати Марта." in setup)
        assertTrue(LiveToolBridge.SEND in setup && LiveToolBridge.RECALL in setup)
        assertTrue("\"AUDIO\"" in setup && "inputAudioTranscription" in setup)
        r.session.mic(pcm(true))
        assertEquals("до setupComplete звук не йде", 0, sock.audioFrames())
        sock.server(Frames.SETUP_OK)
        runCurrent()
        assertEquals(CallPhase.LISTENING, r.session.state.value.phase)
        r.session.mic(pcm(true))
        assertEquals(1, sock.audioFrames())
        assertTrue(sock.sent.last().contains("audio/pcm;rate=16000"))
    }

    @Test
    fun `немає токена — дзвінок чесно недоступний, і сокета не відкривали`() = runTest {
        val r = rig(refuse = true)
        r.session.start()
        runCurrent()
        val st = r.session.state.value
        assertEquals(CallPhase.ENDED, st.phase)
        assertEquals(CallEnd.Unavailable("на компʼютері не задано ключ Gemini"), st.end)
        assertTrue(r.net.sockets.isEmpty())
    }

    @Test
    fun `перебивання — сервер каже interrupted, звук гасне негайно, PHANTOM слухає`() = runTest {
        val r = rig()
        up(r)
        val sock = r.net.last
        sock.server(Frames.said("Зараз розповім довго"))
        sock.server(Frames.audio())
        runCurrent()
        assertEquals(CallPhase.SPEAKING, r.session.state.value.phase)
        // Людина заговорила — локальний край мови стишує PHANTOM ще до сервера.
        r.session.mic(pcm(true))
        runCurrent()
        assertEquals("duck:true", r.speaker.log.last())
        sock.server(Frames.INTERRUPTED)
        runCurrent()
        assertTrue("flush" in r.speaker.log.takeLast(2))
        assertEquals(CallPhase.LISTENING, r.session.state.value.phase)
        // Мікрофон не замовкав увесь час — сервер мусить чути перебивання.
        assertTrue(sock.audioFrames() >= 1)
        r.session.hangUp()
        runCurrent()
        assertTrue("обірвана репліка позначена", r.saved.single().single { it.who == CallTranscript.Who.PHANTOM }.cut)
    }

    @Test
    fun `goAway — новий одноразовий токен і продовження сесії за handle`() = runTest {
        val r = rig()
        up(r)
        val first = r.net.last
        first.server(Frames.resumable("h-42"))
        first.server(Frames.goAway())
        runCurrent()
        assertTrue(first.closed)
        assertEquals(CallPhase.RECONNECTING, r.session.state.value.phase)
        assertEquals(2, r.minted())
        val second = r.net.last
        assertTrue(second.url.endsWith("tok-2"))
        second.open()
        runCurrent()
        assertTrue("\"handle\":\"h-42\"" in second.setups().single())
        // Пізні кадри старого сокета нічого не міняють.
        first.server(Frames.audio())
        runCurrent()
        assertEquals(0, r.speaker.playedBytes)
        second.server(Frames.SETUP_OK)
        runCurrent()
        assertEquals(CallPhase.LISTENING, r.session.state.value.phase)
    }

    @Test
    fun `обрив після підйому — перепідключення з паузою, понад межу — Lost`() = runTest {
        val r = rig(maxReconnects = 2)
        up(r)
        r.net.last.listener.onFailure("мережа впала")
        runCurrent()
        assertEquals(CallPhase.RECONNECTING, r.session.state.value.phase)
        assertEquals(1, r.net.sockets.size)
        advanceTimeBy(401)
        runCurrent()
        assertEquals(2, r.net.sockets.size)
        r.net.last.listener.onFailure("знову")
        runCurrent()
        advanceTimeBy(500)
        assertEquals("друга пауза довша", 2, r.net.sockets.size)
        advanceTimeBy(400)
        runCurrent()
        assertEquals(3, r.net.sockets.size)
        r.net.last.listener.onFailure("і знову")
        runCurrent()
        assertEquals(CallEnd.Lost("і знову"), r.session.state.value.end)
    }

    @Test
    fun `обрив до першого setupComplete — недоступно, а не нескінченні спроби`() = runTest {
        val r = rig()
        r.session.start()
        runCurrent()
        r.net.last.listener.onFailure("403")
        runCurrent()
        assertEquals(CallEnd.Unavailable("403"), r.session.state.value.end)
        assertEquals(1, r.net.sockets.size)
    }

    @Test
    fun `кінець — сокет закрито, звук погашено, мікрофон більше не шле`() = runTest {
        val r = rig()
        up(r)
        r.session.hangUp()
        runCurrent()
        val st = r.session.state.value
        assertEquals(CallPhase.ENDED, st.phase)
        assertEquals(CallEnd.HungUp, st.end)
        assertTrue(r.net.last.closed)
        assertEquals("flush", r.speaker.log.last())
        val before = r.net.last.sent.size
        r.session.mic(pcm(true))
        assertEquals(before, r.net.last.sent.size)
        assertTrue("порожній дзвінок не пише в чат", r.saved.isEmpty())
    }

    @Test
    fun `вимкнений мікрофон — нічого не йде на сервер`() = runTest {
        val r = rig()
        up(r)
        r.session.setMuted(true)
        r.session.mic(pcm(true))
        assertEquals(0, r.net.last.audioFrames())
        assertTrue(r.session.state.value.muted)
        assertEquals(0f, r.session.level.value)
    }

    @Test
    fun `виклик руки — відповідь іде назад toolResponse, лист без згоди не йде`() = runTest {
        val r = rig()
        up(r)
        val sock = r.net.last
        sock.server(Frames.toolCall("c1", LiveToolBridge.SEND, """{"who":"Марта","text":"Запізнюсь"}"""))
        runCurrent()
        val reply = sock.sent.last()
        assertTrue(reply.startsWith("{\"toolResponse\""))
        assertTrue("awaiting_confirmation" in reply && "\"id\":\"c1\"" in reply)
        assertTrue(r.hands.sent.isEmpty())
        assertEquals("Кому: Марта. Текст: «Запізнюсь». Надсилати?", r.session.state.value.pending?.ask)

        // Людина торкнулась «Так» — модель дізнається про це словами людини.
        r.session.confirm(true)
        runCurrent()
        assertTrue("Так, підтверджую." in sock.sent.last())
        sock.server(Frames.toolCall("c2", LiveToolBridge.SEND, """{"who":"Марта","text":"Запізнюсь"}"""))
        runCurrent()
        assertEquals(listOf("peer_marta" to "Запізнюсь"), r.hands.sent)
        assertTrue("\"ok\":true" in sock.sent.last())
        assertNull(r.session.state.value.pending)
    }
}
