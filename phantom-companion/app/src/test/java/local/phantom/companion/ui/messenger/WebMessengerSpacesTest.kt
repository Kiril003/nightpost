package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Міст простору: події, канали, стеля (картка msg-x 05). */
class WebMessengerSpacesTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val id = "spc123spc123"
    private val g = PeerGroup.Group(id, "Клуб", listOf(me, olia))

    private fun msg(text: String, from: String, at: Long) =
        PeerMessage(id = "$from-$at", fromId = from, toId = me, body = PeerGroup.encode(g, text), sentAtMs = at)

    private val log = listOf(
        msg("відкрили", me, 1),
        msg(PeerGroup.encodeAct(PeerGroup.Act.Set(PeerGroup.Setting.KIND, PeerGroup.KIND_SPACE)), me, 2),
        msg(PeerGroup.encodeAct(PeerGroup.Act.Space("""{"id":"e1","actor":"$me"}""")), olia, 3),
        msg(PeerGroup.inChannel("trenery", "о сьомій")!!, olia, 4),
    )

    @Test
    fun `веб отримує подію з підписантом, а не з полем JSON`() {
        val state = PeerGroup.stateOf(log, id)!!
        assertTrue(state.isSpace)
        assertEquals(listOf(WebMessengerSpaces.Wire(olia, 3, """{"id":"e1","actor":"$me"}""")), WebMessengerSpaces.wire(state))
    }

    @Test
    fun `стрічка не показує подій простору, а рядок каналу — без адреси`() {
        val feed = WebMessengerGroups.feed(log, id)
        assertEquals(3, feed.size)
        assertFalse(feed.any { PeerGroup.isSpaceLine(it.body) })
        assertEquals("о сьомій", WebMessengerGroups.said(feed.last().body))
        assertEquals("trenery", WebMessengerGroups.channelOf(feed.last().body))
        assertNull(WebMessengerGroups.channelOf(feed.first().body))
    }

    @Test
    fun `простір повний на 50, гілка на 32`() {
        val big = g.copy(memberIds = (1..40).map { "f%015d".format(it) })
        assertTrue(WebMessengerGroups.isFull(big, null))
        assertFalse(WebMessengerGroups.isFull(big, PeerGroup.stateOf(log, id)))
        assertEquals(PeerGroup.MAX_SPACE_MEMBERS, PeerGroup.stateOf(log, id)!!.cap)
    }
}
