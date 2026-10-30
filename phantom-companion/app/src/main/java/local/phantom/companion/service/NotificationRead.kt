package local.phantom.companion.service

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.AppContainer
import local.phantom.companion.ui.messenger.WebMessengerReads

/**
 * «Прочитано» зі шторки — те саме прочитання, що й з відкритого чату.
 *
 * Межа прочитаного зсувається тими самими сховищами, що й екран, і співрозмовник
 * дістає той самий звіт PH-RDU ([WebMessengerReads]) — з тими самими умовами:
 * перемикач «Звіти про прочитання», блокування, ключ. Інакше людина, що
 * прочитала зі шторки, лишалась би для співрозмовника «непрочитаною», а
 * зникаючий лист не почав би свого строку.
 */
internal class NotificationRead(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    private val markDm: (peerId: String, uptoMs: Long) -> Unit,
    private val markGroup: (groupId: String, uptoMs: Long) -> Unit,
    private val receipts: WebMessengerReads,
) {

    /**
     * true — межу зсунуто (звіт міг і не поїхати: перемикач вимкнено чи ключа немає).
     * [sawText] false — шторка показувала «Нове повідомлення» без тексту (замок):
     * межа своя рухається, а звіту немає — людина листа не бачила.
     */
    fun advance(conversation: String, groupId: String?, sawText: Boolean = true): Boolean {
        val outbox = log()
        val meId = me()
        if (groupId != null) {
            // Групова репліка приїжджає звичайною приватною копією — групу
            // видно лише розібравши тіло, як це робить PeerAnnounce.
            val lines = outbox.filter { it.fromId != meId && PeerGroup.decode(it.body)?.group?.id == groupId }
            val newest = lines.maxByOrNull { it.sentAtMs } ?: return false
            markGroup(groupId, newest.sentAtMs)
            if (sawText) PeerGroup.decode(newest.body)?.group?.let { receipts.readGroup(it, lines, 0) }
            return true
        }
        val newest = outbox.filter { it.fromId == conversation }.maxOfOrNull { it.sentAtMs } ?: return false
        markDm(conversation, newest)
        if (sawText) receipts.readConversation(conversation, newest)
        return true
    }

    companion object {
        fun of(container: AppContainer) = NotificationRead(
            log = { container.mesh.outbox.value },
            me = { container.mesh.selfIdFlow.value },
            markDm = { peer, upto -> container.peers.markRead(peer, upto) },
            markGroup = { group, upto -> container.groupReads.markRead(group, upto) },
            receipts = WebMessengerReads.of(container),
        )
    }
}
