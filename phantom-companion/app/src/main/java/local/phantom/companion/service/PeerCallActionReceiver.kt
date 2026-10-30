package local.phantom.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp

/**
 * «Відхилити» зі шторки.
 *
 * Окремий приймач, а не намір в активність: відмова мусить поїхати тому боку
 * навіть тоді, коли до екрана справа так і не дійшла — і саме тому вона тут
 * сама. «Взяти» цим шляхом не ходить і ходити не може: слухавку бере рушій
 * WebRTC у веб-месенджері, тож та кнопка ВІДЧИНЯЄ месенджер
 * ([PeerCallNotifications]).
 *
 * `exported=false` — кадр «відмовив» їде тому, хто дзвонить, і право його
 * слати має лише система, що доставила плашку.
 */
class PeerCallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DECLINE) return
        val pending = goAsync()
        decline(context) { pending.finish() }
    }

    companion object {
        /** Те саме, що кнопка «Відхилити», для всіх, хто просить відмовити: шторка, гарнітура, система. */
        fun decline(context: Context, done: () -> Unit = {}) {
            // Плашка гасне одразу: кадр назад їде по мережі й має право на секунду.
            PeerCallNotifications.clear(context)

            val container = runCatching { PhantomApp.container() }.getOrNull() ?: return done()
            container.scope.launch {
                try {
                    container.callHub.declineNow()
                } catch (t: Throwable) {
                    Log.w(TAG, "кадр відмови не поїхав", t)
                } finally {
                    done()
                }
            }
        }

        const val ACTION_DECLINE = "phantom.call.decline"
        private const val TAG = "PeerCallAction"
    }
}
