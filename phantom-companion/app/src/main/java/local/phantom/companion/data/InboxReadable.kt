package local.phantom.companion.data

import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.ui.messenger.WebMessengerGroups

/** Шторка лише про те, що розмова справді покаже: той самий журнал і ті самі двері, що в мосту. */
internal object InboxReadable {

    fun shows(log: List<PeerMessage>, roster: List<PeerIdentity>, notice: PeerAnnounce.Notice, messageId: String): Boolean {
        if (log.none { it.id == messageId }) return false
        notice.groupId?.let { return WebMessengerGroups.find(log, it) != null }
        return roster.any { it.id == notice.fromId }
    }
}
