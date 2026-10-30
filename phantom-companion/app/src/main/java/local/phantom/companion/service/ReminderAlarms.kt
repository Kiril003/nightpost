package local.phantom.companion.service

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import local.phantom.companion.R
import local.phantom.companion.core.data.reminders.ReminderEntity

/**
 * Turns rows in the reminders table into alarms that actually go off.
 *
 * Exactness is claimed through USE_EXACT_ALARM (auto-granted from API 33) so
 * setting a reminder never sends the operator into system settings — the
 * zero-config law. Where that is unavailable the alarm degrades to the
 * idle-tolerant inexact variant rather than not firing at all.
 */
object ReminderAlarms {

    const val CHANNEL_ID: String = "phantom_reminders_v1"
    const val ACTION_FIRE: String = "local.phantom.companion.action.REMINDER_FIRE"
    const val EXTRA_ID: String = "reminder_id"

    private const val TAG = "ReminderAlarms"

    fun registerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.kt_reminder_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.kt_reminder_channel_desc)
                enableVibration(true)
            },
        )
    }

    private const val VISIT_REQUEST_CODE = 0x5E1D

    fun scheduleVisit(context: Context, atMs: Long): Boolean {
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        if (!canScheduleExact(context)) return false
        val intent = Intent(context, local.phantom.companion.self.VisitActionReceiver::class.java).apply {
            action = local.phantom.companion.self.PhantomVisits.ACTION_DUE
            data = Uri.parse("phantom-visit://due")
            putExtra("visit_id", "due")
        }
        val pi = PendingIntent.getBroadcast(
            context,
            VISIT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return false
        return runCatching {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, pi)
            true
        }.onFailure { Log.w(TAG, "could not arm visit alarm", it) }.getOrDefault(false)
    }

    fun schedule(context: Context, id: String, triggerAtMs: Long) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePendingIntent(context, id, create = true) ?: return
        runCatching {
            if (canScheduleExact(context)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        }.onFailure { Log.w(TAG, "could not arm reminder $id", it) }
    }

    fun cancel(context: Context, id: String) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = firePendingIntent(context, id, create = false) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    fun notify(context: Context, reminder: ReminderEntity) {
        // Без цього нагадування просто не зʼявлялось: notify() при відібраному
        // POST_NOTIFICATIONS не падає, а тихо нічого не робить, і людина не
        // дізнається, що нагадування було. Тут воно принаймні лишає слід.
        if (!notificationsAllowed(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — reminder ${reminder.id} not delivered")
            return
        }
        val open = context.packageManager
            .getLaunchIntentForPackage(context.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPi = open?.let {
            PendingIntent.getActivity(
                context,
                reminder.id.hashCode(),
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            // Момент — це продукт, текст — ні: нагадування пише сам оператор і
            // воно спокійно несе прізвище, склад або час виїзду.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setSmallIcon(NotificationLook.smallIcon)
                    .setColor(NotificationLook.accent)
                    .setContentTitle(context.getString(R.string.reminder_title))
                    .setCategory(NotificationCompat.CATEGORY_REMINDER)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .apply { contentPi?.let(::setContentIntent) }
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(reminder.id.hashCode(), notification)
        }.onFailure { Log.w(TAG, "notification for ${reminder.id} refused", it) }
    }

    private fun notificationsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(AlarmManager::class.java) ?: return false
        return runCatching { am.canScheduleExactAlarms() }.getOrDefault(false)
    }

    /**
     * One PendingIntent per reminder. The id rides in the *data* URI, not only
     * in the extras — `filterEquals` ignores extras, so an id-only-in-extras
     * intent would make every reminder share (and overwrite) one alarm slot.
     */
    private fun firePendingIntent(context: Context, id: String, create: Boolean): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_FIRE
            data = Uri.parse("phantom://reminder/$id")
            putExtra(EXTRA_ID, id)
        }
        var flags = PendingIntent.FLAG_IMMUTABLE
        flags = flags or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, id.hashCode(), intent, flags)
    }
}
