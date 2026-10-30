package local.phantom.companion.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import local.phantom.companion.Distribution
import local.phantom.companion.MainActivity
import local.phantom.companion.R
import local.phantom.companion.core.data.alerts.ALARM_CHANNEL_ID
import local.phantom.companion.core.data.alerts.AirAlertMissLog
import local.phantom.companion.feature.stream.ui.map.AirAlertNotice
import local.phantom.companion.feature.stream.ui.map.AirAlertNoticeKind
import local.phantom.companion.feature.stream.ui.map.oblastShortUk

/**
 * СИРЕНА-3 — доставка тривоги в шторку.
 *
 * Три канали, бо це три різні події за терміновістю: сама тривога має рвати
 * тишу, відбій — ні, а вартовий рядок служби взагалі мовчить. Один канал з
 * IMPORTANCE_HIGH на всі три навчив би оператора глушити канал, після чого
 * тривога не дійде ніколи.
 */
object AirAlertNotifications {

    /**
     * v2, бо канал в Android незмінний після створення, а v1 дзвонив у потік
     * сповіщень: телефон із прибраним звуком сповіщень мовчав уночі, тоді як
     * будильник на тому самому телефоні дзвонив. Тривога, яку не чути о третій
     * — не тривога. Ціна нового id названа чесно: налаштування звуку, які
     * оператор зробив собі на старому каналі, не переїжджають.
     */
    const val CHANNEL_ALARM = ALARM_CHANNEL_ID
    private const val CHANNEL_ALARM_LEGACY = "phantom_air_alert_v1"
    const val CHANNEL_CLEAR = "phantom_air_alert_clear_v1"

    const val NOTIF_ALARM = 4310

    /** Окремий requestCode: спільний із мапою затирав би її PendingIntent. */
    private const val NOTIF_ALARM_FULLSCREEN = 4312

    /** MainActivity читає це і садить камеру на мапу. */
    const val EXTRA_OPEN_MAP = "phantom.open_map"

    private const val TAG = "AirAlertNotifications"

