package local.phantom.companion.core.data.diary

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * Phase 1.5-D / Phase 3-D3 — high-level façade over [DecisionEntryDao]
 * and the Phase 3-D3 [PendingPublishDao] outbound queue.
 *
 * Mirrors the shape of `core-data/.../vault/VaultStore.kt`: the DAO stays
 * close to SQL, the Store gives callers a typed `Flow<List<DecisionEntry>>`
 * + ergonomic save/delete that auto-fills timestamps and ids when the
 * caller doesn't care about them.
 *
 * Phase 3-D3 adds the outbound side. [publish] tries the supplied
 * [transport] first (cheap `isConnected` short-circuit, then `send`);
 * on transport-offline / send-failed it queues into the local
 * `pending_publish` table. [drainPending] re-tries everything in the
 * queue when the link reconnects, deleting each row after a confirmed
 * send. Both methods are no-op when [pendingDao] is null (legacy
 * single-arg constructor; used by Phase 1.5-D tests that don't care
 * about sync).
 */
class DiaryStore(
    private val dao: DecisionEntryDao,
    private val pendingDao: PendingPublishDao? = null,
) {

    fun getEntries(): Flow<List<DecisionEntry>> = dao.getAll()

    suspend fun getEntry(id: String): DecisionEntry? = dao.getById(id)

    /**
     * Convenience overload that mints a UUID and a `now` timestamp when the
     * caller is creating a fresh entry. Returns the persisted [DecisionEntry]
     * so the UI can surface it immediately if it doesn't want to wait for
     * the [getEntries] flow to re-emit.
     */
    suspend fun saveNew(
        title: String,
        context: String,
        status: String,
        clock: () -> Long = System::currentTimeMillis,
        idFactory: () -> String = { UUID.randomUUID().toString() },
    ): DecisionEntry {
        val entry = DecisionEntry(
            id = idFactory(),
            createdAt = clock(),
            title = title,
            context = context,
            status = status,
        )
        dao.insert(entry)
        return entry
    }

    /** Re-save an edited entry. Caller is expected to keep the same [DecisionEntry.id]. */
    suspend fun update(entry: DecisionEntry) {
        dao.insert(entry)
    }

    suspend fun delete(id: String) = dao.delete(id)

    /**
     * Phase 3-D3 — emit a `decision_diary/<verb>` envelope for [entry].
     *
     * If [transport] is non-null AND `isConnected` is true, attempts a
     * single send; on success returns true. On any failure path
     * (offline, send-rejected, transport null) the envelope is
     * persisted in `pending_publish` for [drainPending] to retry on
     * reconnect, and the method returns false.
     *
     * Always non-blocking from the caller's perspective: the local
     * write that triggered the publish is already durable; the
     * publish path is a side-effect that mustn't lose data on
     * transport failure.
     */
    suspend fun publish(
        entry: DecisionEntry,
        verb: String,
        transport: DiaryPublishTransport?,
        clock: () -> Long = System::currentTimeMillis,
    ): Boolean {
        val payload = DiaryPublishCodec.encodeData(entry, verb)
        // Cheap connectivity check first — avoids a futile network attempt.
        if (transport != null && transport.isConnected) {
            val sent = runCatching { transport.send(verb, payload) }
                .getOrDefault(false)
            if (sent) return true
        }
        // Either no transport, transport offline, or send returned false /
        // threw. Persist for later retry — but only if we have a queue dao.
        pendingDao?.insert(
            PendingPublish(
                verb = verb,
                payload = payload,
                enqueuedAt = clock(),
            ),
        )
        return false
    }

    /**
     * Phase 3-D3 — drain the offline queue through [transport]. Walks
     * the rows in FIFO order, sends each one, deletes only on confirmed
     * success. A single failure halts the drain (the link likely
     * dropped again) so the next reconnect resumes from the right
     * place — never re-orders.
     *
     * Returns the count of successfully-drained rows. No-op + 0 when
     * [pendingDao] is null.
     */
    suspend fun drainPending(transport: DiaryPublishTransport): Int {
        val queue = pendingDao ?: return 0
        if (!transport.isConnected) return 0
        var drained = 0
        for (row in queue.snapshot()) {
            val sent = runCatching { transport.send(row.verb, row.payload) }
                .getOrDefault(false)
            if (!sent) break // Stop on first failure; preserve FIFO.
            queue.deleteById(row.rowId)
            drained++
        }
        return drained
    }

    /** Read-only count of buffered envelopes (for operator-visible "N pending" badge). */
    suspend fun pendingCount(): Int = pendingDao?.count() ?: 0

    /** Observable list of pending rows; settings UI can show oldest age etc. */
    fun pendingFlow(): Flow<List<PendingPublish>>? = pendingDao?.observeAll()
}
