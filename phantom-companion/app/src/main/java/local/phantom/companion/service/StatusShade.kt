package local.phantom.companion.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import local.phantom.companion.MainActivity
import local.phantom.companion.R
import java.util.concurrent.ConcurrentHashMap

/**
 * Єдиний обовʼязковий рядок служб переднього плану: назва застосунку й нічого
 * більше. Стану (ПК, сітка, небо, голос) тут немає і не буде: про нього кажуть
 * екрани застосунку, а шторка лишається для людей, нагадувань і грошей.
 *
 * Пастка спільного id: `stopForeground(STOP_FOREGROUND_REMOVE)` в одного
 * учасника прибирає рядок у решти. Тому учасники ніколи не прибирають — вони
 * відʼєднуються, і рядок гасне лише коли пішов останній.
 */
object StatusShade {

    // v2: важливість каналу після створення змінити не можна, тож MIN — новий id.
    const val CHANNEL = "phantom_status_v2"
    const val NOTIF_ID = 4200

    /** Канали, які цей рядок заступив. Видалення забирає і їхні сповіщення. */
    internal val RETIRED_CHANNELS = listOf(
        "phantom_status_v1",
        "phantom_link_v1",
        "phantom_mesh_v1",
        "phantom_sky_watch_v1",
        "phantom_live_dialog_v1",
        "phantom_voice_fallback_v1",
        "phantom.position",
    )

    private const val TAG = "StatusShade"

    private val participants = ConcurrentHashMap<String, Unit>()

    @Volatile
    private var appContext: Context? = null

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                context.getString(R.string.status_channel),
                NotificationManager.IMPORTANCE_MIN,
            ).apply {
                description = context.getString(R.string.status_channel_desc)
                setShowBadge(false)
            },
        )
    }

    fun retireOldChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        RETIRED_CHANNELS.forEach { runCatching { nm.deleteNotificationChannel(it) } }
    }

    @Synchronized
    fun attach(service: Service, fgsType: Int) {
        val context = service.applicationContext
        appContext = context
        ensureChannel(context)
        runCatching {
            ServiceCompat.startForeground(service, NOTIF_ID, build(context), fgsType)
        }.onFailure { Log.e(TAG, "system refused foreground start: ${service.javaClass.simpleName}", it) }
        participants[service.javaClass.name] = Unit
    }

    @Synchronized
    fun detach(service: Service) {
        participants.remove(service.javaClass.name)
        runCatching { ServiceCompat.stopForeground(service, ServiceCompat.STOP_FOREGROUND_DETACH) }
        if (participants.isNotEmpty()) return
        appContext?.let { ctx -> runCatching { NotificationManagerCompat.from(ctx).cancel(NOTIF_ID) } }
    }

    internal fun build(context: Context): android.app.Notification {
        val open = PendingIntent.getActivity(
            context,
            NOTIF_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(context.getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
    }
}