    fun registerChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.deleteNotificationChannel(CHANNEL_ALARM_LEGACY) }
        if (!AirAlertGate.available(context)) {
            runCatching { nm.deleteNotificationChannel(CHANNEL_ALARM) }
            runCatching { nm.deleteNotificationChannel(CHANNEL_CLEAR) }
            return
        }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALARM,
                context.getString(R.string.air_alert_channel_alarm),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.air_alert_channel_alarm_desc)
                enableVibration(true)
                enableLights(true)
                setBypassDnd(true)
                // USAGE_ALARM — це і є та відмінність, через яку будильник
                // будить, а сповіщення ні. setBypassDnd пробиває «Не турбувати»,
                // але не прибраний повзунок гучності сповіщень.
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_CLEAR,
                context.getString(R.string.air_alert_channel_clear),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.air_alert_channel_clear_desc)
                enableVibration(false)
            },
        )
    }

    /**
     * Одна подія — одне сповіщення. Той самий id для тривоги й відбою: відбій
     * ЗАМІЩАЄ тривогу в шторці, бо дві плашки з протилежним змістом поруч —
     * це не інформація, а сумнів.
     */
    fun notify(context: Context, notice: AirAlertNotice) {
        if (!allowed(context)) {
            Log.w(TAG, "POST_NOTIFICATIONS not granted — alert ${notice.oblast} not delivered")
            // Лог сам по собі — це знання, віддане нікуди. Факт лишається на
            // диску, щоб застосунок міг сказати вголос: тривога БУЛА, і ти її
            // не побачив. Попередження наперед пояснює ризик; квитанція
            // доводить, що ризик уже стався, і саме вона не дає реченню про
            // стан стати шпалерами.
            AirAlertMissLog.record(context, notice.oblast, notice.atEpochMs)
            return
        }
        val raised = notice.kind == AirAlertNoticeKind.RAISED
        val where = oblastShortUk(notice.oblast)
        val title = context.getString(
            if (raised) R.string.air_alert_title_raised else R.string.air_alert_title_clear,
            where,
        )
        val body = notice.phrase

        val builder = NotificationCompat.Builder(
            context,
            if (raised) CHANNEL_ALARM else CHANNEL_CLEAR,
        )
            .setSmallIcon(NotificationLook.smallIcon)
            // Тривога — danger, відбій — success: ті самі токени v1.2, що й у застосунку.
            .setColor(if (raised) NotificationLook.danger else NotificationLook.success)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.phrase))
            .setWhen(notice.atEpochMs)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openMapIntent(context))
            .setCategory(if (raised) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_STATUS)
            .setPriority(if (raised) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_DEFAULT)
            // Єдине, що замкнений екран показує повністю. Тривогу, заради якої
            // треба розблокувати телефон, не читає ніхто: її оминають бігом до
            // укриття. А видає вона рівно ту область, у якій телефон і так
            // фізично стоїть — сирена надворі каже те саме гучніше.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Відбій заміщає тривогу тим самим id. Тривога живе, доки її не
            // зняли: setTimeoutAfter прибрав би її сам, і зникнення читалось би
            // як відбій, якого не було.
            .setOnlyAlertOnce(!raised)

        if (raised) {
            fullScreenIntent(context, notice, where)?.let {
                builder.setFullScreenIntent(it, true)
            }
        } else {
            // Відбій знімає екран-сирену, якщо він ще стоїть. Лишити його
            // висіти після відбою — це тримати людину в укритті даремно.
            AirAlertFullScreenActivity.close()
        }

        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIF_ALARM, builder.build())
        }.onFailure { Log.w(TAG, "air-alert notification refused", it) }
    }

    /**
     * Захоплення екрана заслуговує рівно одна подія на весь застосунок —
     * оголошення тривоги над областю, у якій телефон фізично стоїть.
     *
     * Дві брами, і обидві звужують. Перша: телефон має бути замкнений або
     * погашений. Оператор із телефоном у руках уже бачить банер на мапі, і
     * вибити йому з-під пальця те, що він робить, — це не терміновість, це
     * шкода. Друга: з API 34 дозвіл на повноекранний намір дається окремо і
     * може бути знятий; без цієї перевірки система мовчки ігнорує намір, і
     * тривога виглядала б доставленою, лишаючись звичайною плашкою.
     *
     * Повідомлення від людей сюди не потрапляють НІКОЛИ: у цій сітці немає
     * поля терміновості на дроті, тож будь-яка спроба вирішити за відправника
     * була б здогадкою — а екран, що зривається на чат, за тиждень навчить
     * вимикати канал, і тоді не дійде вже тривога.
     */
    private fun fullScreenIntent(
        context: Context,
        notice: AirAlertNotice,
        where: String,
    ): PendingIntent? {
        if (!Distribution.alertFullScreen) return null
        val nm = context.getSystemService(NotificationManager::class.java) ?: return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !nm.canUseFullScreenIntent()
        ) {
            Log.i(TAG, "full-screen intent not permitted — alert stays a heads-up")
            return null
        }
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val awake = power?.isInteractive == true && keyguard?.isKeyguardLocked != true
        if (awake) return null

        val intent = Intent(context, AirAlertFullScreenActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AirAlertFullScreenActivity.EXTRA_WHERE, where)
            putExtra(AirAlertFullScreenActivity.EXTRA_PHRASE, notice.phrase)
            putExtra(AirAlertFullScreenActivity.EXTRA_AT, notice.atEpochMs)
        }
        return runCatching {
            PendingIntent.getActivity(
                context,
                NOTIF_ALARM_FULLSCREEN,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }

    private fun openMapIntent(context: Context): PendingIntent? {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_OPEN_MAP, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return runCatching {
            PendingIntent.getActivity(
                context,
                NOTIF_ALARM,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }

    private fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
    }
}
