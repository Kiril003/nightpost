package local.phantom.companion.service

import android.content.Context
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerFile

internal object NoticeLabels {
    enum class Attach { PHOTO, VOICE, VIDEO, FILE }

    fun attachKind(kind: String, mime: String): Attach = when {
        kind == PeerFile.VOICE -> Attach.VOICE
        kind == PeerFile.VIDEO_NOTE -> Attach.VIDEO
        kind == PeerFile.IMAGE || mime.startsWith("image/") -> Attach.PHOTO
        else -> Attach.FILE
    }

    fun senderName(known: String?, unknownLabel: String): String =
        known?.takeIf { it.isNotBlank() } ?: unknownLabel

    fun shortcutLabel(groupName: String?, senderName: String, unknownLabel: String): String =
        (groupName ?: senderName).ifBlank { unknownLabel }

    fun reaction(context: Context, emoji: String, quoted: String?): String =
        if (quoted != null) context.getString(R.string.peer_reaction_notification, emoji, quoted)
        else context.getString(R.string.peer_reaction_notification_plain, emoji)

    /** Група відкривається через чергу запитів; тост лише якщо поставити запит не вдалося. */
    fun requestGroup(post: (String) -> Unit, onFail: () -> Unit, groupId: String) {
        runCatching { post(groupId) }.onFailure { onFail() }
    }
}
