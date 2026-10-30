package local.phantom.companion.self

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import local.phantom.companion.self.Initiative.Decision
import local.phantom.companion.self.Initiative.Moment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentDueTest {

    private val zone = ZoneId.of("UTC")
    private val initStore = MemoryInitiativeStore()
    private val commitmentStore = InMemoryCommitmentStore()
    private val posted = mutableListOf<Pair<Commitment, Initiative.Visit>>()
    private val ini = Initiative(
        store = initStore,
        commitments = commitmentStore,
        onDueCommitment = { c, v -> posted += c to v },
        zone = { zone },
    )

    private fun at(day: Int, hour: Int, min: Int = 0): Long =
        LocalDateTime.of(2026, 9, day, hour, min).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `Initiative tick posts due commitment line once and attaches due metadata`() {
        val dueMs = at(27, 14, 0)
        commitmentStore.add(
            Commitment(
                id = "cmt_1",
                text = "Надішлю Олі фото сьогодні",
                personName = "Оля",
                peerId = "peer_olia",
                what = "фото",
                dueMs = dueMs,
                createdMs = at(27, 10, 0),
                sourceMessageId = "msg_1",
                state = CommitmentState.OPEN,
            ),
        )

        // Before due time -> Wait(dueMs)
        assertEquals(Decision.Wait(dueMs), ini.decide(Moment(at(27, 12, 0), onTheRoad = false)))
        assertTrue(posted.isEmpty())

        // At due time -> Come with short line «Оля: фото — сьогодні»
        val first = ini.decide(Moment(dueMs, onTheRoad = false))
        assertTrue("expected Decision.Come but got $first", first is Decision.Come)
        val visit = (first as Decision.Come).visit
        assertEquals(Initiative.Kind.COMMITMENT, visit.kind)
        assertEquals("Оля: фото — сьогодні", visit.body)
        assertEquals(1, posted.size)
        assertEquals("cmt_1", posted.single().first.id)

        // Subsequent ticks -> Nothing (posted only once)
        assertEquals(Decision.Nothing, ini.decide(Moment(at(27, 15, 0), onTheRoad = false)))
        assertEquals(Decision.Nothing, ini.decide(Moment(at(28, 10, 0), onTheRoad = false)))
        assertEquals(1, posted.size)
    }

    @Test
    fun `Initiative skips due commitment that was marked done or dropped before decide`() {
        val dueMs = at(27, 14, 0)
        commitmentStore.add(
            Commitment(
                id = "cmt_done",
                text = "Надішлю Олі фото сьогодні",
                personName = "Оля",
                peerId = "peer_olia",
                what = "фото",
                dueMs = dueMs,
                createdMs = at(27, 10, 0),
                sourceMessageId = "msg_1",
                state = CommitmentState.OPEN,
            ),
        )
        // Queue a commitment visit and then mark the commitment DONE in the store before it fires
        ini.offer(
            Initiative.Visit(
                id = "cmt-cmt_done",
                kind = Initiative.Kind.COMMITMENT,
                key = Initiative.COMMITMENT_KEY_PREFIX + "cmt_done",
                body = "Оля: фото — сьогодні",
                why = "",
                dueAtMs = dueMs,
            ),
        )
        commitmentStore.markDone("cmt_done")

        assertEquals(Decision.Nothing, ini.decide(Moment(dueMs, onTheRoad = false)))
        assertTrue(posted.isEmpty())
        assertTrue(PhantomVisits.isReminderNotificationKind(Initiative.Kind.COMMITMENT))
        assertTrue(PhantomVisits.isReminderNotificationKind(Initiative.Kind.REMINDER))
    }

    @Test
    fun `earliestDueMs picks earliest pending lesson, reminder or open commitment instead of later request`() {
        val now = at(27, 10, 0)
        val in10Min = at(27, 10, 10)
        val in30Min = at(27, 10, 30)
        val nextWeek = at(30, 9, 0)

        val state = Initiative.State(
            queue = listOf(
                Initiative.Visit("l1", Initiative.Kind.LESSON, "lesson.csharp", "Цикли", "чому", in30Min),
            ),
        )
        val openCommitments = listOf(
            Commitment(
                id = "c_soon",
                text = "Надішлю Олі фото",
                personName = "Оля",
                peerId = "p1",
                what = "фото",
                dueMs = in10Min,
                createdMs = now,
                sourceMessageId = "m1",
                state = CommitmentState.OPEN,
            ),
            Commitment(
                id = "c_late",
                text = "Поверну книгу",
                personName = "Петро",
                peerId = "p2",
                what = "книгу",
                dueMs = nextWeek,
                createdMs = now,
                sourceMessageId = "m2",
                state = CommitmentState.OPEN,
            ),
        )

        assertEquals(
            in10Min,
            PhantomVisits.earliestDueMs(state, openCommitments, requestedAtMs = nextWeek, nowMs = now),
        )
        assertEquals(
            in30Min,
            PhantomVisits.earliestDueMs(
                state.copy(announcedCommitments = setOf("c_soon")),
                openCommitments,
                requestedAtMs = nextWeek,
                nowMs = now,
            ),
        )
    }
}
