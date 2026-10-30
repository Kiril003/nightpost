package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PeerAnnounceReactionTest {

    private val group = PeerGroup.Group("abc123abc123", "Північ", listOf("me", "marta", "oleh"))
    private val mine = PeerMessage(id = "m1", fromId = "me", toId = "marta", body = "завтра о сьомій", sentAtMs = 1_000L)
    private val theirs = PeerMessage(id = "m2", fromId = "marta", toId = "me", body = "ок", sentAtMs = 2_000L)
    private val myGroupLine = PeerMessage(id = "g1", fromId = "me", toId = "marta", body = PeerGroup.encode(group, "виходимо"), sentAtMs = 3_000L)
    private val log = listOf(mine, theirs, myGroupLine)

    private fun react(from: String, target: String, on: Boolean = true, emoji: String = "❤") = PeerMessage(
        id = "r1",
        fromId = from,
        toId = "me",
        body = PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, on, emoji))!!,
        sentAtMs = 5_000L,
    )

    private fun reaction(m: PeerMessage, chat: String? = null, grp: String? = null) =
        PeerAnnounce.reaction(m, log, chat, grp, selfId = "me")

    @Test
    fun `a reaction to my line reaches the shade with the emoji and my words`() {
        val r = reaction(react("marta", "m1"))!!
        assertEquals("marta", r.notice.key)
        assertEquals(null, r.notice.groupId)
        assertEquals("❤", r.emoji)
        assertEquals("завтра о сьомій", r.quoted)
    }

    @Test
    fun `the frame itself stays silent on the ordinary road`() {
        assertNull(PeerAnnounce.notice(react("marta", "m1"), null, null, selfId = "me"))
    }

    @Test
    fun `taking a reaction back, reacting to their own line or to a stranger's line says nothing`() {
        assertNull(reaction(react("marta", "m1", on = false)))
        assertNull(reaction(react("marta", "m2")))
        assertNull(reaction(react("oleh", "m1")))
        assertNull(reaction(react("me", "m1")))
    }

    @Test
    fun `the chat on screen does not also buzz`() {
        assertNull(reaction(react("marta", "m1"), chat = "marta"))
    }

    @Test
    fun `a reaction to my group line is keyed on the group`() {
        val r = reaction(react("oleh", "me_3000"))!!
        assertEquals(group.id, r.notice.key)
        assertEquals("Північ", r.notice.groupName)
        assertEquals("виходимо", r.quoted)
        assertNull(reaction(react("oleh", "me_3000"), grp = group.id))
        assertNull(reaction(react("stranger", "me_3000")))
    }

    @Test
    fun `wire bodies are never quoted and long words are cut`() {
        val file = mine.copy(id = "m3", body = PeerMark.encode(PeerMark.Mark(PeerMark.What.VOTE, "m9", true, "opt_1"))!!)
        val long = mine.copy(id = "m4", body = "а".repeat(200))
        val withBoth = log + file + long
        assertNull(PeerAnnounce.reaction(react("marta", "m3"), withBoth, null, null, "me")!!.quoted)
        assertEquals(60, PeerAnnounce.reaction(react("marta", "m4"), withBoth, null, null, "me")!!.quoted!!.length)
    }
}
