package local.phantom.companion.service

import android.content.Context
import kotlinx.coroutines.launch
import local.phantom.companion.data.AppContainer

/**
 * Keeps the alarm table a mirror of the reminders table. Nothing else has to
 * remember to arm anything: a tool writes a row, the Flow re-emits, the alarm
 * appears. A past-due row arms with a past time, which AlarmManager delivers
 * at once — that is how reminders missed while the process was dead surface.
 */
object ReminderAlarmSync {

    private const val WATCH_LIMIT = 200

    fun start(context: Context, container: AppContainer) {
        val app = context.applicationContext
        ReminderAlarms.registerChannel(app)
        container.scope.launch {
            var armed = emptySet<String>()
            container.reminderStore.pendingFlow(WATCH_LIMIT).collect { rows ->
                val live = rows.associateBy { it.id }
                (armed - live.keys).forEach { ReminderAlarms.cancel(app, it) }
                rows.forEach { ReminderAlarms.schedule(app, it.id, it.triggerAtMs) }
                armed = live.keys
            }
        }
    }
}
