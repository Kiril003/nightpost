package local.phantom.companion.ui.messenger

import java.time.Instant
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMark
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.messenger.WebMessengerPlanRow.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * K2: a group that began with a meetup card carries a `plan` row, so the web can end it from the
 * conversation list even when `messages` is empty (the Studentská plan of 11 Oct never ended).
 *
 * Lines go through [WebMessengerGroups.feed] exactly as in `groupConversation`; marks are real
 * `PH-CST:` frames folded by [WebMessengerCardMarks.fold]. The organiser is the card's
 * authenticated sender, never the JSON `organizer` field.
 */
class WebMessengerPlanRowTest {

    private val org = "a1a1a1a1a1a1a1a1"
    private val olena = "b2b2b2b2b2b2b2b2"
    private val petro = "c3c3c3c3c3c3c3c3"
    private val stranger = "d4d4d4d4d4d4d4d4"
    private val group = PeerGroup.Group("0f0f0f0f0f0f0f0f", "Studentská · 19:00", listOf(org, olena, petro))

    private val h = 3_600_000L
    private val at = 1_760_200_000_000L
    private val now = at + 72 * h
    private val opened = at - 5 * h

    private fun cardJson(
        id: String = "mu-st",
        atMs: Long = at,
        expiresAt: Long? = at + 24 * h,
        slots: List<Long>? = null,
        organizer: String = org,
    ): String = buildString {
        append("""{"v":1,"id":"$id","venue":{"id":"v1","name":"Studentská"},"at":$atMs,"organizer":"$organizer",""")
        append(""""invitees":[],"expiresAt":${expiresAt ?: "null"}""")
        if (slots != null) append(""","slots":[${slots.joinToString(",")}]""")
        append("}")
    }

    private var seq = 0
    private fun line(from: String, text: String, sentAt: Long, deletedAt: Long = 0L) =
        PeerMessage(
            id = "line-${seq++}", fromId = from, toId = if (from == olena) org else olena,
            body = PeerGroup.encode(group, text), sentAtMs = sentAt, deletedAtMs = deletedAt,
        )

    private fun card(json: String, sentAt: Long = opened + 1_000, from: String = org, deletedAt: Long = 0L) =
        line(from, PeerCard.encode(PeerCard.Body(PeerCard.Card.MEETUP, json))!!, sentAt, deletedAt)

    private fun opener(from: String = org, sentAt: Long = opened) = line(from, "Started a group. Let's talk here.", sentAt)

    private fun act(sentAt: Long = opened - 1_000) = line(org, PeerGroup.encodeAct(PeerGroup.Act.Rename), sentAt)

    private fun mark(from: String, cardLine: PeerMessage, value: String, sentAt: Long, on: Boolean = true) =
        PeerMessage(
            id = "mark-${seq++}", fromId = from, toId = org,
            body = PeerMark.encode(PeerMark.Mark(PeerMark.What.CARD_STATE, WebMessengerCardMarks.targetOf(cardLine), on, value))!!,
            sentAtMs = sentAt,
        )

    private fun rowOf(log: List<PeerMessage>): Plan? =
        WebMessengerPlanRow.of(WebMessengerGroups.feed(log, group.id), group.memberIds) { WebMessengerCardMarks.fold(log) }

    private fun planOf(log: List<PeerMessage>): Plan {
        val p = rowOf(log)
        assertNotNull("a group that opens with the creator's line and a meetup card is a plan group", p)
        return p!!
    }

    private fun iso(ms: Long) = Instant.ofEpochMilli(ms).toString()

