package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRead
import local.phantom.companion.data.AppContainer

/**
 * Звіти про прочитання з боку веба: коли слати READ і що показати про чужі.
 *
 * Вимкнений перемикач діє в обидва боки: нічого не шлемо й чужих прочитань не
 * показуємо — [visible] false означає «невідомо», а не «не прочитано».
 */
internal class WebMessengerReads(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    private val enabled: () -> Boolean,
    /** Не заблокований і має ключ. */
    private val canSend: (peerId: String) -> Boolean,
    private val send: (toId: String, body: String) -> PeerMessage?,
) {

    fun visible(): Boolean = enabled()

    /** Докуди прочитано рядки [peerId] у стрічці до [seq] включно; 0 — у всій. */
    fun uptoOf(feed: List<PeerMessage>, peerId: String, seq: Int): Long =
        (if (seq > 0) feed.take(seq) else feed).filter { it.fromId == peerId }.maxOfOrNull { it.sentAtMs } ?: 0L

    /** Кадр іде лише коли точка справді зрушила вперед. */
    fun readConversation(peerId: String, uptoMs: Long): Boolean = advance(peerId, uptoMs, "")

    /** По кадру кожному автору, чиї рядки щойно прочитано. */
    fun readGroup(group: PeerGroup.Group, feed: List<PeerMessage>, seq: Int): Int =
        group.memberIds.filter { it != me() }.count { member -> advance(member, uptoOf(feed, member, seq), group.id) }

    fun pointOf(peerId: String): PeerRead.Point? =
        if (visible()) PeerRead.point(log(), peerId, me()) else null

    /** Скільки учасників прочитали власний рядок [line]; null — невідомо. */
    fun fanRead(group: PeerGroup.Group, line: PeerMessage): Int? {
        if (!visible() || line.fromId != me()) return null
        val log = log()
        return group.memberIds.filter { it != me() }
            .count { (PeerRead.point(log, it, me(), group.id)?.uptoMs ?: 0L) >= line.sentAtMs }
    }

    /** Мить прочитання власного рядка або 0 — ще ні. Годинник читача, не пізніше за цю мить. */
    fun readAtMs(line: PeerMessage, point: PeerRead.Point?): Long =
        if (line.fromId == me() && point != null && point.uptoMs >= line.sentAtMs) point.atMs else 0L

    private fun advance(peerId: String, uptoMs: Long, groupId: String): Boolean {
        if (!enabled() || uptoMs <= 0L || !canSend(peerId)) return false
        val sent = PeerRead.point(log(), me(), peerId, groupId)?.uptoMs ?: 0L
        if (uptoMs <= sent) return false
        val body = PeerRead.encode(PeerRead.Read(uptoMs, groupId)) ?: return false
        return send(peerId, body) != null
    }

    companion object {
        /** Ті самі умови, що й у мосту веба: перемикач, блокування, ключ. */
        fun of(container: AppContainer) = WebMessengerReads(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            enabled = { container.readReceipts.enabled.value },
            canSend = { id ->
                !container.peerBlocks.blocks(id) &&
                    container.peers.peers.value.any { it.id == id && it.dhPubKey.isNotBlank() }
            },
            send = { to, frame -> container.mesh.sendRead(to, frame) },
        )
    }
}
