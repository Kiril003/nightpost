package local.phantom.companion.self

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

enum class CommitmentState(val wire: String) {
    OPEN("open"),
    DONE("done"),
    DROPPED("dropped");

    companion object {
        fun of(wire: String?): CommitmentState =
            entries.firstOrNull { it.wire == wire } ?: OPEN
    }
}

data class Commitment(
    val id: String,
    val text: String,
    val personName: String,
    val peerId: String?,
    val what: String,
    val dueMs: Long?,
    val createdMs: Long,
    val sourceMessageId: String,
    val state: CommitmentState = CommitmentState.OPEN,
)

interface CommitmentStore {
    fun add(commitment: Commitment): Commitment
    fun get(id: String): Commitment?
    fun listOpen(): List<Commitment>
    fun listAll(): List<Commitment>
    fun dueOpen(nowMs: Long): List<Commitment>
    fun setState(id: String, state: CommitmentState): Boolean
    fun markDone(id: String): Boolean = setState(id, CommitmentState.DONE)
    fun markDropped(id: String): Boolean = setState(id, CommitmentState.DROPPED)
}

/** Памʼять у процесі для JVM-тестів та резерву без Android SQLite. */
class InMemoryCommitmentStore : CommitmentStore {
    private val rows = LinkedHashMap<String, Commitment>()

    @Synchronized
    override fun add(commitment: Commitment): Commitment {
        rows[commitment.id] = commitment
        return commitment
    }

    @Synchronized
    override fun get(id: String): Commitment? = rows[id]

    @Synchronized
    override fun listOpen(): List<Commitment> =
        rows.values.filter { it.state == CommitmentState.OPEN }.sortedBy { it.dueMs ?: Long.MAX_VALUE }

    @Synchronized
    override fun listAll(): List<Commitment> = rows.values.toList()

    @Synchronized
    override fun dueOpen(nowMs: Long): List<Commitment> =
        rows.values.filter { it.state == CommitmentState.OPEN && it.dueMs != null && it.dueMs <= nowMs }
            .sortedBy { it.dueMs }

    @Synchronized
    override fun setState(id: String, state: CommitmentState): Boolean {
        val current = rows[id] ?: return false
        rows[id] = current.copy(state = state)
        return true
    }
}

/**
 * SQLite-сховище обіцянок у файлах застосунку з увімкненим WAL.
 */
class SqliteCommitmentStore(
    context: Context,
    dbFile: File = File(context.filesDir, DB_NAME),
) : SQLiteOpenHelper(context, dbFile.absolutePath, null, DB_VERSION), CommitmentStore {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS commitments(
                id TEXT PRIMARY KEY NOT NULL,
                text TEXT NOT NULL,
                person_name TEXT NOT NULL,
                peer_id TEXT NULL,
                what TEXT NOT NULL,
                due_ms INTEGER NULL,
                created_ms INTEGER NOT NULL,
                source_message_id TEXT NOT NULL,
                state TEXT NOT NULL CHECK(state IN ('open','done','dropped'))
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    @Synchronized
    override fun add(commitment: Commitment): Commitment {
        val cv = ContentValues().apply {
            put("id", commitment.id)
            put("text", commitment.text)
            put("person_name", commitment.personName)
            if (commitment.peerId != null) put("peer_id", commitment.peerId) else putNull("peer_id")
            put("what", commitment.what)
            if (commitment.dueMs != null) put("due_ms", commitment.dueMs) else putNull("due_ms")
            put("created_ms", commitment.createdMs)
            put("source_message_id", commitment.sourceMessageId)
            put("state", commitment.state.wire)
        }
        writableDatabase.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        return commitment
    }

    @Synchronized
    override fun get(id: String): Commitment? {
        readableDatabase.query(
            TABLE,
             null,
            "id = ?",
            arrayOf(id),
            null,
            null,
            null,
        ).use { c ->
            return if (c.moveToFirst()) readRow(c) else null
        }
    }

    @Synchronized
    override fun listOpen(): List<Commitment> {
        val out = ArrayList<Commitment>()
        readableDatabase.query(
            TABLE,
            null,
            "state = ?",
            arrayOf(CommitmentState.OPEN.wire),
            null,
            null,
            "CASE WHEN due_ms IS NULL THEN 1 ELSE 0 END ASC, due_ms ASC, created_ms ASC",
        ).use { c ->
            while (c.moveToNext()) out += readRow(c)
        }
        return out
    }

    @Synchronized
    override fun listAll(): List<Commitment> {
        val out = ArrayList<Commitment>()
        readableDatabase.query(TABLE, null, null, null, null, null, "created_ms DESC").use { c ->
            while (c.moveToNext()) out += readRow(c)
        }
        return out
    }

    @Synchronized
    override fun dueOpen(nowMs: Long): List<Commitment> {
        val out = ArrayList<Commitment>()
        readableDatabase.query(
            TABLE,
            null,
            "state = ? AND due_ms IS NOT NULL AND due_ms <= ?",
            arrayOf(CommitmentState.OPEN.wire, nowMs.toString()),
            null,
            null,
            "due_ms ASC",
        ).use { c ->
            while (c.moveToNext()) out += readRow(c)
        }
        return out
    }

    @Synchronized
    override fun setState(id: String, state: CommitmentState): Boolean {
        val cv = ContentValues().apply { put("state", state.wire) }
        val updated = writableDatabase.update(TABLE, cv, "id = ?", arrayOf(id))
        return updated > 0
    }

    private fun readRow(c: Cursor): Commitment {
        val peerIdx = c.getColumnIndexOrThrow("peer_id")
        val dueIdx = c.getColumnIndexOrThrow("due_ms")
        return Commitment(
            id = c.getString(c.getColumnIndexOrThrow("id")),
            text = c.getString(c.getColumnIndexOrThrow("text")),
            personName = c.getString(c.getColumnIndexOrThrow("person_name")),
            peerId = if (c.isNull(peerIdx)) null else c.getString(peerIdx),
            what = c.getString(c.getColumnIndexOrThrow("what")),
            dueMs = if (c.isNull(dueIdx)) null else c.getLong(dueIdx),
            createdMs = c.getLong(c.getColumnIndexOrThrow("created_ms")),
            sourceMessageId = c.getString(c.getColumnIndexOrThrow("source_message_id")),
            state = CommitmentState.of(c.getString(c.getColumnIndexOrThrow("state"))),
        )
    }

    companion object {
        const val DB_NAME = "commitments.db"
        private const val DB_VERSION = 1
        private const val TABLE = "commitments"
    }
}
