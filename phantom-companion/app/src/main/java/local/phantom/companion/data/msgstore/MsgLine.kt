package local.phantom.companion.data.msgstore

import local.phantom.companion.core.net.peer.PeerMessage

/** Розмова пари — без знання, хто з двох «я»: один бік рядка завжди мій. */
internal fun convOf(m: PeerMessage): String =
    if (m.fromId <= m.toId) "${m.fromId}|${m.toId}" else "${m.toId}|${m.fromId}"

internal class MsgLines(private val sql: MsgSql) {

    fun upsert(rows: Collection<PeerMessage>) = sql.batch(UPSERT, rows.map(::argsOf))

    /** Рядок, що вже лежить, лишається своїм: переїзд не перетирає новішого стану. */
    fun insertMissing(rows: Collection<PeerMessage>) = sql.batch(INSERT_MISSING, rows.map(::argsOf))

    fun delete(ids: Collection<String>) = sql.batch("DELETE FROM line WHERE id = ?", ids.map { arrayOf<Any?>(it) })

    fun deleteAll() = sql.exec("DELETE FROM line")

    fun all(): List<PeerMessage> = sql.query("SELECT $COLUMNS FROM line ORDER BY rid", row = ::read)

    fun count(): Long = sql.query("SELECT count(*) FROM line") { it.long(0) }.single()

    fun countIn(conv: String): Long =
        sql.query("SELECT count(*) FROM line WHERE conv_id = ?", conv) { it.long(0) }.single()

    fun present(ids: Collection<String>): Set<String> =
        ids.chunked(500).flatMapTo(HashSet()) { part ->
            sql.query(
                "SELECT id FROM line WHERE id IN (${part.joinToString(",") { "?" }})",
                *part.toTypedArray(),
            ) { it.str(0) }
        }

    /** Сторінка розмови від найновішого; [beforeMs] — межа попередньої сторінки. */
    fun page(conv: String, beforeMs: Long = Long.MAX_VALUE, limit: Int = 50): List<PeerMessage> =
        sql.query(
            "SELECT $COLUMNS FROM line WHERE conv_id = ? AND sent_at < ? ORDER BY sent_at DESC, rid DESC LIMIT ?",
            conv, beforeMs, limit.toLong(), row = ::read,
        )

    /** Непрочитані від [fromId] після [afterMs] — позначка прочитаного живе в реєстрі людей, не тут. */
    fun unread(conv: String, fromId: String, afterMs: Long): Long =
        sql.query(
            "SELECT count(*) FROM line WHERE conv_id = ? AND sent_at > ? AND from_id = ?",
            conv, afterMs, fromId,
        ) { it.long(0) }.single()

    private fun argsOf(m: PeerMessage): Array<Any?> = arrayOf(
        m.id, convOf(m), m.fromId, m.toId, m.body, m.sentAtMs, m.replyToId, m.signature,
        m.state.name, m.attempts.toLong(), m.nextAttemptMs, m.sealing.name, m.heldNonce,
        m.heldUntilMs, m.editedAtMs, m.deletedAtMs, m.fw?.name.orEmpty(), m.fw?.id.orEmpty(), m.qt,
    )

    companion object {
        private const val COLUMNS =
            "id, from_id, to_id, body, sent_at, reply_to, signature, state, attempts, " +
                "next_attempt, sealing, held_nonce, held_until, edited_at, deleted_at, fw_name, fw_id, qt"
        private const val INSERT =
            "INSERT INTO line (id, conv_id, from_id, to_id, body, sent_at, reply_to, signature, state, " +
                "attempts, next_attempt, sealing, held_nonce, held_until, edited_at, deleted_at, fw_name, fw_id, qt) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
        private const val INSERT_MISSING = "$INSERT ON CONFLICT(id) DO NOTHING"
        private const val UPSERT = "$INSERT ON CONFLICT(id) DO UPDATE SET " +
            "conv_id = excluded.conv_id, from_id = excluded.from_id, to_id = excluded.to_id, " +
            "body = excluded.body, sent_at = excluded.sent_at, reply_to = excluded.reply_to, " +
            "signature = excluded.signature, state = excluded.state, attempts = excluded.attempts, " +
            "next_attempt = excluded.next_attempt, sealing = excluded.sealing, " +
            "held_nonce = excluded.held_nonce, held_until = excluded.held_until, " +
            "edited_at = excluded.edited_at, deleted_at = excluded.deleted_at, " +
            "fw_name = excluded.fw_name, fw_id = excluded.fw_id, qt = excluded.qt"

        // rid — явний INTEGER PRIMARY KEY: неявний rowid таблиці з текстовим ключем
        // VACUUM має право перенумерувати, і line_fts тоді вказував би на чужі рядки.
        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS line (" +
                "rid INTEGER PRIMARY KEY, id TEXT NOT NULL UNIQUE, conv_id TEXT NOT NULL, " +
                "from_id TEXT NOT NULL, to_id TEXT NOT NULL, body TEXT NOT NULL, " +
                "sent_at INTEGER NOT NULL, reply_to TEXT NOT NULL, signature TEXT NOT NULL, " +
                "state TEXT NOT NULL, attempts INTEGER NOT NULL, next_attempt INTEGER NOT NULL, " +
                "sealing TEXT NOT NULL, held_nonce TEXT NOT NULL, held_until INTEGER NOT NULL, " +
                "edited_at INTEGER NOT NULL, deleted_at INTEGER NOT NULL, " +
                "via_device TEXT NOT NULL DEFAULT '', fw_name TEXT NOT NULL DEFAULT '', " +
                "fw_id TEXT NOT NULL DEFAULT '', qt TEXT NOT NULL DEFAULT '')",
            "CREATE INDEX IF NOT EXISTS line_conv_at ON line (conv_id, sent_at)",
            "CREATE INDEX IF NOT EXISTS line_due ON line (state, next_attempt)",
        )

        /** Стовпці, дописані після першої схеми: база v1 дістає їх порожніми, рядки лишаються. */
        val ADDED = listOf(
            "fw_name" to "fw_name TEXT NOT NULL DEFAULT ''",
            "fw_id" to "fw_id TEXT NOT NULL DEFAULT ''",
            "qt" to "qt TEXT NOT NULL DEFAULT ''",
        )

        // Невідомий стан (рядок новішої збірки) стає типовим — та сама поступливість,
        // що й coerceInputValues у старому JSON.
        fun read(r: MsgRow): PeerMessage = PeerMessage(
            id = r.str(0),
            fromId = r.str(1),
            toId = r.str(2),
            body = r.str(3),
            sentAtMs = r.long(4),
            replyToId = r.str(5),
            signature = r.str(6),
            state = enumOr(r.str(7), PeerMessage.State.QUEUED),
            attempts = r.long(8).toInt(),
            nextAttemptMs = r.long(9),
            sealing = enumOr(r.str(10), PeerMessage.Sealing.UNKNOWN),
            heldNonce = r.str(11),
            heldUntilMs = r.long(12),
            editedAtMs = r.long(13),
            deletedAtMs = r.long(14),
            fw = PeerMessage.forwardOf(r.str(15), r.str(16)),
            qt = r.str(17),
        )

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
