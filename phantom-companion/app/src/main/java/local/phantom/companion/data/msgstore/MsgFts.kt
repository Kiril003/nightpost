package local.phantom.companion.data.msgstore

import local.phantom.companion.core.net.peer.PeerMessage

/** Пошук за змістом на диску; індекс веде тригер, код його не наздоганяє. */
internal class MsgFts(private val sql: MsgSql) {

    /** Кожне слово запиту — префікс; лапки знешкоджують синтаксис FTS у словах людини. */
    fun search(text: String, limit: Int = 50): List<PeerMessage> {
        val terms = text.split(Regex("\\s+")).filter { it.isNotBlank() }
            .joinToString(" ") { "\"" + it.replace("\"", "\"\"") + "\"*" }
        if (terms.isEmpty()) return emptyList()
        return sql.query(
            "SELECT l.id, l.from_id, l.to_id, l.body, l.sent_at, l.reply_to, l.signature, l.state, " +
                "l.attempts, l.next_attempt, l.sealing, l.held_nonce, l.held_until, l.edited_at, l.deleted_at, " +
                "l.fw_name, l.fw_id, l.qt " +
                "FROM line_fts f JOIN line l ON l.rid = f.rowid WHERE line_fts MATCH ? " +
                "ORDER BY l.sent_at DESC LIMIT ?",
            terms, limit.toLong(), row = MsgLines::read,
        )
    }

    companion object {
        val SCHEMA = listOf(
            "CREATE VIRTUAL TABLE IF NOT EXISTS line_fts USING fts5(body, content='line', " +
                "content_rowid='rid', tokenize='unicode61 remove_diacritics 2')",
            "CREATE TRIGGER IF NOT EXISTS line_fts_ai AFTER INSERT ON line BEGIN " +
                "INSERT INTO line_fts (rowid, body) VALUES (new.rid, new.body); END",
            "CREATE TRIGGER IF NOT EXISTS line_fts_ad AFTER DELETE ON line BEGIN " +
                "INSERT INTO line_fts (line_fts, rowid, body) VALUES ('delete', old.rid, old.body); END",
            "CREATE TRIGGER IF NOT EXISTS line_fts_au AFTER UPDATE OF body ON line " +
                "WHEN old.body IS NOT new.body BEGIN " +
                "INSERT INTO line_fts (line_fts, rowid, body) VALUES ('delete', old.rid, old.body); " +
                "INSERT INTO line_fts (rowid, body) VALUES (new.rid, new.body); END",
        )
    }
}