    @Test
    fun `the Studentska case - expired card, no marks - the row says expired with an ISO end`() {
        val c = card(cardJson())
        val p = planOf(listOf(opener(), c))
        assertEquals(Plan.State.EXPIRED, p.state(now))
        assertEquals(at + 24 * h, p.expiresAtMs)
        assertEquals("mu-st", p.meetupId)
        assertEquals(org, p.organizerId)
        val json = WebMessengerPlanRow.json(p).toString()
        assertTrue("expires_at is not the ISO instant: $json", json.contains(""""expires_at":"${iso(at + 24 * h)}""""))
        assertTrue("cancelled is not false: $json", json.contains(""""cancelled":false"""))
    }

    @Test
    fun `keep by a member keeps an expired plan, keep by a stranger does not`() {
        val c = card(cardJson())
        val kept = planOf(listOf(opener(), c, mark(olena, c, "keep:1", now - h)))
        assertEquals(listOf(olena), kept.keptBy)
        assertEquals("KEPT must win over EXPIRED", Plan.State.KEPT, kept.state(now))

        val foreign = planOf(listOf(opener(), c, mark(stranger, c, "keep:1", now - h)))
        assertEquals(emptyList<String>(), foreign.keptBy)
        assertEquals(Plan.State.EXPIRED, foreign.state(now))
    }

    @Test
    fun `cancel by the card author cancels, cancel by another member is dropped`() {
        val c = card(cardJson(expiresAt = now + 24 * h))
        val byOrg = planOf(listOf(opener(), c, mark(org, c, "cancel:1", now - h)))
        assertTrue(byOrg.cancelled)
        assertEquals(Plan.State.CANCELLED, byOrg.state(now))

        val byOlena = planOf(listOf(opener(), c, mark(olena, c, "cancel:1", now - h)))
        assertFalse("only the organiser may cancel", byOlena.cancelled)
        assertEquals(Plan.State.ACTIVE, byOlena.state(now))
    }

    @Test
    fun `a move by the author resets the end, a move by another member is ignored`() {
        val c = card(cardJson())
        val min = (now + 2 * h) / 60_000
        val moved = planOf(listOf(opener(), c, mark(org, c, "at:$min", now - h)))
        assertEquals(min * 60_000, moved.atMs)
        assertEquals(min * 60_000 + WebMessengerPlanRow.TTL_MS, moved.expiresAtMs)
        assertEquals("the card's own end is past, the moved one is not", Plan.State.ACTIVE, moved.state(now))

        val foreign = planOf(listOf(opener(), c, mark(olena, c, "at:$min", now - h)))
        assertEquals(at, foreign.atMs)
        assertEquals(at + 24 * h, foreign.expiresAtMs)
        assertEquals(Plan.State.EXPIRED, foreign.state(now))
    }

    @Test
    fun `two moves held by the author - the later time wins, not the later frame`() {
        val c = card(cardJson())
        val late = (now + 5 * h) / 60_000
        val early = (now + 2 * h) / 60_000
        val p = planOf(listOf(opener(), c, mark(org, c, "at:$late", now - 2 * h), mark(org, c, "at:$early", now - h)))
        assertEquals(late * 60_000, p.atMs)
        assertEquals(late * 60_000 + WebMessengerPlanRow.TTL_MS, p.expiresAtMs)
    }

    @Test
    fun `slots card - no lock means no time yet, only the author's lock picks one`() {
        val slots = listOf(at, at + 24 * h, at + 48 * h)
        val c = card(cardJson(slots = slots, expiresAt = at + 72 * h))
        assertNull("voting is still open", planOf(listOf(opener(), c)).atMs)
        assertEquals(slots[1], planOf(listOf(opener(), c, mark(org, c, "lock:1", now - h))).atMs)
        assertNull("a member's lock is not the organiser's pick", planOf(listOf(opener(), c, mark(olena, c, "lock:1", now - h))).atMs)
    }

    @Test
    fun `a permanent group never ends, even when the author cancelled`() {
        val c = card(cardJson(expiresAt = null))
        val p = planOf(listOf(opener(), c))
        assertNull(p.expiresAtMs)
        assertEquals(Plan.State.PERMANENT, p.state(now))

        val cancelled = planOf(listOf(opener(), c, mark(org, c, "cancel:1", now - h)))
        assertTrue("the row still tells the truth", cancelled.cancelled)
        assertEquals(Plan.State.PERMANENT, cancelled.state(now))
        assertTrue(WebMessengerPlanRow.json(cancelled).toString().contains(""""expires_at":null"""))
    }

    @Test
    fun `position rule - only the creator's single opener may precede the card`() {
        val c = card(cardJson())
        assertNotNull("[creator opener, card]", rowOf(listOf(opener(), c)))
        assertNotNull("[act, creator opener, card]", rowOf(listOf(act(), opener(), c)))
        assertNotNull("[card]", rowOf(listOf(c)))
        assertNull("[other member's text, card]", rowOf(listOf(opener(from = olena), c)))
        assertNull("[two texts, card]", rowOf(listOf(opener(sentAt = opened - 10), opener(sentAt = opened), c)))
    }

    @Test
    fun `the newest live card decides, a deleted newest card is skipped`() {
        val old = card(cardJson(id = "mu-old"), sentAt = opened + 1_000)
        val fresh = card(cardJson(id = "mu-new", atMs = now + 2 * h, expiresAt = now + 26 * h), sentAt = now - h)
        val p = planOf(listOf(opener(), old, fresh))
        assertEquals("mu-new", p.meetupId)
        assertEquals(Plan.State.ACTIVE, p.state(now))

        val gone = card(cardJson(id = "mu-new", atMs = now + 2 * h, expiresAt = now + 26 * h), sentAt = now - h, deletedAt = now - 1_000)
        val q = planOf(listOf(opener(), old, gone))
        assertEquals("mu-old", q.meetupId)
        assertEquals(Plan.State.EXPIRED, q.state(now))
    }

    @Test
    fun `an unparsable card payload gives no plan, the same group with a valid card does`() {
        assertNotNull(rowOf(listOf(opener(), card(cardJson()))))
        assertNull(rowOf(listOf(opener(), card("""{"v":1,"id":"""))))
    }

    @Test
    fun `json shape is frozen - key names, order and nulls`() {
        val full = Plan(
            meetupId = "mu-st", venue = "Studentská", organizerId = org, atMs = at, expiresAtMs = at + 24 * h,
            keptBy = listOf(olena), cancelled = false,
        )
        assertEquals(
            """{"meetup_id":"mu-st","venue":"Studentská","organizer":"$org","at":"${iso(at)}",""" +
                """"expires_at":"${iso(at + 24 * h)}","kept_by":["$olena"],"cancelled":false}""",
            WebMessengerPlanRow.json(full).toString(),
        )
        val bare = full.copy(venue = null, atMs = null, expiresAtMs = null, keptBy = emptyList(), cancelled = true)
        assertEquals(
            """{"meetup_id":"mu-st","venue":null,"organizer":"$org","at":null,"expires_at":null,"kept_by":[],"cancelled":true}""",
            WebMessengerPlanRow.json(bare).toString(),
        )
    }
}
