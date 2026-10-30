package local.phantom.companion.data.msgstore

internal data class ConvSummary(
    val convId: String,
    val lines: Long,
    val lastId: String,
    val lastAtMs: Long,
    val lastFromId: String,
    val snippet: String,
)

/**
 * Рядок списку розмов, який тримають тригери: список читається O(розмов), а не
 * проходом по всьому журналу на кожну людину.
 */
internal class ConvSummaries(private val sql: MsgSql) {

    fun all(): List<ConvSummary> =
        sql.query("SELECT $COLS FROM conv_summary ORDER BY last_at DESC", row = ::read)

    fun of(conv: String): ConvSummary? =
        sql.query("SELECT $COLS FROM conv_summary WHERE conv_id = ?", conv, row = ::read).firstOrNull()

    companion object {
        private const val COLS = "conv_id, lines, last_id, last_at, last_from, snippet"
        private const val SNIPPET = 200
        private const val LAST_OF =
            "(SELECT id, sent_at, from_id, substr(body, 1, $SNIPPET) FROM line " +
                "WHERE conv_id = %s ORDER BY sent_at DESC, rid DESC LIMIT 1)"

        private fun read(r: MsgRow) = ConvSummary(r.str(0), r.long(1), r.str(2), r.long(3), r.str(4), r.str(5))

        val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS conv_summary (conv_id TEXT NOT NULL PRIMARY KEY, " +
                "lines INTEGER NOT NULL, last_id TEXT NOT NULL, last_at INTEGER NOT NULL, " +
                "last_from TEXT NOT NULL, snippet TEXT NOT NULL)",
            "CREATE TRIGGER IF NOT EXISTS conv_summary_ai AFTER INSERT ON line BEGIN " +
                "INSERT OR IGNORE INTO conv_summary VALUES (new.conv_id, 0, '', -1, '', ''); " +
                "UPDATE conv_summary SET lines = lines + 1 WHERE conv_id = new.conv_id; " +
                "UPDATE conv_summary SET last_id = new.id, last_at = new.sent_at, last_from = new.from_id, " +
                "snippet = substr(new.body, 1, $SNIPPET) WHERE conv_id = new.conv_id AND new.sent_at >= last_at; " +
                "END",
            "CREATE TRIGGER IF NOT EXISTS conv_summary_ad AFTER DELETE ON line BEGIN " +
                "UPDATE conv_summary SET lines = lines - 1 WHERE conv_id = old.conv_id; " +
                "DELETE FROM conv_summary WHERE conv_id = old.conv_id AND lines <= 0; " +
                "UPDATE conv_summary SET (last_id, last_at, last_from, snippet) = ${LAST_OF.format("old.conv_id")} " +
                "WHERE conv_id = old.conv_id AND last_id = old.id; " +
                "END",
            "CREATE TRIGGER IF NOT EXISTS conv_summary_au AFTER UPDATE OF body, sent_at ON line " +
                "WHEN old.body IS NOT new.body OR old.sent_at != new.sent_at BEGIN " +
                "UPDATE conv_summary SET (last_id, last_at, last_from, snippet) = ${LAST_OF.format("new.conv_id")} " +
                "WHERE conv_id = new.conv_id; " +
                "END",
        )
    }
}
