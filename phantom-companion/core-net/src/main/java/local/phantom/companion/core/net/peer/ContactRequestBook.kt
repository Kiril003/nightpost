package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Запити в контакти на телефоні: дзеркало `messenger/contact_requests.py`.
 *
 * Кожен рядок привʼязаний до власника — чужий не видно й не зачепити. Ліміти
 * ті самі, що на вузлі ПК: 30 вихідних і 50 вхідних на добу.
 */
class ContactRequestBook(
    private val store: Store,
    private val now: () -> Long = System::currentTimeMillis,
) {

    interface Store {
        fun read(): String?
        fun write(raw: String)
    }

    class QuotaExceeded : Exception()

    @Serializable
    data class Row(
        val id: String,
        val owner: String,
        val dir: String,
        val peer: String,
        val name: String,
        val text: String,
        val state: String,
        val atMs: Long,
        val sent: Boolean,
    )

    private val lock = Any()
    private val ser = ListSerializer(Row.serializer())

    private fun load(): List<Row> =
        store.read()?.let { runCatching { Json.decodeFromString(ser, it) }.getOrNull() } ?: emptyList()

    private fun save(rows: List<Row>) = store.write(Json.encodeToString(ser, rows))

    private fun recent(rows: List<Row>, owner: String, dir: String): Int {
        val since = now() - DAY_MS
        return rows.count { it.owner == owner && it.dir == dir && it.atMs >= since }
    }

    fun find(owner: String, id: String): Row? =
        synchronized(lock) { load().firstOrNull { it.owner == owner && it.id == id } }

    fun listOpen(owner: String): List<Row> =
        synchronized(lock) { load().filter { it.owner == owner && it.state == PENDING } }

    /** (рядок, новий?). Другий запит тій самій людині повертає перший. */
    fun createOutgoing(owner: String, id: String, peer: String, name: String, text: String): Pair<Row, Boolean> =
        synchronized(lock) {
            val rows = load()
            rows.firstOrNull { it.owner == owner && it.dir == OUT && it.peer == peer && it.state == PENDING }
                ?.let { return it to false }
            if (recent(rows, owner, OUT) >= DAILY_OUT) throw QuotaExceeded()
            val row = Row(id, owner, OUT, peer, PeerContact.clip(name, NAME_LIMIT), text, PENDING, now(), false)
            save(rows + row)
            row to true
        }

    /** Чужий кадр. Зайвий, повторний і понад ліміт — мовчки відкидається. */
    fun addIncoming(owner: String, id: String, peer: String, name: String, text: String): Boolean =
        synchronized(lock) {
            val clean = cleanText(text).let { if (hasLink(it)) "" else it }
            val rows = load()
            if (rows.any {
                    it.owner == owner && it.dir == IN && it.peer == peer && (it.id == id || it.state == PENDING)
                }
            ) {
                return false
            }
            if (recent(rows, owner, IN) >= DAILY_IN) return false
            if (rows.count { it.owner == owner && it.dir == IN && it.state == PENDING } >= MAX_PENDING_IN) return false
            save(rows + Row(id, owner, IN, peer, PeerContact.clip(name.trim(), NAME_LIMIT), clean, PENDING, now(), true))
            true
        }

    /** Міняє стан свого рядка. Чужий (інший власник чи напрям) не чіпається. */
    fun mark(owner: String, id: String, dir: String, state: String, peer: String? = null, sent: Boolean? = null): Boolean =
        synchronized(lock) {
            var hit = false
            val next = load().map { r ->
                if (r.owner == owner && r.id == id && r.dir == dir && (peer == null || r.peer == peer) &&
                    (r.state == PENDING || state == r.state)
                ) {
                    hit = true
                    r.copy(state = state, sent = sent ?: r.sent)
                } else {
                    r
                }
            }
            if (hit) save(next)
            hit
        }

    /** Кадр від [peer] (`inbox._apply_contact_frame`). [peerName] — запасне імʼя з реєстру. */
    fun absorb(owner: String, peer: String, peerName: String, op: PeerContact.Op) {
        when (op) {
            is PeerContact.Op.Request -> addIncoming(owner, op.id, peer, op.name.ifEmpty { peerName }, op.text)
            is PeerContact.Op.Withdraw -> mark(owner, op.id, IN, "withdrawn", peer = peer)
            is PeerContact.Op.Accept -> mark(owner, op.id, OUT, "accepted", peer = peer)
        }
    }

    companion object {
        const val PENDING = "pending"
        const val IN = "in"
        const val OUT = "out"
        const val DAILY_OUT = 30
        const val DAILY_IN = 50
        const val MAX_PENDING_IN = 100
        const val TEXT_LIMIT = 500
        const val NAME_LIMIT = 60
        private const val DAY_MS = 86_400_000L

        private val LINK = Regex("(?i)(https?://|www\\.|[\\p{L}\\p{N}_]+\\.(com|net|org|io|dev|app|me|ru|ua)\\b)")

        fun cleanText(text: String?): String = PeerContact.clip((text ?: "").trim(), TEXT_LIMIT)

        fun hasLink(text: String): Boolean = LINK.containsMatchIn(text)
    }
}
