package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wire between two phones. Anything on the same Wi-Fi can open this port,
 * so every frame that is not exactly right must be refused rather than
 * guessed at.
 */
class PeerWireTest {

    @Test
    fun `a well-formed header is read`() {
        val h = PeerWire.parseHeader("PHANTOM/1 MSG 120")!!
        assertEquals("MSG", h.verb)
        assertEquals(120, h.bodyBytes)
    }

    @Test
    fun `the header a sender writes is the header a reader parses`() {
        val h = PeerWire.parseHeader(PeerWire.header(PeerWire.VERB_MSG, 42))!!
        assertEquals(42, h.bodyBytes)
    }

    @Test
    fun `someone else's traffic is not answered`() {
        assertNull(PeerWire.parseHeader("GET / HTTP/1.1"))
        assertNull(PeerWire.parseHeader(""))
        assertNull(PeerWire.parseHeader(null))
        assertNull(PeerWire.parseHeader("PHANTOM/1 MSG"))
    }

    @Test
    fun `a length that is not a number is refused`() {
        assertNull(PeerWire.parseHeader("PHANTOM/1 MSG багато"))
    }

    @Test
    fun `an absurd length is refused before a byte is read`() {
        // Otherwise anyone on the Wi-Fi allocates as much memory as they like.
        assertNull(PeerWire.parseHeader("PHANTOM/1 MSG 999999999"))
        assertNull(PeerWire.parseHeader("PHANTOM/1 MSG -1"))
    }

    @Test
    fun `an empty body is allowed by the frame but refused by meaning`() {
        val h = PeerWire.parseHeader("PHANTOM/1 MSG 0")!!
        assertTrue(PeerWire.classify(h, "") is PeerWire.Inbound.Malformed)
    }

    @Test
    fun `an unknown verb is refused, not guessed at`() {
        val h = PeerWire.parseHeader("PHANTOM/1 SHOUT 5")!!
        assertTrue(PeerWire.classify(h, "hello") is PeerWire.Inbound.Malformed)
    }

    @Test
    fun `a message body with newlines survives — people press enter`() {
        val body = """{"body":"перший рядок\nдругий"}"""
        val h = PeerWire.parseHeader(PeerWire.header(PeerWire.VERB_MSG, body.length))!!
        val out = PeerWire.classify(h, body)
        assertEquals(body, (out as PeerWire.Inbound.Message).json)
    }

    @Test
    fun `an ack is recognised only for its own receipt`() {
        val line = PeerWire.ackLine("cmVjZWlwdA")
        assertTrue(PeerWire.isAck(line, "cmVjZWlwdA"))
        assertFalse(PeerWire.isAck(line, "cmVjZWlwdB"))
        assertFalse(PeerWire.isAck("PHANTOM/1 ERR busy", "cmVjZWlwdA"))
        assertFalse(PeerWire.isAck(null, "cmVjZWlwdA"))
    }

    @Test
    fun `a bare ACK confirms nothing`() {
        // Три літери й порожнеча — саме те, що напише той, хто квитанції
        // порахувати не може.
        assertFalse(PeerWire.isAck("PHANTOM/1 ACK ", "cmVjZWlwdA"))
        assertFalse(PeerWire.isAck("PHANTOM/1 ACK", "cmVjZWlwdA"))
        assertFalse(PeerWire.isAck(PeerWire.ackLine("cmVjZWlwdA"), ""))
    }

    @Test
    fun `a build that does not speak HLO answers it as an unknown verb`() {
        // Це і є весь запобіжник сумісності, і він мусить бути ТІЄЮ САМОЮ
        // гілкою, якою стара збірка відповідає на будь-яке невідоме слово, —
        // а не її імітацією. Зміниться текст тут — і телефони перестануть
        // розуміти, що співрозмовник просто не оновився.
        val h = PeerWire.parseHeader("PHANTOM/1 HLO 5")!!
        val out = PeerWire.classify(h, "hello")
        assertTrue(out is PeerWire.Inbound.Malformed)
        assertEquals("unknown verb HLO", (out as PeerWire.Inbound.Malformed).reason)
        // І саме це відправник читає з дроту, щоб зрозуміти причину.
        assertTrue(
            PeerWire.errorReason(PeerWire.errorLine(out.reason))!!
                .startsWith(PeerWire.UNKNOWN_VERB),
        )
    }

    @Test
    fun `a listener that speaks HLO reads it as a hello`() {
        val h = PeerWire.parseHeader("PHANTOM/1 HLO 5")!!
        val out = PeerWire.classify(h, """{"v":1}""", speaksHello = true)
        assertEquals("""{"v":1}""", (out as PeerWire.Inbound.Hello).json)
        assertTrue(PeerWire.classify(h, "", speaksHello = true) is PeerWire.Inbound.Malformed)
    }

    @Test
    fun `a refusal says why, and anything else says nothing`() {
        assertEquals("unknown verb HLO", PeerWire.errorReason("PHANTOM/1 ERR unknown verb HLO"))
        assertEquals("refused", PeerWire.errorReason(PeerWire.errorLine("refused")))
        assertNull(PeerWire.errorReason(PeerWire.ackLine("cmVjZWlwdA")))
        assertNull(PeerWire.errorReason("PHANTOM/1 ERR"))
        assertNull(PeerWire.errorReason("PHANTOM/1 ERR "))
        assertNull(PeerWire.errorReason("GET / HTTP/1.1"))
        assertNull(PeerWire.errorReason(null))
    }

    @Test
    fun `a hello reply is framed like any other body`() {
        // Відповідь на HLO — єдина, що має власне тіло. Якби вона їхала одним
        // рядком, привіт із перенесенням рядка обрізався б рівно так, як колись
        // обрізались листи.
        val body = """{"v":1,"from":"a","to":"b"}"""
        val h = PeerWire.parseHeader(PeerWire.header(PeerWire.VERB_HELLO, body.length))!!
        assertEquals(PeerWire.VERB_HELLO, h.verb)
        assertEquals(body.length, h.bodyBytes)
    }

    @Test
    fun `an error line can never break the framing`() {
        val line = PeerWire.errorLine("bad\nsignature\nfrom peer")
        assertFalse("a reason with newlines would forge a second line", '\n' in line)
    }

    @Test
    fun `a hostile receipt cannot stretch the ack line`() {
        val line = PeerWire.ackLine("x".repeat(5000))
        assertTrue(line.length < 200)
    }
}
