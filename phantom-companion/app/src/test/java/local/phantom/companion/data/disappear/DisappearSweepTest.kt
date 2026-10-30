package local.phantom.companion.data.disappear

import local.phantom.companion.core.net.peer.PeerDisappear
import local.phantom.companion.core.net.peer.PeerFile
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.messenger.WebMessengerKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisappearSweepTest {

    private val me = "me000000"
    private val her = "her00000"
    private val t0 = 1_790_000_000_000L
    private val day = PeerDisappear.DAY_S * 1000
    private val blob = "0123456789abcdef0123456789abcdef"

    private fun line(id: String, from: String, body: String, at: Long) =
        PeerMessage(id = id, fromId = from, toId = if (from == me) her else me, body = body, sentAtMs = at)

    private class Sink(var lines: List<PeerMessage>, val me: String, val peer: String) : DisappearWiring.Sink {
        val dropped = mutableListOf<String>()
        override fun log() = lines
        override fun me() = me
        override fun peers() = listOf(peer)
        override fun delete(line: PeerMessage) { lines = lines.filterNot { it.id == line.id } }
        override fun dropBytes(peerId: String, blobId: String) { dropped += blobId }
    }

    @Test fun `минув строк — рядок і байти вкладення стерто, свіжого й непрочитаного не чіпано`() {
        val photo = """{"blob_id":"$blob","key_hex":"aa","sha256":"cc","size":10,"name":"IMG.jpg","mime":"image/jpeg"}"""
        val sink = Sink(
            listOf(
                line("ttl", her, PeerDisappear.encode(PeerDisappear.DAY_S, t0)!!, t0),
                line("old", me, "старе", t0 + 1),
                line("pic", me, photo, t0 + 2),
                line("theirs", her, "непрочитане", t0 + 3),
                line("fresh", me, "свіже", t0 + day),
            ),
            me, her,
        )
        val gone = DisappearWiring.sweep(sink, { emptyList() }, t0 + day + 10)
        assertEquals(2, gone)
        assertEquals(listOf("ttl", "theirs", "fresh"), sink.lines.map { it.id })
        assertEquals(listOf(blob), sink.dropped)
    }

    @Test fun `рядок строку видно в стрічці, але він не непрочитаний і не рядок переліку`() {
        val ttl = line("ttl", her, PeerDisappear.encode(PeerDisappear.WEEK_S, t0)!!, t0)
        val f = WebMessengerKinds.fields(ttl.body)
        assertEquals(WebMessengerKinds.TIMER, f[WebMessengerKinds.KIND])
        assertEquals(PeerDisappear.WEEK_S, f[WebMessengerKinds.TIMER_S])
        assertTrue(WebMessengerKinds.isVisible(ttl.body))
        assertEquals(0, WebMessengerKinds.unread(listOf(ttl), her, 0L))
        assertEquals(null, WebMessengerKinds.lastVisible(listOf(ttl), her))
    }
}
