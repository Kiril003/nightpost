package local.phantom.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import local.phantom.companion.PhantomApp
import local.phantom.companion.data.applock.AppLockStore

/**
 * «Прочитано» зі шторки.
 *
 * Прибрати плашку — половина справи, і саме та половина, яку легко зробити
 * брехнею: розмова зникає зі шторки, а лічильник непрочитаного в застосунку
 * лишається, бо межу прочитаного досі рухав тільки відкритий екран чату. Тому
 * ця дія посуває ту саму межу тими самими сховищами, що й екран
 * ([PeerRosterStore.markRead] для розмови на двох, [PeerGroupReadStore] для
 * групи) — інакше оператор «прочитав» двічі. І співрозмовник дістає той самий
 * звіт про прочитання ([NotificationRead]).
 *
 * Свідомо БЕЗ вимоги розблокування: слів від імені людини тут немає, лише її
 * межа прочитаного. Але коли замок ховав текст у шторці, звіту співрозмовнику
 * немає — людина бачила «Нове повідомлення», а не лист.
 */
class PeerMarkReadReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val conversation = intent.getStringExtra(EXTRA_CONVERSATION) ?: return
        val groupId = intent.getStringExtra(EXTRA_GROUP_ID)

        // Плашка йде одразу: палець уже на екрані, а запис межі лягає в
        // шифроване сховище і не має права тримати відгук.
        PeerNotifications.clear(context, conversation)

        val pending = goAsync()
        Thread {
            try {
                advance(conversation, groupId)
            } catch (t: Throwable) {
                Log.w(TAG, "межу прочитаного не зсунуто: $conversation", t)
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun advance(conversation: String, groupId: String?) {
        val container = runCatching { PhantomApp.container() }.getOrNull() ?: return
        val sawText = !AppLockStore.of(container.applicationContext).hidesNotificationContent()
        NotificationRead.of(container).advance(conversation, groupId, sawText)
    }

    companion object {
        const val EXTRA_CONVERSATION = "phantom.peer.read.conversation"
        const val EXTRA_GROUP_ID = "phantom.peer.read.group"
        private const val TAG = "PeerMarkRead"
    }
}
