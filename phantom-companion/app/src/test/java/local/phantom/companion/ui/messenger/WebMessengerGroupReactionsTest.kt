package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.messenger.WebMessengerGroupReactions.Why
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * K1: a reaction in a group follows the 1:1 `react()` contract and travels to every keyed member.
 *
 * The door is called for real; the log is a fixture; `send` appends the frame to the same log,
 * as `PeerMesh.send` does. The mark target is `(author, sentAtMs)`, never the per-copy id.
 */
class WebMessengerGroupReactionsTest {

    private val org = "a1a1a1a1a1a1a1a1"
    private val olena = "b2b2b2b2b2b2b2b2"
    private val petro = "c3c3c3c3c3c3c3c3"
    private val dima = "d4d4d4d4d4d4d4d4"
    private val stranger = "e5e5e5e5e5e5e5e5"
    private val group = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Walk test", listOf(org, olena, petro, dima))
    private val other = PeerGroup.Group("1e1e1e1e1e1e1e1e", "Other", listOf(org, olena, petro))
    private val sentAt = 1_760_000_000_000L
    private val target = "${org}_$sentAt"
    private val said = PeerGroup.encode(group, "привіт усім")

    private val copyToOlena = PeerMessage("11111111-1111-1111-1111-111111111111", org, olena, said, sentAt)
    private val copyToPetro = PeerMessage("22222222-2222-2222-2222-222222222222", org, petro, said, sentAt)
    private val copyToDima = PeerMessage("33333333-3333-3333-3333-333333333333", org, dima, said, sentAt)
    private val otherLine = PeerMessage("44444444-4444-4444-4444-444444444444", org, olena, PeerGroup.encode(other, "інша"), sentAt + 5)
    private val directLine = PeerMessage("55555555-5555-5555-5555-555555555555", org, olena, "вдвох", sentAt + 9)

    private class Phone(
        val me: String,
        start: List<PeerMessage>,
        var keyed: Set<String>,
        var failing: Set<String> = emptySet(),
    ) {
        val log = start.toMutableList()
        val sent = mutableListOf<Pair<String, String>>()
        private var clock = 1_760_000_100_000L

        fun door() = WebMessengerGroupReactions(
            log = { log.toList() },
            me = { me },
            keyed = { keyed },
            send = { to, frame ->
                if (to in failing) {
                    null
                } else {
                    sent += to to frame
                    PeerMessage("sent-${sent.size}", me, to, frame, clock++).also { log += it }
                }
            },
        )
    }

    private fun olenasPhone(keyed: Set<String> = setOf(org, petro), failing: Set<String> = emptySet()) =
        Phone(olena, listOf(copyToOlena, otherLine, directLine), keyed, failing)

    private fun emoji(e: String) = """{"emoji":"$e"}"""

    private fun done(o: WebMessengerGroupReactions.Outcome): WebMessengerGroupReactions.Outcome.Done =
        o as? WebMessengerGroupReactions.Outcome.Done ?: fail("expected Done, got $o") as Nothing

    private fun assertRefused(o: WebMessengerGroupReactions.Outcome, status: Int, code: String, why: Why) {
        assertEquals(WebMessengerGroupReactions.Outcome.Refused(status, code, why), o)
    }

    @Test
    fun `a reaction goes to every keyed member but me, keyed by author and instant`() {
        val p = olenasPhone()
        val r = done(p.door().handle(group, copyToOlena.id, emoji("👍")))
        assertEquals(listOf(org, petro), p.sent.map { it.first })
        for ((_, frame) in p.sent) {
            assertEquals(PeerMark.Mark(PeerMark.What.REACTION, target, true, "👍"), PeerMark.decode(frame))
        }
        assertEquals(target, r.target)
        assertTrue(r.on)
        assertEquals(listOf(dima), r.missed)
        assertEquals(copyToOlena.id, r.line.id)
        assertEquals(mapOf("👍" to listOf(olena)), PeerMark.fold(p.log, PeerMark.What.REACTION, target))
    }

    @Test
    fun `the same reaction again removes it, direction from the journal`() {
        val p = olenasPhone()
        val door = p.door()
        done(door.handle(group, copyToOlena.id, emoji("👍")))
        val r = done(door.handle(group, copyToOlena.id, emoji("👍")))
        assertEquals(false, r.on)
        assertEquals(4, p.sent.size)
        for ((_, frame) in p.sent.drop(2)) {
            assertEquals(PeerMark.Mark(PeerMark.What.REACTION, target, false, "👍"), PeerMark.decode(frame))
        }
        assertEquals(emptyMap<String, List<String>>(), PeerMark.fold(p.log, PeerMark.What.REACTION, target))
    }

    @Test
    fun `any sibling copy id resolves to the same target and the representative line`() {
        val p = Phone(org, listOf(copyToOlena, copyToPetro, copyToDima), keyed = setOf(olena, petro))
        val r = done(p.door().handle(group, copyToPetro.id, emoji("🔥")))
        assertEquals(target, r.target)
        assertEquals(WebMessengerGroups.feed(p.log, group.id).first().id, r.line.id)
        assertEquals(listOf(olena, petro), p.sent.map { it.first })
        assertEquals(listOf(dima), r.missed)
    }

