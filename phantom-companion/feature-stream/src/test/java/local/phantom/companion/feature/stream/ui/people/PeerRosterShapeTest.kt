package local.phantom.companion.feature.stream.ui.people

import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRoster
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Форма реєстру й розмови.
 *
 * Порядок рядків тримає розмова, а не радіо: якщо список пересортовує чужий
 * Wi-Fi, людина втрачає памʼять місця саме тоді, коли поспішає. «В мережі»
 * лишається фільтром — і живим названо того, від кого щось чули у вікні, а не
 * того, кого колись додали. Роздільник «нове» так само мусить стояти лише над
 * ЇХНІМ повідомленням: лінія над власною фразою — ознака месенджера, якому не
 * вірять.
 */
class PeerRosterShapeTest {

    private val now = 1_000_000L
    private val me = "meeeeeeeeeeeeeee"
    private val them = "themmmmmmmmmmmmm"

    private fun peer(
        id: String,
        name: String = "Хтось",
        seen: Long = 0L,
        added: Long = 0L,
        verifiedAt: Long = 0L,
        keyChangedAt: Long = 0L,
        source: PeerIdentity.KeySource = PeerIdentity.KeySource.CAMERA,
    ) = PeerIdentity(
        id = id,
        name = name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "k-$id",
        addedAtMs = added,
        lastSeenMs = seen,
        verifiedAtMs = verifiedAt,
        keyChangedAtMs = keyChangedAt,
        keySource = source,
    )

    private fun msg(from: String, at: Long, body: String = "текст") = PeerMessage(
        id = "$from-$at",
        fromId = from,
        toId = if (from == me) them else me,
        body = body,
        sentAtMs = at,
    )

    @Test
    fun `only a fresh signal counts as reachable`() {
        val roster = listOf(
            peer("a", seen = now - 1_000L),
            peer("b", seen = now - PeerRoster.LIVE_WINDOW_MS - 1L),
            peer("c", seen = 0L),
        )
        assertEquals(listOf("a"), PeerChatFormat.reachable(roster, now).map { it.id })
    }

    @Test
    fun `the order is conversation, never the radio`() {
        val talkedYesterday = peer("a", name = "Ада", seen = 0L, added = 10L)
        val talkedJustNow = peer("b", name = "Богдан", seen = 0L, added = 20L)
        val neverTalkedButLive = peer("c", name = "Влад", seen = now, added = 30L)
        val roster = listOf(talkedYesterday, talkedJustNow, neverTalkedButLive)
        val activity = mapOf("a" to 100L, "b" to 900L, "c" to 0L)

        val ordered = PeerChatFormat.ordered(roster) { activity[it.id]!! }
        assertEquals(listOf("b", "a", "c"), ordered.map { it.id })

        // Same people, presence inverted — the one live peer went silent and
        // the two silent ones came into range. Nothing may move: a row that
        // travels because somebody else's Wi-Fi dropped is the whole reason
        // sectioning by presence was taken out.
        val presenceFlipped = listOf(
            talkedYesterday.copy(lastSeenMs = now),
            talkedJustNow.copy(lastSeenMs = now),
            neverTalkedButLive.copy(lastSeenMs = 0L),
        )
        assertEquals(
            ordered.map { it.id },
            PeerChatFormat.ordered(presenceFlipped) { activity[it.id]!! }.map { it.id },
        )
    }

    @Test
    fun `people with nothing said yet fall back to when they were added`() {
        val roster = listOf(
            peer("old", name = "Яна", added = 100L),
            peer("new", name = "Ада", added = 900L),
        )
        assertEquals(
            listOf("new", "old"),
            PeerChatFormat.ordered(roster) { 0L }.map { it.id },
        )
    }

