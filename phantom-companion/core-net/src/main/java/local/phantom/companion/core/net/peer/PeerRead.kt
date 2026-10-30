package local.phantom.companion.core.net.peer

/**
 * «Я прочитав твої рядки до цієї миті» — один кадр на зсув, а не на рядок.
 *
 * Мить — [PeerMessage.sentAtMs] останнього прочитаного рядка, тобто годинник
 * АВТОРА цих рядків: так приймач звіряє її зі своїми ж листами без чужого часу.
 * Точка лише росте: старший кадр, що доїхав пізніше, її не відсуває.
 */
object PeerRead {

    internal const val TAG = "PH-RDU:"

    private const val SEP = ';'

    data class Read(val uptoMs: Long, val groupId: String = "")

    data class Point(val uptoMs: Long, val atMs: Long)

    fun encode(read: Read): String? {
        if (read.uptoMs <= 0L) return null
        if (read.groupId.isNotEmpty() && !isGroupId(read.groupId)) return null
        return TAG + read.uptoMs + (if (read.groupId.isEmpty()) "" else SEP + read.groupId)
    }

    fun isRead(body: String): Boolean = body.startsWith(TAG)

    fun decode(body: String): Read? {
        if (!isRead(body)) return null
        val parts = body.removePrefix(TAG).split(SEP)
        if (parts.size > 2) return null
        val upto = parts[0].toLongOrNull()?.takeIf { it > 0L } ?: return null
        val group = parts.getOrNull(1)?.also { if (!isGroupId(it)) return null }.orEmpty()
        return Read(upto, group)
    }

    /**
     * Докуди [readerId] прочитав рядки [authorId]. Кадр рахується лише тоді,
     * коли його відправник — сам читач і адресований він автору: сторонній
     * «прочитав» за когось іншого не пересуває нічого.
     */
    fun point(log: List<PeerMessage>, readerId: String, authorId: String, groupId: String = ""): Point? =
        log.asSequence()
            .filter { it.fromId == readerId && it.toId == authorId && isRead(it.body) }
            .mapNotNull { m -> decode(m.body)?.takeIf { it.groupId == groupId }?.let { Point(it.uptoMs, m.sentAtMs) } }
            .maxWithOrNull(compareBy({ it.uptoMs }, { it.atMs }))

    /** Лишає по одному кадру на читача, адресата й розмову — найдальший. */
    fun compact(log: List<PeerMessage>): List<PeerMessage> {
        val reads = log.mapNotNull { m -> decode(m.body)?.let { m to it } }
        if (reads.size < 2) return log
        val keep = reads
            .groupBy { (m, r) -> Triple(m.fromId, m.toId, r.groupId) }
            .values
            .map { rows -> rows.maxWith(compareBy({ it.second.uptoMs }, { it.first.sentAtMs })).first.id }
            .toSet()
        val drop = reads.map { it.first.id }.toSet() - keep
        return if (drop.isEmpty()) log else log.filterNot { it.id in drop }
    }

    private fun isGroupId(value: String): Boolean =
        value.length in 1..64 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
}
