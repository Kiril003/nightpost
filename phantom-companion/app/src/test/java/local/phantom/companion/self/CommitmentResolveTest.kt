package local.phantom.companion.self

import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.memory.MemoryFactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommitmentResolveTest {

    private val zone: ZoneId = ZoneId.of("Europe/Kyiv")
    private var clock: Long = LocalDateTime.of(2026, 9, 27, 10, 0).atZone(zone).toInstant().toEpochMilli()
    private val store = InMemoryCommitmentStore()
    private val roster = mutableListOf<CommitmentResolver.RosterPerson>()
    private val service = CommitmentService(
        store = store,
        roster = { roster.toList() },
        now = { clock },
        zone = { zone },
    )
    private val memory = SelfMemory(MemoryFactStore(FakeFactDao())) { clock }
    private val self = PhantomSelf(
        memory = memory,
        tutor = CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
        hasBrain = { false },
        render = ::plain,
        commitments = service,
        now = { clock },
    )

    @Test
    fun `zero roster matches stores commitment with name as written and no peer link`() = runBlocking {
        roster.clear()
        val reply = self.answer("Надішлю Олі фото завтра")
        assertNotNull(reply)
        val open = store.listOpen()
        assertEquals(1, open.size)
        val saved = open.single()
        assertNull(saved.peerId)
        assertEquals("Оля", saved.personName)
        assertEquals("фото", saved.what)
        assertEquals(CommitmentState.OPEN, saved.state)
        assertNull("no card note for 0 matches", service.peekLastNote())
    }

    @Test
    fun `one roster match stores commitment linked to that peer id`() = runBlocking {
        roster.clear()
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_1", name = "Оля Коваль", circle = "friends")
        val reply = self.answer("Надішлю Олі фото завтра")
        assertNotNull(reply)
        val open = store.listOpen()
        assertEquals(1, open.size)
        val saved = open.single()
        assertEquals("peer_olia_1", saved.peerId)
        assertEquals("Оля Коваль", saved.personName)
        assertEquals("фото", saved.what)
    }

    @Test
    fun `two Olias in roster returns choose card without writing to store until chosen`() = runBlocking {
        roster.clear()
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_1", name = "Оля Коваль", circle = "family")
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_2", name = "Оля Мельник", circle = "work")

        val reply = self.answer("Надішлю Ользі фото завтра")
        assertNotNull(reply)
        assertTrue("must not write before disambiguation", store.listAll().isEmpty())

        val note = service.peekLastNote()
        assertNotNull("choose card note must be prepared", note)
        assertEquals("choose", note!!.kind)
        assertEquals(2, note.candidates.size)
        assertEquals("peer_olia_1", note.candidates[0].peerId)
        assertEquals("Оля Коваль", note.candidates[0].name)
        assertEquals("family", note.candidates[0].hint)
        assertEquals("peer_olia_2", note.candidates[1].peerId)

        val chosen = service.choose(note.pendingId!!, "peer_olia_2")
        assertNotNull(chosen)
        assertEquals("peer_olia_2", chosen!!.peerId)
        assertEquals("Оля Мельник", chosen.personName)
        assertEquals("фото", chosen.what)
        assertEquals(1, store.listOpen().size)
        assertEquals("peer_olia_2", store.listOpen().single().peerId)
    }

    @Test
    fun `what did I promise lists open commitments and done marks it done`() = runBlocking {
        roster.clear()
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_1", name = "Оля")
        self.answer("Надішлю Олі фото завтра")

        val listReply = self.answer("Що я обіцяв?")!!
        assertTrue(listReply.contains("Оля"))
        assertTrue(listReply.contains("фото"))

        val c = store.listOpen().single()
        assertTrue(service.done(c.id))
        assertTrue(store.listOpen().isEmpty())
        assertEquals(CommitmentState.DONE, store.get(c.id)?.state)
        assertEquals("COMMITMENT_NONE()", self.answer("what did I promise?"))
    }

    @Test
    fun `choose with unknown or already-resolved pendingId returns null without popping another pending commitment`() = runBlocking {
        local.phantom.companion.data.CommitmentNotes.clearInMemory()
        roster.clear()
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_1", name = "Оля Коваль", circle = "family")
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_2", name = "Оля Мельник", circle = "work")
        roster += CommitmentResolver.RosterPerson(id = "peer_sashko_1", name = "Сашко Петренко", circle = "family")
        roster += CommitmentResolver.RosterPerson(id = "peer_sashko_2", name = "Сашко Іваненко", circle = "work")

        self.answer("Надішлю Олі фото завтра")
        val oliaNote = service.takeLastNote()!!
        self.answer("Скину Сашкові звіт завтра")
        val sashkoNote = service.takeLastNote()!!

        // Unknown pendingId must not touch Sashko's pending commitment
        assertNull(service.choose("pend_unknown", "peer_olia_1"))
        assertTrue(store.listAll().isEmpty())

        // Resolve Olia once, then double-tap Olia's pendingId -> second tap must return null and leave Sashko intact
        val firstChosen = service.choose(oliaNote.pendingId!!, "peer_olia_1")
        assertNotNull(firstChosen)
        assertNull("double tap on resolved pendingId must return null", service.choose(oliaNote.pendingId!!, "peer_olia_2"))
        assertEquals(1, store.listOpen().size)

        // Sashko's pending commitment is still intact and resolves properly
        val sashkoChosen = service.choose(sashkoNote.pendingId!!, "peer_sashko_2")
        assertNotNull(sashkoChosen)
        assertEquals("peer_sashko_2", sashkoChosen!!.peerId)
        assertEquals("Сашко Іваненко", sashkoChosen.personName)
        assertEquals(2, store.listOpen().size)
    }

    @Test
    fun `choose reconstructs pending commitment from CommitmentNotes after process restart and updates note state`() = runBlocking {
        local.phantom.companion.data.CommitmentNotes.clearInMemory()
        roster.clear()
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_1", name = "Оля Коваль", circle = "family")
        roster += CommitmentResolver.RosterPerson(id = "peer_olia_2", name = "Оля Мельник", circle = "work")

        val parsed = CommitmentSpeech.parse("Надішлю Олі фото завтра", clock, zone)!!
        val res = service.record("Надішлю Олі фото завтра", parsed) as CommitmentResolver.Resolution.Ambiguous
        service.bindLastNoteToMessage(null, "msg_restart_1")

        // Simulate process death: new CommitmentService with empty pendingById, but CommitmentNotes retains the note
        val freshService = CommitmentService(
            store = store,
            roster = { roster.toList() },
            now = { clock },
            zone = { zone },
        )
        val chosen = freshService.choose(res.pending.id, "peer_olia_2")
        assertNotNull("must reconstruct pending from stored note", chosen)
        assertEquals("peer_olia_2", chosen!!.peerId)
        assertEquals("Оля Мельник", chosen.personName)
        assertEquals("фото", chosen.what)

        val updatedNote = local.phantom.companion.data.CommitmentNotes.get(null, "msg_restart_1")
        assertNotNull(updatedNote)
        assertEquals("saved", updatedNote!!.kind)
        assertEquals(chosen.id, updatedNote.commitmentId)
        assertNull("second choose after reconstruction must return null", freshService.choose(res.pending.id, "peer_olia_1"))
    }

    private fun seed(id: String, person: String, what: String, due: Long?, createdAgoMin: Long = 600): Commitment =
        Commitment(id, "$person: $what", person, null, what, due, clock - createdAgoMin * 60_000, "").also(store::add)

    private val minute = 60_000L

    @Test
    fun `doneDue closes the promise that is due, not the newer one that is not`() {
        seed("due", "Оля", "фото", due = clock - 30 * minute, createdAgoMin = 300)
        seed("later", "Петро", "дзвінок", due = clock + 24 * 60 * minute, createdAgoMin = 1)

        val res = service.doneDue()
        assertEquals("due", (res as? CommitmentResolver.DoneDue.Done)?.commitment?.id)
        assertEquals(CommitmentState.DONE, store.get("due")!!.state)
        assertEquals(CommitmentState.OPEN, store.get("later")!!.state)
    }

    @Test
    fun `a bare done in the chat answers with the due promise`() = runBlocking {
        seed("due", "Оля", "фото", due = clock - 30 * minute, createdAgoMin = 300)
        seed("later", "Петро", "дзвінок", due = clock + 24 * 60 * minute, createdAgoMin = 1)

        assertEquals("COMMITMENT_MARKED_DONE(Оля,фото)", self.answer("готово"))
        assertEquals(CommitmentState.OPEN, store.get("later")!!.state)
    }

    @Test
    fun `two promises due within two hours are a question, nothing is closed`() = runBlocking {
        seed("older", "Петро", "дзвінок", due = clock - 100 * minute)
        seed("newer", "Оля", "фото", due = clock - 10 * minute)

        val res = service.doneDue()
        assertEquals(listOf("newer", "older"), (res as? CommitmentResolver.DoneDue.Ambiguous)?.candidates?.map { it.id })
        assertEquals(2, store.listOpen().size)

        assertEquals("COMMITMENT_DONE_WHICH()\n\n• Оля: фото\n• Петро: дзвінок", self.answer("done"))
        assertEquals(2, store.listOpen().size)
    }

    @Test
    fun `promises due more than two hours apart are not confused, the newest due one is closed`() {
        seed("old", "Петро", "дзвінок", due = clock - 4 * 60 * minute)
        seed("new", "Оля", "фото", due = clock - 1 * minute)

        val res = service.doneDue()
        assertEquals("new", (res as? CommitmentResolver.DoneDue.Done)?.commitment?.id)
        assertEquals(CommitmentState.OPEN, store.get("old")!!.state)
    }

    @Test
    fun `with nothing due the newest created open promise is closed, and nothing open is None`() {
        assertEquals(CommitmentResolver.DoneDue.None, service.doneDue())

        seed("a", "Петро", "дзвінок", due = null, createdAgoMin = 500)
        seed("b", "Оля", "фото", due = clock + 60 * minute, createdAgoMin = 20)
        val res = service.doneDue()
        assertEquals("b", (res as? CommitmentResolver.DoneDue.Done)?.commitment?.id)
        assertEquals(CommitmentState.OPEN, store.get("a")!!.state)
    }
}