    @Test
    fun `the filter keeps the order it was given`() {
        val roster = listOf(
            peer("a", seen = now, added = 10L),
            peer("b", seen = 0L, added = 20L),
            peer("c", seen = now, added = 30L),
        )
        val activity = mapOf("a" to 300L, "b" to 200L, "c" to 100L)
        val ordered = PeerChatFormat.ordered(roster) { activity[it.id]!! }
        val filtered = PeerChatFormat.reachable(ordered, now)

        val keptIds = filtered.map { it.id }
        assertEquals(listOf("a", "c"), keptIds)
        // A strict subsequence — spatial memory has to survive the filter.
        assertEquals(keptIds, ordered.map { it.id }.filter { it in keptIds })
    }

    @Test
    fun `an empty roster filters to nothing rather than failing`() {
        assertTrue(PeerChatFormat.reachable(emptyList(), now).isEmpty())
        assertTrue(PeerChatFormat.ordered(emptyList()) { 0L }.isEmpty())
    }

    @Test
    fun `a moved key outranks the tick the old key earned`() {
        assertEquals(
            PeerChatFormat.Trust.UNVERIFIED,
            PeerChatFormat.trust(peer("a")),
        )
        assertEquals(
            PeerChatFormat.Trust.IN_PERSON,
            PeerChatFormat.trust(peer("a", verifiedAt = 500L)),
        )
        // Verified long ago, key swapped since — the tick belongs to a key
        // that is no longer there.
        assertEquals(
            PeerChatFormat.Trust.CHANGED,
            PeerChatFormat.trust(peer("a", verifiedAt = 500L, keyChangedAt = 900L)),
        )
        // Read aloud again after the change — the alarm ends by verifying.
        assertEquals(
            PeerChatFormat.Trust.IN_PERSON,
            PeerChatFormat.trust(peer("a", verifiedAt = 1_000L, keyChangedAt = 900L)),
        )
    }

    @Test
    fun `search finds a person by name and by key`() {
        val roster = listOf(
            peer("a1b2c3d4e5f60718", name = "Богдан"),
            peer("ffeeddccbbaa9988", name = "Оксана"),
        )
        assertEquals(listOf("Богдан"), PeerChatFormat.search(roster, "бог").map { it.name })
        // Ключ показують як «a1b2·c3d4», але крапку ніхто не набирає.
        assertEquals(listOf("Богдан"), PeerChatFormat.search(roster, "a1b2c3").map { it.name })
        assertEquals(listOf("Богдан"), PeerChatFormat.search(roster, "a1b2·c3").map { it.name })
        assertTrue(PeerChatFormat.search(roster, "кого нема").isEmpty())
        assertEquals(2, PeerChatFormat.search(roster, "  ").size)
    }

    @Test
    fun `the new mark stands above their first unread line`() {
        val log = listOf(msg(them, 100), msg(me, 200), msg(them, 300), msg(them, 400))
        val rows = PeerChatFormat.rows(log, me, readUpToMs = 200L)
        val markAt = rows.indexOfFirst { it is PeerChatFormat.Row.NewMark }
        assertTrue("no mark drawn", markAt >= 0)
        val next = rows[markAt + 1] as PeerChatFormat.Row.Bubble
        assertEquals(300L, next.message.sentAtMs)
        assertEquals(1, rows.count { it is PeerChatFormat.Row.NewMark })
    }

    @Test
    fun `the mark never stands above my own message`() {
        val log = listOf(msg(them, 100), msg(me, 200), msg(me, 300))
        val rows = PeerChatFormat.rows(log, me, readUpToMs = 100L)
        assertTrue(rows.none { it is PeerChatFormat.Row.NewMark })
    }

    @Test
    fun `a chat read to the end has no mark`() {
        val log = listOf(msg(them, 100), msg(them, 200))
        assertTrue(
            PeerChatFormat.rows(log, me, readUpToMs = 200L)
                .none { it is PeerChatFormat.Row.NewMark },
        )
    }

    @Test
    fun `a first-ever conversation has no mark`() {
        val log = listOf(msg(them, 100), msg(them, 200))
        assertTrue(
            PeerChatFormat.rows(log, me, readUpToMs = 0L)
                .none { it is PeerChatFormat.Row.NewMark },
        )
    }
}
