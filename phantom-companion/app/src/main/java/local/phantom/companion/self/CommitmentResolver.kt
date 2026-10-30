package local.phantom.companion.self

import java.time.ZoneId
import java.util.UUID
import local.phantom.companion.data.CommitmentCardNote
import local.phantom.companion.data.CommitmentNotes

/**
 * Розвʼязання згаданої людини проти справжнього ростера власника:
 * 0 збігів — записуємо як написано без peer_id;
 * 1 збіг — записуємо з peer_id;
 * 2+ збігів — не записуємо до вибору власника на картці.
 */
object CommitmentResolver {

    data class RosterPerson(
        val id: String,
        val name: String,
        val circle: String? = null,
        val lastSeenMs: Long = 0L,
    ) {
        fun hint(): String? {
            val c = circle?.trim()?.takeIf { it.isNotEmpty() && it != "all" }
            if (c != null) return c
            return null
        }
    }

    data class CandidateChip(
        val peerId: String,
        val name: String,
        val hint: String? = null,
    )

    data class PendingCommitment(
        val id: String,
        val text: String,
        val parsed: CommitmentSpeech.ParsedCommitment,
        val candidates: List<CandidateChip>,
        val createdMs: Long,
        val sourceMessageId: String,
    )

    sealed interface Resolution {
        data class Saved(val commitment: Commitment, val matchedPeer: RosterPerson?) : Resolution
        data class Ambiguous(val pending: PendingCommitment) : Resolution
    }

    sealed interface DoneDue {
        data class Done(val commitment: Commitment) : DoneDue
        data class Ambiguous(val candidates: List<Commitment>) : DoneDue
        data object None : DoneDue
    }

    fun matchRoster(mention: String, people: List<RosterPerson>): List<RosterPerson> =
        people.filter { person ->
            person.name.isNotBlank() && CommitmentSpeech.matchesFirstName(mention, person.name)
        }
}

