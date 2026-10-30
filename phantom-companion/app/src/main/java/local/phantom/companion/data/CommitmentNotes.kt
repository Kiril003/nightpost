package local.phantom.companion.data

import android.content.Context
import local.phantom.companion.self.Commitment
import local.phantom.companion.self.CommitmentResolver
import local.phantom.companion.self.CommitmentStore
import org.json.JSONArray
import org.json.JSONObject

data class CommitmentCardNote(
    val kind: String,
    val commitmentId: String?,
    val pendingId: String?,
    val person: String,
    val peerId: String?,
    val what: String,
    val dueMs: Long?,
    val candidates: List<CommitmentResolver.CandidateChip>,
    val state: String,
    val atMs: Long,
) {
    fun toJson(store: CommitmentStore? = null): JSONObject {
        val liveState = commitmentId?.let { store?.get(it)?.state?.wire } ?: state
        return JSONObject().apply {
            put("kind", kind)
            if (!commitmentId.isNullOrBlank()) put("commitment_id", commitmentId)
            if (!pendingId.isNullOrBlank()) put("pending_id", pendingId)
            put("person", person)
            if (!peerId.isNullOrBlank()) put("peer_id", peerId)
            put("what", what)
            if (dueMs != null) put("due_ms", dueMs)
            if (candidates.isNotEmpty()) {
                put(
                    "candidates",
                    JSONArray().also { arr ->
                        candidates.forEach { c ->
                            arr.put(
                                JSONObject().apply {
                                    put("peer_id", c.peerId)
                                    put("name", c.name)
                                    if (!c.hint.isNullOrBlank()) put("hint", c.hint)
                                },
                            )
                        }
                    },
                )
            }
            put("state", liveState)
        }
    }

    fun encode(): String = JSONObject().apply {
        put("kind", kind)
        put("commitment_id", commitmentId.orEmpty())
        put("pending_id", pendingId.orEmpty())
        put("person", person)
        put("peer_id", peerId.orEmpty())
        put("what", what)
        if (dueMs != null) put("due_ms", dueMs)
        put("state", state)
        put("at_ms", atMs)
        put(
            "candidates",
            JSONArray().also { arr ->
                candidates.forEach { c ->
                    arr.put(
                        JSONObject()
                            .put("peer_id", c.peerId)
                            .put("name", c.name)
                            .put("hint", c.hint.orEmpty()),
                    )
                }
            },
        )
    }.toString()

    companion object {
        fun due(commitment: Commitment, atMs: Long = System.currentTimeMillis()): CommitmentCardNote =
            CommitmentCardNote(
                kind = "due",
                commitmentId = commitment.id,
                pendingId = null,
                person = commitment.personName,
                peerId = commitment.peerId,
                what = commitment.what,
                dueMs = commitment.dueMs,
                candidates = emptyList(),
                state = commitment.state.wire,
                atMs = atMs,
            )

        fun decode(raw: String?): CommitmentCardNote? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(raw)
                val arr = o.optJSONArray("candidates")
                val candidates = if (arr == null) {
                    emptyList()
                } else {
                    (0 until arr.length()).mapNotNull { i ->
                        val c = arr.optJSONObject(i) ?: return@mapNotNull null
                        val pid = c.optString("peer_id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val name = c.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val hint = c.optString("hint").takeIf { it.isNotBlank() }
                        CommitmentResolver.CandidateChip(pid, name, hint)
                    }
                }
                CommitmentCardNote(
                    kind = o.optString("kind", "saved"),
                    commitmentId = o.optString("commitment_id").takeIf { it.isNotBlank() },
                    pendingId = o.optString("pending_id").takeIf { it.isNotBlank() },
                    person = o.optString("person"),
                    peerId = o.optString("peer_id").takeIf { it.isNotBlank() },
                    what = o.optString("what"),
                    dueMs = if (o.has("due_ms") && !o.isNull("due_ms")) o.optLong("due_ms") else null,
                    candidates = candidates,
                    state = o.optString("state", "open"),
                    atMs = o.optLong("at_ms", 0L),
                )
            }.getOrNull()
        }
    }
}

object CommitmentNotes {
    private const val FILE = "phantom_commitment_notes_v1"
    private const val KEEP = 60
    private val memory = LinkedHashMap<String, CommitmentCardNote>()

    @Synchronized
    fun clearInMemory() {
        memory.clear()
    }

    @Synchronized
    fun putInMemory(messageId: String, note: CommitmentCardNote) {
        memory[messageId] = note
        while (memory.size > KEEP) {
            val firstKey = memory.keys.firstOrNull() ?: break
            memory.remove(firstKey)
        }
    }

    @Synchronized
    fun findByPendingId(ctx: Context?, pendingId: String): Pair<String, CommitmentCardNote>? {
        if (pendingId.isBlank()) return null
        memory.entries.firstOrNull { it.value.pendingId == pendingId && it.value.kind == "choose" }
            ?.let { return it.key to it.value }
        if (ctx == null) return null
        return runCatching {
            val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            prefs.all.entries.firstNotNullOfOrNull { (k, v) ->
                val note = CommitmentCardNote.decode(v as? String)
                if (note != null && note.pendingId == pendingId && note.kind == "choose") {
                    memory[k] = note
                    k to note
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    @Synchronized
    fun updateStateByCommitmentId(commitmentId: String, state: String, ctx: Context? = null) {
        val keys = memory.entries.filter { it.value.commitmentId == commitmentId }.map { it.key }
        for (k in keys) {
            memory[k]?.let { memory[k] = it.copy(state = state) }
        }
        if (ctx != null) {
            runCatching {
                val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                val updates = prefs.all.entries.mapNotNull { (k, v) ->
                    val decoded = CommitmentCardNote.decode(v as? String)
                    if (decoded != null && decoded.commitmentId == commitmentId) {
                        val next = decoded.copy(state = state)
                        memory[k] = next
                        k to next.encode()
                    } else {
                        null
                    }
                }
                if (updates.isNotEmpty()) {
                    prefs.edit().apply { updates.forEach { (k, enc) -> putString(k, enc) } }.apply()
                }
            }
        }
    }

    fun put(ctx: Context?, messageId: String, note: CommitmentCardNote) {
        putInMemory(messageId, note)
        if (ctx == null) return
        runCatching {
            val prefs = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            val old = prefs.all.entries
                .mapNotNull { (k, v) -> CommitmentCardNote.decode(v as? String)?.let { k to it.atMs } }
                .sortedByDescending { it.second }
                .drop(KEEP - 1)
                .map { it.first }
            prefs.edit().apply { old.forEach(::remove) }.putString(messageId, note.encode()).apply()
        }
    }

    @Synchronized
    fun get(ctx: Context?, messageId: String): CommitmentCardNote? {
        memory[messageId]?.let { return it }
        if (ctx == null) return null
        return runCatching {
            CommitmentCardNote.decode(ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(messageId, null))
        }.getOrNull()?.also { memory[messageId] = it }
    }
}
