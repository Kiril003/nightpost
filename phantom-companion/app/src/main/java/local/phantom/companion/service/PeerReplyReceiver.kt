package local.phantom.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.net.peer.PeerFrames
import local.phantom.companion.core.net.peer.PeerGroup

/**
 * Відповідь зі шторки, без відкривання застосунку.
 *
 * У рукавицях, за кермом, під дощем — відкрити застосунок, дочекатись замка,
 * знайти розмову і надрукувати означає не відповісти взагалі. Черга вихідних
 * та сама, що й у чаті: лист лягає в неї до першої спроби відправки, тож
 * відповідь зі шторки нічим не слабша за відповідь з екрана.
 */
class PeerReplyReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        val text = textOf(intent)?.trim().orEmpty()
        if (text.isEmpty()) return

        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)

        // Звірений контакт змінив ключ — лист чекає на «Продовжити» в чаті
        // (PeerIdentity.sendHeld). Шторка цього рішення не приймає.
        if (groupId == null && container.peers.peers.value.any { it.id == conversation && it.sendHeld }) {
            PeerNotifications.noteReplyHeld(context, conversation)
            return
        }

        // Зі шторки приходить НАБРАНЕ — тим самим входом, що й з композера, тож
        // і межа походження та сама: рядок з початком кадру пристрою тут теж не
        // їде, і плашка каже про це окремим словом.
        val outcome = if (groupId != null) {
            PeerGroup.known(container.mesh.outbox.value).firstOrNull { it.id == groupId }
                ?.let { container.mesh.sendTypedToGroup(it, text) }
        } else {
            container.mesh.sendTyped(toId = conversation, body = text)
        }

        when {
            outcome is PeerFrames.Typed.Refused -> {
                Log.w(TAG, "відповідь зі шторки почалась як кадр пристрою: $conversation")
                PeerNotifications.noteReplyRefused(context, conversation)
            }
            outcome is PeerFrames.Typed.Sent && outcome.messages.isNotEmpty() -> {
                PeerNotifications.noteOwnReply(context, conversation, text)
                // Хто відповів — той прочитав: межа і звіт ті самі, що з «Прочитано».
                runCatching { NotificationRead.of(container).advance(conversation, groupId) }
                    .onFailure { Log.w(TAG, "межу прочитаного після відповіді не зсунуто: $conversation", it) }
            }
            else -> {
                // Ключ не піднявся або групу не знайдено в журналі. Мовчазна
                // поразка тут — це людина, впевнена, що відповіла.
                Log.w(TAG, "відповідь зі шторки не стала в чергу: $conversation")
                PeerNotifications.noteReplyFailed(context, conversation)
            }
        }
    }

    private fun textOf(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()
            ?: intent.getStringExtra(EXTRA_CANNED)

    companion object {
        const val KEY_REPLY = "phantom.peer.reply"
        const val EXTRA_CONVERSATION = "phantom.peer.conversation"
        const val EXTRA_GROUP_ID = "phantom.peer.group"
        const val EXTRA_CANNED = "phantom.peer.canned"
        private const val TAG = "PeerReply"
    }
}
