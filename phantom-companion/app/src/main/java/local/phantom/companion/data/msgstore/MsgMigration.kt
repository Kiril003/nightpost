package local.phantom.companion.data.msgstore

import java.security.MessageDigest
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerWhere

/**
 * Переїзд журналу з одного JSON-рядка в базу.
 *
 * Стара копія стирається не тоді, коли рядки лягли, а на НАСТУПНОМУ відчиненні
 * бази, яке побачило ту саму копію вже перенесеною: тільки це доводить, що ключ
 * пережив перезапуск. Той самий шлях підбирає й JSON, дописаний старішою
 * збіркою після відкату — інший хеш означає «ще не переносили».
 */
internal object MsgMigration {

    const val OPENS = "opens"
    const val SEEN = "v1_seen_sha256"
    const val SEEN_AT_OPEN = "v1_seen_open"

    sealed interface Outcome {
        data object Nothing : Outcome
        data class Copied(val rows: Int) : Outcome
        data object Retired : Outcome
    }

    class Unverified(missing: Int) : IllegalStateException("не лягли $missing рядків")

    /**
     * [raw] — вміст старого слота, [parse] — старий розбір із порятунком битого хвоста,
     * [retire] — стирає стару копію.
     */
    fun run(base: MsgBase, raw: String?, parse: (String) -> List<PeerMessage>, retire: () -> Unit): Outcome {
        val open = base.sql.tx {
            ((base.meta(OPENS)?.toLongOrNull() ?: 0L) + 1).also { base.setMeta(OPENS, it.toString()) }
        }
        if (raw.isNullOrBlank()) return Outcome.Nothing
        val hash = sha256(raw)
        if (base.meta(SEEN) == hash) {
            val seenAt = base.meta(SEEN_AT_OPEN)?.toLongOrNull() ?: open
            if (seenAt >= open) return Outcome.Nothing
            retire()
            return Outcome.Retired
        }
        val rows = parse(raw).filterNot { PeerWhere.isWhere(it.body) }
        base.sql.tx {
            base.lines.insertMissing(rows)
            val ids = rows.mapTo(HashSet()) { it.id }
            val missing = ids.size - base.lines.present(ids).size
            if (missing != 0) throw Unverified(missing)
            base.setMeta(SEEN, hash)
            base.setMeta(SEEN_AT_OPEN, open.toString())
        }
        return Outcome.Copied(rows.size)
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
