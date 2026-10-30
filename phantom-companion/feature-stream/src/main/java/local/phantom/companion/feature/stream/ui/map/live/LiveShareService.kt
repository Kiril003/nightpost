package local.phantom.companion.feature.stream.ui.map.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import local.phantom.companion.feature.stream.R

data class LiveShareNotice(val names: List<String>)

object LiveShareForeground {
    const val ACTION_STOP = "local.phantom.companion.live.STOP"
    const val EXTRA_NAMES = "names"
    const val CHANNEL_ID = "live_share"
    const val NOTIFICATION_ID = 4107

    @Volatile
    internal var onStopAction: (() -> Unit)? = null

    fun notice(names: List<String>): LiveShareNotice? {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (clean.isEmpty()) null else LiveShareNotice(clean)
    }

    fun bind(context: Context, scope: CoroutineScope, names: Flow<List<String>>, onStop: () -> Unit): Job {
        onStopAction = onStop
        val appContext = context.applicationContext ?: context
        return scope.launch {
            var running = false
            names.map { notice(it) }.distinctUntilChanged().collect { current ->
                if (current != null) {
                    val intent = Intent(appContext, LiveShareService::class.java)
                        .putStringArrayListExtra(EXTRA_NAMES, ArrayList(current.names))
                    runCatching { ContextCompat.startForegroundService(appContext, intent) }
                    running = true
                } else if (running) {
                    runCatching { appContext.stopService(Intent(appContext, LiveShareService::class.java)) }
                    running = false
                }
            }
        }
    }
}

class LiveShareService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == LiveShareForeground.ACTION_STOP) {
            runCatching { LiveShareForeground.onStopAction?.invoke() }
            runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }

        ensureChannel()
        val names = intent?.getStringArrayListExtra(LiveShareForeground.EXTRA_NAMES)
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val notification = buildNotification(names)
        try {
            ServiceCompat.startForeground(this, LiveShareForeground.NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } catch (_: SecurityException) {
            stopSelf()
        } catch (_: IllegalStateException) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(LiveShareForeground.CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                LiveShareForeground.CHANNEL_ID,
                getString(R.string.live_share_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun buildNotification(names: List<String>): Notification {
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val stopIntent = Intent(this, LiveShareService::class.java).setAction(LiveShareForeground.ACTION_STOP)
        val stopPi = PendingIntent.getService(this, 1, stopIntent, piFlags)
        val contentPi = packageManager?.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(this, 0, launch, piFlags)
        }
        return NotificationCompat.Builder(this, LiveShareForeground.CHANNEL_ID)
            .setSmallIcon(R.drawable.ms_near_me)
            .setContentTitle(getString(R.string.live_share_title))
            .apply {
                if (names.isNotEmpty()) setContentText(names.joinToString(", "))
                if (contentPi != null) setContentIntent(contentPi)
            }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.live_share_stop), stopPi)
            .build()
    }
}
