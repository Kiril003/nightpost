package local.phantom.companion.feature.ambient.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Phase 3-E1 — notification-channel + foreground-notification helpers
 * for [WhisperLineService]. Extracted as a top-level object so the
 * channel ID, importance, and copy can be unit-pinned without
 * spinning a Service.
 *
 * Channel name «фантом · присутність» is verbatim from the prompt's
 * Phase 3-E1 spec — the «присутність» label distinguishes this
 * service from the existing «phantom_link_v1» (data-sync notifications)
 * so both can be enabled independently in system settings.
 *
 * Importance is LOW so the notification doesn't beep / vibrate but
 * the foreground requirement is still satisfied. setShowBadge(false)
 * keeps the launcher icon clean.
 */
object WhisperLineNotification {
    const val CHANNEL_ID: String = "phantom_whisper_v1"
    const val CHANNEL_NAME: String = "Nightpost · присутність"
    const val CHANNEL_DESCRIPTION: String =
        "тонка золота смужка з підказками поверх інших додатків"
    const val NOTIF_ID: Int = 4211
    const val NOTIF_TITLE: String = "Nightpost · присутність"
    const val NOTIF_TEXT: String = "стрічка слухає тиху тишу"

    /**
     * Register the notification channel. No-op on pre-O. Idempotent —
     * NotificationManager.createNotificationChannel replaces an existing
     * channel with the same id without state loss.
     */
    fun registerChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = CHANNEL_DESCRIPTION
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Build the foreground-service notification. Smallicon falls back
     * to the launcher icon if the module-local drawable hasn't been
     * generated yet — keeps the build green during the Phase 3-E1
     * scaffolding even before a custom gold-stripe icon ships.
     */
    fun build(context: Context, smallIconRes: Int): android.app.Notification =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(smallIconRes)
            .setContentTitle(NOTIF_TITLE)
            .setContentText(NOTIF_TEXT)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
}
