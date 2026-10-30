package local.phantom.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp

/** The moment a reminder is due: surface it, mark it, and roll a repeat forward. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderAlarms.ACTION_FIRE) return
        val id = intent.getStringExtra(ReminderAlarms.EXTRA_ID) ?: return
        val appContext = context.applicationContext
        val pending = goAsync()
        val container = runCatching { PhantomApp.container() }.getOrNull()
        if (container == null) {
            pending.finish()
            return
        }
        container.scope.launch {
            try {
                val reminder = container.reminderStore.get(id) ?: return@launch
                if (!local.phantom.companion.self.PhantomVisits.remind(appContext, container, reminder)) {
                    ReminderAlarms.notify(appContext, reminder)
                }
                container.reminderStore.markFired(id)
                if (container.reminderStore.rollToNext(id)) {
                    container.reminderStore.get(id)?.let {
                        ReminderAlarms.schedule(appContext, it.id, it.triggerAtMs)
                    }
                }
            } catch (t: Throwable) {
                Log.w("ReminderReceiver", "reminder $id failed to surface", t)
            } finally {
                pending.finish()
            }
        }
    }
}
