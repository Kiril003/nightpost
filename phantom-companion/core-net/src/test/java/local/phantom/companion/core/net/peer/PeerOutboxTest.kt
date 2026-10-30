package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What happens to a message nobody could deliver. On a mesh built for a city
 * under jamming, «dropped it and showed a red dot» is the one answer that is
 * not allowed.
 */
class PeerOutboxTest {

    private fun msg(id: String, at: Long = 0L, to: String = "them") = PeerMessage(
        id = id, fromId = "me", toId = to, body = "текст", sentAtMs = at,
    )

    @Test
    fun `a queued message is due immediately`() {
        val out = PeerOutbox.enqueue(emptyList(), msg("a"))
        assertEquals(1, PeerOutbox.due(out, 0L).size)
    }

    @Test
    fun `re-enqueueing the same id does not double the message`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.enqueue(out, msg("a"))
        assertEquals(1, out.size)
    }

    @Test
    fun `order is what makes a conversation`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("late", at = 900L))
        out = PeerOutbox.enqueue(out, msg("early", at = 100L))
        assertEquals(listOf("early", "late"), PeerOutbox.due(out, 1_000L).map { it.id })
    }

    @Test
    fun `a failed try waits longer each time instead of hammering the radio`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markFailed(out, "a", nowMs = 1_000L)
        assertTrue("must not be due immediately", PeerOutbox.due(out, 1_000L).isEmpty())
        assertEquals(1, PeerOutbox.due(out, 1_000L + PeerOutbox.BACKOFF_MS[0]).size)

        out = PeerOutbox.markFailed(out, "a", nowMs = 10_000L)
        assertTrue(PeerOutbox.backoffFor(2) > PeerOutbox.backoffFor(1))
    }

    @Test
    fun `a message given up on is still there — never silently gone`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markFailed(out, "a", nowMs = PeerOutbox.LIFETIME_MS)
        assertEquals(1, out.size)
        assertEquals(PeerMessage.State.FAILED, out[0].state)
        assertTrue("a dead message must stop costing battery", PeerOutbox.due(out, Long.MAX_VALUE).isEmpty())
    }

    @Test
    fun `twenty refusals in three hours leave the letter queued and due again`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a", at = 0L))
        val step = 9 * 60_000L
        repeat(20) { out = PeerOutbox.markFailed(out, "a", nowMs = it * step) { ms -> ms } }
        assertEquals(PeerMessage.State.QUEUED, out[0].state)
        assertEquals(20, out[0].attempts)
        assertEquals(1, PeerOutbox.due(out, 19 * step + PeerOutbox.BACKOFF_MS.last()).size)
    }

    @Test
    fun `refusals back off to half an hour and no further`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        var at = 0L
        repeat(100) {
            out = PeerOutbox.markFailed(out, "a", nowMs = at) { ms -> ms }
            assertTrue(out[0].nextAttemptMs - at <= PeerOutbox.BACKOFF_MS.last())
            at = out[0].nextAttemptMs
        }
        assertEquals(PeerMessage.State.QUEUED, out[0].state)
        assertEquals(PeerOutbox.BACKOFF_MS.last(), PeerOutbox.backoffFor(out[0].attempts))
    }

    @Test
    fun `a letter lives seven days, as long as the relay holds it`() {
        assertEquals(PeerRelay.TTL_MS, PeerOutbox.LIFETIME_MS)
        val day = PeerRelay.EPOCH_MS
        val out = PeerOutbox.enqueue(emptyList(), msg("a", at = 1_000L))
        val lastDay = 1_000L + 7 * day - 1
        assertEquals(PeerMessage.State.QUEUED, PeerOutbox.markFailed(out, "a", lastDay)[0].state)
        assertEquals(PeerMessage.State.QUEUED, PeerOutbox.markUnreached(out, "a", lastDay)[0].state)
        val week = 1_000L + 7 * day
        assertEquals(PeerMessage.State.FAILED, PeerOutbox.markFailed(out, "a", week)[0].state)
        assertEquals(PeerMessage.State.FAILED, PeerOutbox.markUnreached(out, "a", week)[0].state)
    }

    @Test
    fun `a letter back from an expired relay copy fails on its next try once the week is out`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a", at = 0L))
        out = PeerOutbox.markHeld(out, "a", "bm9uY2U", heldUntilMs = PeerRelay.TTL_MS + 60_000L)
        out = PeerOutbox.markExpiredHeld(out, "a")
        assertEquals(PeerMessage.State.QUEUED, out[0].state)
        out = PeerOutbox.markUnreached(out, "a", PeerRelay.TTL_MS + 120_000L)
        assertEquals(PeerMessage.State.FAILED, out[0].state)
    }

    /**
     * Виміряна драбина смерті: 2с → 10с → 60с → 5хв → 30хв×3 → FAILED назавжди,
     * і ніхто не воскрешає. 96 хвилин — і слово втрачене за те, що другий
     * телефон був вимкнений. Тиша більше не платить цією монетою.
     */
    @Test
    fun `silence never kills a letter, however long nobody is reachable`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a", at = 0L))
        var at = 0L
        repeat(200) {
            out = PeerOutbox.markUnreached(out, "a", nowMs = at)
            at = out[0].nextAttemptMs
        }
        assertTrue("більше за виміряні 96 хвилин", at > 96 * 60_000L)
        assertEquals(PeerMessage.State.QUEUED, out[0].state)
        assertEquals("тиша не витрачає спроб", 0, out[0].attempts)
        assertEquals("лист лишається винним", 1, PeerOutbox.due(out, at).size)
    }

    @Test
    fun `an unreached letter waits longer as it ages, never faster than half an hour`() {
        val out = PeerOutbox.enqueue(emptyList(), msg("a", at = 0L))
        val young = PeerOutbox.markUnreached(out, "a", nowMs = 1_000L)[0].nextAttemptMs - 1_000L
        val old = PeerOutbox.markUnreached(out, "a", nowMs = 7_200_000L)[0]
            .nextAttemptMs - 7_200_000L
        assertTrue("свіжий лист пробують частіше", young < old)
        assertEquals(PeerOutbox.UNREACHED_MS.last(), old)
    }

    /** FAILED — факт, і тиша його не скасовує. */
    @Test
    fun `a letter already given up on is not resurrected by an unreachable tick`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markFailed(out, "a", nowMs = PeerOutbox.LIFETIME_MS)
        out = PeerOutbox.markUnreached(out, "a", nowMs = 1_000L)
        assertEquals(PeerMessage.State.FAILED, out[0].state)
    }

    @Test
    fun `the operator asking again gives it a clean start`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markFailed(out, "a", nowMs = PeerOutbox.LIFETIME_MS)
        out = PeerOutbox.retry(out, "a")
        assertEquals(PeerMessage.State.QUEUED, out[0].state)
        assertEquals(1, PeerOutbox.due(out, 0L).size)
    }

    @Test
    fun `a sent message is still retried until the peer confirms`() {
        // Handing it to the transport is not proof anyone received it.
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markSent(out, "a")
        assertEquals(1, PeerOutbox.due(out, 0L).size)
        out = PeerOutbox.markDelivered(out, "a")
        assertTrue(PeerOutbox.due(out, 0L).isEmpty())
    }

    @Test
    fun `pruning forgets only what was delivered`() {
        var out = emptyList<PeerMessage>()
        repeat(PeerOutbox.MAX_PER_PEER + 20) { i ->
            out = PeerOutbox.enqueue(out, msg("d$i", at = i.toLong()))
            if (i < PeerOutbox.MAX_PER_PEER) out = PeerOutbox.markDelivered(out, "d$i")
        }
        val pruned = PeerOutbox.prune(out, "them")
        assertEquals(PeerOutbox.MAX_PER_PEER, pruned.size)
        assertTrue(
            "an undelivered message must survive pruning",
            pruned.none { it.state == PeerMessage.State.QUEUED && it.id !in pruned.map { p -> p.id } },
        )
        assertEquals(20, pruned.count { it.state == PeerMessage.State.QUEUED })
    }

    @Test
    fun `nothing is pruned while the log is small`() {
        val out = PeerOutbox.enqueue(emptyList(), msg("a"))
        assertEquals(1, PeerOutbox.prune(out, "them").size)
    }

    @Test
    fun `a conversation holds both directions`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("mine", at = 1L, to = "marta"))
        out = PeerOutbox.enqueue(
            out,
            PeerMessage(id = "hers", fromId = "marta", toId = "me", body = "ок", sentAtMs = 2L),
        )
        assertEquals(listOf("mine", "hers"), PeerOutbox.conversation(out, "marta").map { it.id })
    }

    @Test
    fun `waiting counts what the operator is owed an answer about`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.enqueue(out, msg("b"))
        out = PeerOutbox.markDelivered(out, "a")
        assertEquals(1, PeerOutbox.waiting(out))
    }

    @Test
    fun `the first retry after a refusal is half a second, never later than the ladder`() {
        assertEquals(listOf(500L, 2_000L, 5_000L, 15_000L, 60_000L), PeerOutbox.BACKOFF_MS.take(5))
        repeat(50) {
            val out = PeerOutbox.markFailed(PeerOutbox.enqueue(emptyList(), msg("a")), "a", nowMs = 0L)
            assertTrue(out[0].nextAttemptMs in 400L..500L)
        }
    }

    @Test
    fun `a path coming back wakes what waits, without forgiving any refusal`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.enqueue(out, msg("b"))
        out = PeerOutbox.markFailed(out, "a", nowMs = 1_000L) { it }
        out = PeerOutbox.markUnreached(out, "b", 1_000L)
        assertTrue(PeerOutbox.due(out, 1_001L).isEmpty())
        val woke = PeerOutbox.wake(out, 1_001L)
        assertEquals(listOf("a", "b"), PeerOutbox.due(woke, 1_001L).map { it.id })
        assertEquals(1, woke.single { it.id == "a" }.attempts)
        assertTrue("нічого спати — той самий список", PeerOutbox.wake(woke, 1_001L) === woke)
    }

    @Test
    fun `the direct path and the relay racing never walk a letter backwards`() {
        var out = PeerOutbox.enqueue(emptyList(), msg("a"))
        out = PeerOutbox.markHeld(out, "a", "bm9uY2U", 99L)
        out = PeerOutbox.markSent(out, "a")
        assertEquals(PeerMessage.State.HELD, out[0].state)
        out = PeerOutbox.markDelivered(out, "a")
        out = PeerOutbox.markHeld(out, "a", "bm9uY2Uy", 99L)
        out = PeerOutbox.markFailed(out, "a", 5L)
        assertEquals(PeerMessage.State.DELIVERED, out[0].state)
        assertEquals("копію в сховку ще треба впізнати й спалити", "bm9uY2Uy", out[0].heldNonce)
        assertEquals(listOf("a"), PeerOutbox.receiptable(out, "them").map { it.id })
    }
}
