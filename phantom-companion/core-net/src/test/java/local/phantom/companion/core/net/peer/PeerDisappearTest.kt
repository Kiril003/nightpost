package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerDisappearTest {

    private val me = "me000000"
    private val her = "her00000"
    private val t0 = 1_790_000_000_000L
    private val day = PeerDisappear.DAY_S * 1000

    private fun line(id: String, from: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = from, toId = if (from == me) her else me, body = body, sentAtMs = at)

    private fun ttl(from: String, seconds: Long, at: Long) = line("ttl-$at", from, PeerDisappear.encode(seconds, at)!!, at)

    @Test fun `кадр — лише чотири строки, і читається назад`() {
        assertEquals("PH-TTL:86400;$t0", PeerDisappear.encode(PeerDisappear.DAY_S, t0))
        assertNull(PeerDisappear.encode(3600, t0))
        assertEquals(PeerDisappear.WEEK_S to t0, PeerDisappear.decode("PH-TTL:604800;$t0"))
        assertNull(PeerDisappear.decode("PH-TTL:3600;$t0"))
        assertNull(PeerDisappear.decode("PH-TTL:86400"))
        assertEquals(PeerFrames.Kind.TTL, PeerFrames.kindOf("PH-TTL:0;$t0"))
        assertTrue("строк не репліка", !PeerOutbox.isSpoken(ttl(me, PeerDisappear.DAY_S, t0)))
    }

    @Test fun `діє найпізніший кадр обох, хоч би хто поставив`() {
        val log = listOf(ttl(me, PeerDisappear.DAY_S, t0), ttl(her, PeerDisappear.WEEK_S, t0 + 10), line("x", her, "привіт", t0 + 5))
        val now = PeerDisappear.current(log, me, her)!!
        assertEquals(PeerDisappear.WEEK_S, now.seconds)
        assertEquals(her, now.byId)
        val s = PeerDisappear.settings(log, me, her)
        assertEquals(PeerDisappear.DAY_S, PeerDisappear.ttlAt(s, t0 + 5))
        assertEquals(0L, PeerDisappear.ttlAt(s, t0 - 1))
    }

    @Test fun `свій лист — від надсилання, чужий — від прочитання, непрочитаний не зникає`() {
        val mine = line("m", me, "моє", t0 + 1_000)
        val hers = line("h", her, "її", t0 + 2_000)
        val log = listOf(ttl(me, PeerDisappear.DAY_S, t0), mine, hers)
        val s = PeerDisappear.settings(log, me, her)
        assertEquals(t0 + 1_000 + day, PeerDisappear.expiresAt(mine, me, s, emptyList()))
        assertNull("чужий непрочитаний — без годинника", PeerDisappear.expiresAt(hers, me, s, emptyList()))
        val reads = PeerDisappear.noteRead(emptyList(), uptoMs = t0 + 2_000, atMs = t0 + 600_000)
        assertEquals(t0 + 600_000 + day, PeerDisappear.expiresAt(hers, me, s, reads))

        assertEquals(emptyList<PeerMessage>(), PeerDisappear.expired(log, me, her, reads, t0 + day))
        assertEquals(listOf(mine), PeerDisappear.expired(log, me, her, reads, t0 + 1_000 + day))
        assertEquals(listOf(mine, hers), PeerDisappear.expired(log, me, her, reads, t0 + 600_000 + day))
    }

    @Test fun `вимкнення не воскрешає того, що вже йшло зі строком, а нове лишається`() {
        val under = line("a", me, "зі строком", t0 + 1)
        val after = line("b", me, "після вимкнення", t0 + 20)
        val log = listOf(ttl(me, PeerDisappear.DAY_S, t0), under, ttl(her, 0, t0 + 10), after)
        assertEquals(listOf(under), PeerDisappear.expired(log, me, her, emptyList(), t0 + 10 * day))
    }

    @Test fun `зникають слова й шматки вкладень, а службові кадри лишаються`() {
        val chunk = line("c", me, PeerFile.vaultedChunk("0123456789abcdef0123456789abcdef", 0, 1), t0 + 1)
        val read = line("r", her, "PH-RDU:${t0 + 1}", t0 + 2)
        val log = listOf(ttl(me, PeerDisappear.DAY_S, t0), chunk, read, line("w", me, "слово", t0 + 3))
        val gone = PeerDisappear.expired(log, me, her, emptyList(), t0 + 2 * day).map { it.id }
        assertEquals(listOf("c", "w"), gone)
    }

    @Test fun `без строку — нічого, і межа прочитаного лише росте`() {
        val log = listOf(line("x", me, "слово", t0))
        assertEquals(emptyList<PeerMessage>(), PeerDisappear.expired(log, me, her, emptyList(), t0 + 100 * day))
        val r = PeerDisappear.noteRead(PeerDisappear.noteRead(emptyList(), 10, 100), 5, 200)
        assertEquals(listOf(PeerDisappear.ReadMark(10, 100)), r)
    }
}