    @Test
    fun `a line of another group, a direct line or an unknown id is 404 no_message`() {
        val p = olenasPhone()
        val door = p.door()
        for (id in listOf(otherLine.id, directLine.id, "nope")) {
            assertRefused(door.handle(group, id, emoji("👍")), 404, "no_message", Why.NO_MESSAGE)
        }
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `bad bodies are refused with the 1-to-1 codes and nothing is sent`() {
        val p = olenasPhone()
        val door = p.door()
        assertRefused(door.handle(group, copyToOlena.id, null), 400, "bad_body", Why.BAD_BODY)
        assertRefused(door.handle(group, copyToOlena.id, "not json"), 400, "bad_body", Why.BAD_BODY)
        assertRefused(door.handle(group, copyToOlena.id, "[]"), 400, "bad_body", Why.BAD_BODY)
        assertRefused(door.handle(group, copyToOlena.id, "{}"), 400, "empty", Why.EMPTY)
        assertRefused(door.handle(group, copyToOlena.id, emoji("   ")), 400, "empty", Why.EMPTY)
        assertRefused(door.handle(group, copyToOlena.id, emoji("x".repeat(70))), 422, "bad_mark", Why.BAD_MARK)
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `no keyed recipient is 409 no_key and the journal stays as it was`() {
        val p = olenasPhone(keyed = emptySet())
        assertRefused(p.door().handle(group, copyToOlena.id, emoji("👍")), 409, "no_key", Why.NO_KEY)
        assertTrue(p.sent.isEmpty())
        assertEquals(3, p.log.size)
    }

    @Test
    fun `nothing queued is 503 no_identity, as in 1-to-1, and a blank own id is the same`() {
        val dead = olenasPhone(failing = setOf(org, petro))
        assertRefused(dead.door().handle(group, copyToOlena.id, emoji("👍")), 503, "no_identity", Why.NO_IDENTITY)
        val nobody = Phone("", listOf(copyToOlena), keyed = setOf(org, petro))
        assertRefused(nobody.door().handle(group, copyToOlena.id, emoji("👍")), 503, "no_identity", Why.NO_IDENTITY)
        assertTrue(nobody.sent.isEmpty())
    }

    @Test
    fun `a member whose send failed is named in missed after the keyless ones`() {
        val p = olenasPhone(failing = setOf(petro))
        val r = done(p.door().handle(group, copyToOlena.id, emoji("👍")))
        assertEquals(listOf(org), p.sent.map { it.first })
        assertEquals(listOf(dima, petro), r.missed)
    }

    @Test
    fun `a removed member cannot react, 409 not_in_group`() {
        val p = olenasPhone()
        val without = group.copy(memberIds = listOf(org, petro, dima))
        assertRefused(p.door().handle(without, copyToOlena.id, emoji("👍")), 409, "not_in_group", Why.NOT_IN_GROUP)
        assertTrue(p.sent.isEmpty())
    }

    @Test
    fun `of keeps only members, drops emptied emojis and keeps order`() {
        val folded = linkedMapOf(
            "👍" to listOf(olena, stranger),
            "🔥" to listOf(stranger),
            "❤️" to listOf(petro),
        )
        val kept = WebMessengerGroupReactions.of(folded, group.memberIds)
        assertEquals(mapOf("👍" to listOf(olena), "❤️" to listOf(petro)), kept)
        assertEquals(listOf("👍", "❤️"), kept.keys.toList())
    }

    @Test
    fun `the receiver sees the reaction on its own copy, and a stranger's frame never counts`() {
        val fromOlena = PeerMessage(
            "in-1", olena, petro,
            PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, "👍"))!!,
            sentAt + 1_000,
        )
        val fromStranger = PeerMessage(
            "in-2", stranger, petro,
            PeerMark.encode(PeerMark.Mark(PeerMark.What.REACTION, target, true, "🔥"))!!,
            sentAt + 2_000,
        )
        val petrosLog = listOf(copyToPetro, fromOlena, fromStranger)
        val folded = PeerMark.foldAll(petrosLog, PeerMark.What.REACTION)
        assertEquals(
            mapOf("👍" to listOf(olena)),
            WebMessengerGroupReactions.onLine(folded, copyToPetro, group.memberIds),
        )
    }

    @Test
    fun `the row read back from the journal shows what the reply promised`() {
        val p = olenasPhone()
        val r = done(p.door().handle(group, copyToOlena.id, emoji("👍")))
        val folded = PeerMark.foldAll(p.log, PeerMark.What.REACTION)
        assertEquals(mapOf("👍" to listOf(olena)), WebMessengerGroupReactions.onLine(folded, r.line, group.memberIds))
        assertEquals(
            WebMessengerGroupReactions.onLine(folded, copyToOlena, group.memberIds),
            WebMessengerGroupReactions.onLine(folded, copyToPetro, group.memberIds),
        )
    }
}
