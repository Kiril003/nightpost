package local.phantom.companion.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Phase 2-D: Watchdog.
 * Ensures PhantomLinkService remains alive even if killed by the OS.
 */
object Watchdog {
    private const val RESTART_INTERVAL_MS = 15 * 60 * 1000L // 15 minutes

    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, 
            0, 
            intent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        alarmManager.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + RESTART_INTERVAL_MS,
            RESTART_INTERVAL_MS,
            pendingIntent
        )
        Log.i("PHANTOM_Watchdog", "Watchdog scheduled every 15m")
    }
}

class WatchdogReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Log.d("PHANTOM_Watchdog", "Watchdog heartbeat triggered")
        val paired = runCatching { local.phantom.companion.PhantomApp.container().store.load() != null }.getOrDefault(false)
        if (paired) {
            val serviceIntent = Intent(context, PhantomLinkService::class.java)
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("PHANTOM_Watchdog", "Failed to start PhantomLinkService from watchdog: ${e.message}", e)
            }
        }
        AirAlertService.ensureRunning(context)
    }
}
