package local.phantom.companion.core.voice.live

import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Стенограма дзвінка і те, що після дзвінка вона лягає в розмову з PHANTOM. */
class CallTranscriptTest {

    @Test
    fun `шматки складаються в репліки по черзі мовців`() {
        var t = 0L
        val tr = CallTranscript { t }
        tr.owner(" Де ")
        tr.owner("найближча аптека ?")
        t = 900
        tr.phantom("Аптека за ")
        tr.phantom("триста метрів.")
        tr.closeTurn()
        t = 2_000
        tr.owner("Дякую")
        val lines = tr.lines()
        assertEquals(
            listOf(
                CallTranscript.Line(CallTranscript.Who.OWNER, "Де найближча аптека?", 0),
                CallTranscript.Line(CallTranscript.Who.PHANTOM, "Аптека за триста метрів.", 900),
                CallTranscript.Line(CallTranscript.Who.OWNER, "Дякую", 2_000),
            ),
            lines,
        )
        assertEquals("Аптека за триста метрів.", tr.last(CallTranscript.Who.PHANTOM))
    }

    @Test
    fun `перебита репліка позначена обірваною, наступна — ні`() {
        val tr = CallTranscript { 0 }
        tr.phantom("Почну здалеку")
        tr.interrupted()
        tr.owner("стоп")
        tr.phantom("Слухаю.")
        val lines = tr.lines()
        assertTrue(lines[0].cut)
        assertEquals(listOf(false, false), lines.drop(1).map { it.cut })
    }

    @Test
    fun `після дзвінка стенограма зберігається один раз і в порядку розмови`() = runTest {
        val saved = mutableListOf<List<CallTranscript.Line>>()
        val now = { testScheduler.currentTime }
        val net = FakeConnector()
        val s = LiveCallSession(
            scope = backgroundScope,
            tokens = LiveTokens(
                listOf(LiveTokenSource { at -> Mint.Got(LiveToken("t", "m", at + TOKEN_TTL_MS, at + START_WINDOW_MS, LiveToken.Source.PC)) }),
                now,
            ),
            connector = net,
            speaker = FakeSpeaker(),
            bridge = LiveToolBridge(RecordingHands(), ConfirmGate(now)),
            persona = "p",
            transcript = CallTranscript(now),
            sink = { saved += it },
            now = now,
        )
        s.start()
        runCurrent()
        net.last.open()
        net.last.server(Frames.SETUP_OK)
        net.last.server(Frames.heard("Як звати сестру?"))
        net.last.server(Frames.said("Марта."))
        net.last.server(Frames.TURN_DONE)
        net.last.server(Frames.heard("Дякую"))
        runCurrent()
        assertTrue("до кінця дзвінка в чат нічого не пишеться", saved.isEmpty())
        assertEquals("Дякую", s.state.value.owner)
        assertEquals("Марта.", s.state.value.phantom)
        s.hangUp()
        s.hangUp()
        runCurrent()
        assertEquals(1, saved.size)
        assertEquals(
            listOf("OWNER:Як звати сестру?", "PHANTOM:Марта.", "OWNER:Дякую"),
            saved.single().map { "${it.who}:${it.text}" },
        )
    }

    @Test
    fun `обрив і перепідключення не губить сказане до обриву`() = runTest {
        val saved = mutableListOf<List<CallTranscript.Line>>()
        val now = { testScheduler.currentTime }
        val net = FakeConnector()
        val s = LiveCallSession(
            scope = backgroundScope,
            tokens = LiveTokens(
                listOf(LiveTokenSource { at -> Mint.Got(LiveToken("t", "m", at + TOKEN_TTL_MS, at + START_WINDOW_MS, LiveToken.Source.PC)) }),
                now,
            ),
            connector = net,
            speaker = FakeSpeaker(),
            bridge = LiveToolBridge(RecordingHands(), ConfirmGate(now)),
            persona = "p",
            transcript = CallTranscript(now),
            sink = { saved += it },
            now = now,
        )
        s.start()
        runCurrent()
        net.last.open()
        net.last.server(Frames.SETUP_OK)
        net.last.server(Frames.heard("перше"))
        net.last.server(Frames.goAway())
        runCurrent()
        net.last.open()
        net.last.server(Frames.SETUP_OK)
        net.last.server(Frames.said("друге"))
        runCurrent()
        s.hangUp()
        runCurrent()
        assertEquals(listOf("перше", "друге"), saved.single().map { it.text })
    }
}
