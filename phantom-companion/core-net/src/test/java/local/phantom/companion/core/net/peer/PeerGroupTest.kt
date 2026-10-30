package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerGroupTest {

    private val me = "aaaa000000000001"
    private val olia = "bbbb000000000002"
    private val taras = "cccc000000000003"

    private fun group(name: String = "Виїзд") =
        PeerGroup.Group(id = "abc123abc123", name = name, memberIds = listOf(me, olia, taras))

    private fun msg(
        toId: String,
        body: String,
        state: PeerMessage.State = PeerMessage.State.QUEUED,
        sentAtMs: Long = 1_000L,
        fromId: String = "aaaa000000000001",
    ) = PeerMessage(
        id = "$toId-$sentAtMs-${body.hashCode()}",
        fromId = fromId,
        toId = toId,
        body = body,
        sentAtMs = sentAtMs,
        state = state,
    )

    @Test
    fun `round trips through the wire`() {
        val decoded = PeerGroup.decode(PeerGroup.encode(group(), "виходимо о шостій"))
        assertEquals("abc123abc123", decoded?.group?.id)
        assertEquals("Виїзд", decoded?.group?.name)
        assertEquals(listOf(me, olia, taras), decoded?.group?.memberIds)
        assertEquals("виходимо о шостій", decoded?.text)
    }

    @Test
    fun `a group name cannot inject a separator`() {
        val decoded = PeerGroup.decode(PeerGroup.encode(group("a:b:c"), "текст"))
        assertEquals("a:b:c", decoded?.group?.name)
        assertEquals("текст", decoded?.text)
    }

    @Test
    fun `newlines in the text survive`() {
        val text = "перший рядок\nдругий рядок"
        assertEquals(text, PeerGroup.decode(PeerGroup.encode(group(), text))?.text)
    }

    @Test
    fun `a plain message is not a group message`() {
        assertNull(PeerGroup.decode("просто текст"))
        assertNull(PeerGroup.decode(PeerPayload.encodePlace(50.45, 30.52)))
    }

    @Test
    fun `truncated and malformed envelopes are refused`() {
        assertNull(PeerGroup.decode("PH-GRP:abc123"))
        assertNull(PeerGroup.decode("PH-GRP:abc:bmFtZQ\ntext"))
        assertNull(PeerGroup.decode("PH-GRP:abc:bmFtZQ::\ntext"))
    }

    @Test
    fun `the operator is never sent their own message`() {
        assertEquals(listOf(olia, taras), PeerGroup.fanOut(group(), me))
    }

    /**
     * The empty fan-out is what the People screen reads as «не створилось»: a
     * group with nobody to send to is never written, so it must never get a
     * screen either. Blank meId is the real case — the Keystore identity had
     * not resolved yet, so the creator was dropped from their own group.
     */
    @Test
    fun `a group with nobody to send to fans out to nobody`() {
        val alone = PeerGroup.Group(id = "abc123abc123", name = "Виїзд", memberIds = listOf(me))
        assertTrue(PeerGroup.fanOut(alone, me).isEmpty())

        val creatorDropped = PeerGroup.Group(id = "abc123abc123", name = "Виїзд", memberIds = listOf())
        assertTrue(PeerGroup.fanOut(creatorDropped, "").isEmpty())
    }

    @Test
    fun `delivery is counted per person, not guessed`() {
        val body = PeerGroup.encode(group(), "виходимо")
        val outbox = listOf(
            msg(olia, body, PeerMessage.State.DELIVERED),
            msg(taras, body, PeerMessage.State.SENT),
        )
        val fan = PeerGroup.fanState(outbox, "abc123abc123", 1_000L)
        assertEquals(1, fan.delivered)
        assertEquals(1, fan.waiting)
        assertEquals(2, fan.total)
        assertTrue(!fan.allDelivered)
    }

    @Test
    fun `one thing said shows as one line, not as a copy per member`() {
        val body = PeerGroup.encode(group(), "виходимо")
        val outbox = listOf(
            msg(olia, body, PeerMessage.State.DELIVERED),
            msg(taras, body, PeerMessage.State.FAILED),
            msg(olia, PeerGroup.encode(group(), "уже тут"), sentAtMs = 2_000L),
            msg(taras, PeerGroup.encode(group(), "уже тут"), sentAtMs = 2_000L),
        )
        val lines = PeerGroup.collapse(outbox, "abc123abc123")
        assertEquals(2, lines.size)
        assertEquals(listOf(1_000L, 2_000L), lines.map { it.sentAtMs })
    }

    @Test
    fun `a collapsed line shows the worst state of its copies`() {
        val body = PeerGroup.encode(group(), "виходимо")
        val outbox = listOf(
            msg(olia, body, PeerMessage.State.DELIVERED),
            msg(taras, body, PeerMessage.State.QUEUED),
        )
        // QUEUED ordinal is lowest — the line must not claim «доставлено»
        // while somebody in the group has still not been reached.
        assertEquals(
            PeerMessage.State.QUEUED,
            PeerGroup.collapse(outbox, "abc123abc123").single().state,
        )
    }

    @Test
    fun `two groups with the same people stay two conversations`() {
        val a = PeerGroup.Group("111111111111", "Робота", listOf(me, olia))
        val b = PeerGroup.Group("222222222222", "Дім", listOf(me, olia))
        val outbox = listOf(
            msg(olia, PeerGroup.encode(a, "перше")),
            msg(olia, PeerGroup.encode(b, "друге")),
        )
        assertEquals(1, PeerGroup.collapse(outbox, "111111111111").size)
        assertEquals(2, PeerGroup.known(outbox).size)
    }

    @Test
    fun `a group is learned from its messages alone`() {
        val outbox = listOf(msg(me, PeerGroup.encode(group(), "привіт"), fromId = olia))
        val learned = PeerGroup.known(outbox).single()
        assertEquals("Виїзд", learned.name)
        assertEquals(listOf(me, olia, taras), learned.memberIds)
    }

    /**
     * Раніше тут вигравав найновіший лист. Це коштувало двох мовчазних відкотів
     * (див. PeerGroupActTest), тож склад і назву тепер міняє лише дія.
     */
    @Test
    fun `звичайна балачка більше не переписує склад і назву`() {
        val opened = PeerGroup.Group("abc123abc123", "Виїзд", listOf(me, olia))
        val stale = PeerGroup.Group("abc123abc123", "Виїзд · субота", listOf(me, olia, taras))
        val outbox = listOf(
            msg(olia, PeerGroup.encode(stale, "друге"), sentAtMs = 2_000L),
            msg(olia, PeerGroup.encode(opened, "перше"), sentAtMs = 1_000L),
        )
        val learned = PeerGroup.known(outbox).single()
        assertEquals("Виїзд", learned.name)
        assertEquals(listOf(me, olia), learned.memberIds)
    }

    @Test
    fun `a fresh id is twelve hex characters`() {
        val id = PeerGroup.newId()
        assertEquals(12, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
    }

    @Test
    fun `ще раз піднімає лише ті копії рядка, що не дійшли`() {
        val body = PeerGroup.encode(group(), "виходимо")
        val outbox = listOf(
            msg(olia, body, state = PeerMessage.State.FAILED, sentAtMs = 2_000L),
            msg(taras, body, state = PeerMessage.State.DELIVERED, sentAtMs = 2_000L),
            // Інший рядок тієї ж групи — його чіпати не можна.
            msg(olia, PeerGroup.encode(group(), "вже тут"),
                state = PeerMessage.State.FAILED, sentAtMs = 9_000L),
        )
        val ids = PeerGroup.failedFan(outbox, "abc123abc123", sentAtMs = 2_000L)
        assertEquals(1, ids.size)
        assertEquals(outbox[0].id, ids.single())
    }

    @Test
    fun `рядок, де все дійшло, нема чого піднімати`() {
        val outbox = listOf(
            msg(olia, PeerGroup.encode(group(), "виходимо"),
                state = PeerMessage.State.DELIVERED, sentAtMs = 2_000L),
        )
        assertTrue(PeerGroup.failedFan(outbox, "abc123abc123", 2_000L).isEmpty())
    }

    @Test
    fun `власні листи не лічаться непрочитаними`() {
        val outbox = listOf(
            msg(olia, PeerGroup.encode(group(), "виходимо"), sentAtMs = 2_000L, fromId = me),
            msg(taras, PeerGroup.encode(group(), "виходимо"), sentAtMs = 2_000L, fromId = me),
            msg(me, PeerGroup.encode(group(), "вже тут"), sentAtMs = 3_000L, fromId = olia),
        )
        assertEquals(1, PeerGroup.unread(outbox, "abc123abc123", me, readUpToMs = 0L))
    }

    @Test
    fun `поки невідомо хто ми — значка немає, а не число з власних листів`() {
        val outbox = listOf(
            msg(olia, PeerGroup.encode(group(), "виходимо"), sentAtMs = 2_000L, fromId = me),
            msg(taras, PeerGroup.encode(group(), "виходимо"), sentAtMs = 2_000L, fromId = me),
        )
        assertEquals(0, PeerGroup.unread(outbox, "abc123abc123", meId = "", readUpToMs = 0L))
    }

    @Test
    fun `an oversized member list is refused rather than trimmed on arrival`() {
        val many = (1..PeerGroup.MAX_SPACE_MEMBERS + 1).map { "id%016d".format(it) }
        val head = "PH-GRP:abc123abc123:${java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("Велика".toByteArray())}:${many.joinToString(",")}"
        assertNull(PeerGroup.decode("$head\nтекст"))
    }
}
