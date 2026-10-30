package local.phantom.companion.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerMoment
import local.phantom.companion.ui.messenger.WebMessengerMoments
import org.junit.Assert.assertEquals
import org.junit.Test

class MomentsSweepTest {

    private val me = "me000000"
    private val t0 = 1_790_000_000_000L
    private fun photo(blob: String) = Json.parseToJsonElement(
        """{"name":"p.jpg","size":1200,"mime":"image/jpeg","sha256":"${"ab".repeat(32)}","blob_id":"$blob","key_hex":"${"cd".repeat(32)}"}""",
    ).jsonObject

    private class Sink(val me: String, val log: MutableList<PeerMessage>) : MomentsWiring.Sink {
        val deleted = mutableListOf<String>()
        val dropped = mutableListOf<Pair<String, String>>()
        override fun log() = log.toList()
        override fun me() = me
        override fun delete(line: PeerMessage) { deleted += line.id }
        override fun dropBytes(conversationId: String, blobId: String) { dropped += conversationId to blobId }
    }

    @Test fun `минула доба — рядок і байти геть, у себе й у того, кому слали`() {
        val a = "a".repeat(32)
        val b = "b".repeat(32)
        val mine = PeerMoment.compose(photo(a), PeerMoment.EVERYONE, t0)!!
        val hers = PeerMoment.compose(photo(b), PeerMoment.EVERYONE, t0 + 10)!!
        val sink = Sink(me, mutableListOf(
            PeerMessage(id = "1", fromId = me, toId = "olena", body = mine, sentAtMs = t0),
            PeerMessage(id = "2", fromId = "olena", toId = me, body = hers, sentAtMs = t0 + 10),
            PeerMessage(id = "3", fromId = "olena", toId = me, body = "привіт", sentAtMs = t0),
        ))
        assertEquals(0, MomentsWiring.sweep(sink, t0 + PeerMoment.TTL_MS - 1))
        assertEquals(1, MomentsWiring.sweep(sink, t0 + PeerMoment.TTL_MS))
        assertEquals(listOf("1"), sink.deleted)
        assertEquals(setOf("olena" to a, WebMessengerMoments.OWN_DIR to a), sink.dropped.toSet())
        sink.log.removeAt(0)
        assertEquals(1, MomentsWiring.sweep(sink, t0 + PeerMoment.TTL_MS + 10))
        assertEquals("olena" to b, sink.dropped.last())
    }
}
