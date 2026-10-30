package local.phantom.companion.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import local.phantom.companion.PhantomApp

/**
 * Ensures [PhantomLinkService] restarts automatically after device reboot.
 * Mandatory for a "sentient" familiar that must always be listening (§2-D).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "Boot completed, checking if we should start PhantomLinkService")
            val container = PhantomApp.container()
            // Alarms do not survive a reboot; the reminders table does.
            runCatching { ReminderAlarmSync.start(context, container) }
                .onFailure { Log.w("BootReceiver", "reminder re-arm failed", it) }
            runCatching { ScheduledLetterAlarms.rearm(context, container.scheduledLetters) }
                .onFailure { Log.w("BootReceiver", "scheduled letters re-arm failed", it) }
            // СИРЕНА-3 — небо не чекає, поки оператор відкриє застосунок після
            // перезавантаження. BOOT_COMPLETED — дозволений вхід для FGS.
            AirAlertService.ensureRunning(context)
            if (LinkStart.fromBoot(android.os.Build.VERSION.SDK_INT)) {
                if (container.store.load() != null) {
                    val serviceIntent = Intent(context, PhantomLinkService::class.java)
                    try {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    } catch (e: Exception) {
                        Log.e("BootReceiver", "Failed to start PhantomLinkService after boot", e)
                    }
                }
            }
        }
    }
}
