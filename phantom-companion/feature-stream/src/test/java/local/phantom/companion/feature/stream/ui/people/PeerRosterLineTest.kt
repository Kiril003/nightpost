package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Що рядок людини каже про людину.
 *
 * Дві помилки тут коштують довіри до всього списку: порахувати непрочитаним
 * власне повідомлення (лічильник, який ніколи не гасне) і показати ключ там,
 * де вже є розмова (список хешів замість списку людей).
 */
class PeerRosterLineTest {

    private val me = "meeeeeeeeeeeeeee"
    private val them = "themmmmmmmmmmmmm"

    private fun msg(from: String, to: String, at: Long, body: String = "текст") = PeerMessage(
        id = "$from-$at",
        fromId = from,
        toId = to,
        body = body,
        sentAtMs = at,
    )

    @Test
    fun `my own messages are never unread`() {
        val log = listOf(
            msg(me, them, 100),
            msg(me, them, 200),
            msg(me, them, 300),
        )
        assertEquals(0, PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L).unread)
    }

    @Test
    fun `only what they said after the boundary counts`() {
        val log = listOf(
            msg(them, me, 100),
            msg(them, me, 200),
            msg(me, them, 250),
            msg(them, me, 300),
        )
        assertEquals(3, PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L).unread)
        assertEquals(1, PeerChatFormat.rosterLine(log, them, me, readUpToMs = 200L).unread)
        assertEquals(0, PeerChatFormat.rosterLine(log, them, me, readUpToMs = 300L).unread)
    }

    @Test
    fun `another persons conversation is not counted here`() {
        val other = "otherrrrrrrrrrrr"
        val log = listOf(msg(other, me, 100), msg(them, me, 100))
        assertEquals(1, PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L).unread)
    }

    @Test
    fun `an empty conversation shows no preview so the row can show the key`() {
        val line = PeerChatFormat.rosterLine(emptyList(), them, me, readUpToMs = 0L)
        assertNull(line.preview)
        assertEquals(0L, line.atMs)
    }

    @Test
    fun `the preview is the newest line either way and knows whose it is`() {
        val log = listOf(msg(them, me, 100, "їхнє"), msg(me, them, 200, "моє"))
        val line = PeerChatFormat.rosterLine(log, them, me, readUpToMs = 999L)
        assertEquals("моє", line.preview)
        assertTrue(line.mine)
        assertEquals(200L, line.atMs)

        val older = PeerChatFormat.rosterLine(log.take(1), them, me, readUpToMs = 999L)
        assertFalse(older.mine)
    }

    @Test
    fun `a multi-line message does not make the row multi-line`() {
        val log = listOf(msg(them, me, 100, "перший\n\nдругий   рядок"))
        assertEquals(
            "перший другий рядок",
            PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L).preview,
        )
    }

    @Test
    fun `a message that is only whitespace is not a preview`() {
        val log = listOf(msg(them, me, 100, "   \n  "))
        assertNull(PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L).preview)
    }

    @Test
    fun `namesakes are the ones that get their key shown`() {
        val roster = listOf(
            "id-1" to "Олег",
            "id-2" to "Олег",
            "id-3" to "Богдан",
        )
        val out = PeerChatFormat.ambiguous(roster)
        assertEquals(setOf("id-1", "id-2"), out)
    }

    @Test
    fun `a name that only differs by case or spacing is still the same name`() {
        val roster = listOf("id-1" to "Олег", "id-2" to "  олег ", "id-3" to "Оксана")
        assertEquals(setOf("id-1", "id-2"), PeerChatFormat.ambiguous(roster))
    }

    @Test
    fun `a roster of distinct names shows no keys at all`() {
        val roster = listOf("id-1" to "Олег", "id-2" to "Богдан", "id-3" to "Оксана")
        assertTrue(PeerChatFormat.ambiguous(roster).isEmpty())
    }

    @Test
    fun `three of a name are all ambiguous, not two of them`() {
        val roster = listOf("a" to "Мама", "b" to "Мама", "c" to "Мама", "d" to "Тато")
        assertEquals(setOf("a", "b", "c"), PeerChatFormat.ambiguous(roster))
    }

    @Test
    fun `a place never shows its wire form on a roster row`() {
        val log = listOf(msg(them, me, 100, "PH-LOC:50.44020,30.51950;25"))
        val line = PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L)
        assertNull("the protocol leaked onto the row", line.preview)
        assertEquals(PeerChatFormat.Kind.PLACE, line.kind)
        assertEquals(100L, line.atMs)
        assertEquals(1, line.unread)
    }

    @Test
    fun `words are still words`() {
        val log = listOf(msg(them, me, 100, "я на площі"))
        val line = PeerChatFormat.rosterLine(log, them, me, readUpToMs = 0L)
        assertEquals("я на площі", line.preview)
        assertEquals(PeerChatFormat.Kind.TEXT, line.kind)
    }

    @Test
    fun `the badge stays a badge`() {
        assertNull(PeerChatFormat.unreadBadge(0))
        assertNull(PeerChatFormat.unreadBadge(-1))
        assertEquals("1", PeerChatFormat.unreadBadge(1))
        assertEquals("99", PeerChatFormat.unreadBadge(99))
        assertEquals("99+", PeerChatFormat.unreadBadge(100))
        assertEquals("99+", PeerChatFormat.unreadBadge(5000))
    }
}