class CommitmentService(
    val store: CommitmentStore,
    private val roster: () -> List<CommitmentResolver.RosterPerson>,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val onScheduled: (Long) -> Unit = {},
    private val appContext: android.content.Context? = null,
) {
    private val pendingById = LinkedHashMap<String, CommitmentResolver.PendingCommitment>()

    @Volatile
    private var lastNote: CommitmentCardNote? = null

    fun peekLastNote(): CommitmentCardNote? = lastNote

    fun takeLastNote(): CommitmentCardNote? = lastNote.also { lastNote = null }

    fun zoneId(): ZoneId = zone()

    fun rosterNames(): List<String> = roster().map { it.name }

    @Synchronized
    fun record(
        text: String,
        parsed: CommitmentSpeech.ParsedCommitment,
        sourceMessageId: String = "",
    ): CommitmentResolver.Resolution {
        val matches = CommitmentResolver.matchRoster(parsed.personRaw, roster())
            .ifEmpty { CommitmentResolver.matchRoster(parsed.person, roster()) }
        val nowMs = now()
        return when (matches.size) {
            0 -> {
                val commitment = Commitment(
                    id = "cmt_" + UUID.randomUUID().toString().take(12),
                    text = text,
                    personName = parsed.person,
                    peerId = null,
                    what = parsed.what,
                    dueMs = parsed.dueMs,
                    createdMs = nowMs,
                    sourceMessageId = sourceMessageId,
                    state = CommitmentState.OPEN,
                )
                store.add(commitment)
                lastNote = null
                commitment.dueMs?.let(onScheduled)
                CommitmentResolver.Resolution.Saved(commitment, null)
            }
            1 -> {
                val peer = matches.single()
                val commitment = Commitment(
                    id = "cmt_" + UUID.randomUUID().toString().take(12),
                    text = text,
                    personName = peer.name,
                    peerId = peer.id,
                    what = parsed.what,
                    dueMs = parsed.dueMs,
                    createdMs = nowMs,
                    sourceMessageId = sourceMessageId,
                    state = CommitmentState.OPEN,
                )
                store.add(commitment)
                val savedNote = CommitmentCardNote(
                    kind = "saved",
                    commitmentId = commitment.id,
                    pendingId = null,
                    person = peer.name,
                    peerId = peer.id,
                    what = commitment.what,
                    dueMs = commitment.dueMs,
                    candidates = emptyList(),
                    state = commitment.state.wire,
                    atMs = nowMs,
                )
                lastNote = savedNote
                if (sourceMessageId.isNotBlank()) {
                    CommitmentNotes.put(appContext, sourceMessageId, savedNote)
                }
                commitment.dueMs?.let(onScheduled)
                CommitmentResolver.Resolution.Saved(commitment, peer)
            }
            else -> {
                val candidates = matches.map { p ->
                    CommitmentResolver.CandidateChip(
                        peerId = p.id,
                        name = p.name,
                        hint = p.hint(),
                    )
                }
                val pending = CommitmentResolver.PendingCommitment(
                    id = "pend_" + UUID.randomUUID().toString().take(12),
                    text = text,
                    parsed = parsed,
                    candidates = candidates,
                    createdMs = nowMs,
                    sourceMessageId = sourceMessageId,
                )
                pendingById[pending.id] = pending
                val chooseNote = CommitmentCardNote(
                    kind = "choose",
                    commitmentId = null,
                    pendingId = pending.id,
                    person = parsed.person,
                    peerId = null,
                    what = parsed.what,
                    dueMs = parsed.dueMs,
                    candidates = candidates,
                    state = CommitmentState.OPEN.wire,
                    atMs = nowMs,
                )
                lastNote = chooseNote
                if (sourceMessageId.isNotBlank()) {
                    CommitmentNotes.put(appContext, sourceMessageId, chooseNote)
                }
                CommitmentResolver.Resolution.Ambiguous(pending)
            }
        }
    }

    private fun reconstructPending(pendingId: String): CommitmentResolver.PendingCommitment? {
        val (msgId, note) = CommitmentNotes.findByPendingId(appContext, pendingId) ?: return null
        return CommitmentResolver.PendingCommitment(
            id = pendingId,
            text = if (note.what.isNotBlank()) "${note.person}: ${note.what}" else note.person,
            parsed = CommitmentSpeech.ParsedCommitment(
                person = note.person,
                personRaw = note.person,
                what = note.what,
                dueMs = note.dueMs,
            ),
            candidates = note.candidates,
            createdMs = note.atMs,
            sourceMessageId = msgId,
        )
    }

    @Synchronized
    fun choose(pendingId: String, peerId: String): Commitment? {
        if (pendingId.isBlank()) return null
        val pending = pendingById.remove(pendingId)
            ?: reconstructPending(pendingId)
            ?: return null
        val chosenName = pending.candidates.firstOrNull { it.peerId == peerId }?.name
            ?: roster().firstOrNull { it.id == peerId }?.name
            ?: pending.parsed.person
        val nowMs = now()
        val commitment = Commitment(
            id = "cmt_" + UUID.randomUUID().toString().take(12),
            text = pending.text,
            personName = chosenName,
            peerId = peerId,
            what = pending.parsed.what,
            dueMs = pending.parsed.dueMs,
            createdMs = nowMs,
            sourceMessageId = pending.sourceMessageId,
            state = CommitmentState.OPEN,
        )
        store.add(commitment)
        val updatedNote = CommitmentCardNote(
            kind = "saved",
            commitmentId = commitment.id,
            pendingId = pending.id,
            person = chosenName,
            peerId = peerId,
            what = commitment.what,
            dueMs = commitment.dueMs,
            candidates = emptyList(),
            state = CommitmentState.OPEN.wire,
            atMs = nowMs,
        )
        lastNote = updatedNote
        if (pending.sourceMessageId.isNotBlank()) {
            CommitmentNotes.put(appContext, pending.sourceMessageId, updatedNote)
        }
        commitment.dueMs?.let(onScheduled)
        return commitment
    }

    @Synchronized
    fun addDirect(
        personName: String,
        peerId: String?,
        what: String,
        text: String = "$personName: $what",
        dueMs: Long? = null,
        sourceMessageId: String = "",
    ): Commitment {
        if (sourceMessageId.isNotBlank()) {
            store.listOpen().firstOrNull { it.sourceMessageId == sourceMessageId }?.let { return it }
        }
        val nowMs = now()
        val commitment = Commitment(
            id = "cmt_" + UUID.randomUUID().toString().take(12),
            text = text,
            personName = personName,
            peerId = peerId,
            what = what,
            dueMs = dueMs,
            createdMs = nowMs,
            sourceMessageId = sourceMessageId,
            state = CommitmentState.OPEN,
        )
        store.add(commitment)
        commitment.dueMs?.let(onScheduled)
        val note = CommitmentCardNote(
            kind = "saved",
            commitmentId = commitment.id,
            pendingId = null,
            person = personName,
            peerId = peerId,
            what = what,
            dueMs = dueMs,
            candidates = emptyList(),
            state = CommitmentState.OPEN.wire,
            atMs = nowMs,
        )
        lastNote = note
        if (sourceMessageId.isNotBlank() && appContext != null) {
            CommitmentNotes.put(appContext, sourceMessageId, note)
        }
        return commitment
    }

    @Synchronized
    fun done(id: String): Boolean {
        val ok = store.markDone(id)
        if (ok) CommitmentNotes.updateStateByCommitmentId(id, CommitmentState.DONE.wire, appContext)
        return ok
    }

    @Synchronized
    fun drop(id: String): Boolean {
        val ok = store.markDropped(id)
        if (ok) CommitmentNotes.updateStateByCommitmentId(id, CommitmentState.DROPPED.wire, appContext)
        return ok
    }

    @Synchronized
    fun doneDue(nowMs: Long = now()): CommitmentResolver.DoneDue {
        val open = store.listOpen()
        if (open.isEmpty()) return CommitmentResolver.DoneDue.None
        val due = open.filter { it.dueMs != null && it.dueMs <= nowMs }
            .sortedWith(compareByDescending<Commitment> { it.dueMs }.thenByDescending { it.createdMs })
        if (due.isEmpty()) {
            val latest = open.maxByOrNull { it.createdMs } ?: return CommitmentResolver.DoneDue.None
            done(latest.id)
            return CommitmentResolver.DoneDue.Done(latest.copy(state = CommitmentState.DONE))
        }
        val newest = due.first()
        val latestDueMs = newest.dueMs!!
        val group = due.filter { latestDueMs - it.dueMs!! <= DUE_WINDOW_MS }
        if (group.size > 1) return CommitmentResolver.DoneDue.Ambiguous(group)
        done(newest.id)
        return CommitmentResolver.DoneDue.Done(newest.copy(state = CommitmentState.DONE))
    }

    fun listOpen(): List<Commitment> = store.listOpen()

    fun listAll(): List<Commitment> = store.listAll()

    @Synchronized
    fun bindLastNoteToMessage(context: android.content.Context?, messageId: String) {
        val note = takeLastNote() ?: return
        if (note.pendingId != null) {
            pendingById[note.pendingId]?.let { p ->
                pendingById[note.pendingId] = p.copy(sourceMessageId = messageId)
            }
        }
        if (note.commitmentId != null) {
            store.get(note.commitmentId)?.let { c ->
                store.add(c.copy(sourceMessageId = messageId))
            }
        }
        CommitmentNotes.put(context, messageId, note)
    }

    companion object {
        private const val DUE_WINDOW_MS = 2 * 60 * 60_000L
    }
}
