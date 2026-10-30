package local.phantom.companion.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.phantom.companion.MainActivity
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.sensor.nav.MockPosition

/**
 * ХИБНА ПОЗИЦІЯ — служба, що тримає підміну живою і **гучною**.
 *
 * Чому взагалі служба, а не один виклик. Система приймає фіктивний фікс
 * рівно доти, доки його **повторюють**: перестанеш класти — і за десятки
 * секунд застосунки навколо знову побачать справжній супутниковий. Тобто
 * підміна це не перемикач, а безперервна дія, і жити вона мусить у процесі,
 * якого не прибʼють, щойно людина згорне ФАНТОМ.
 *
 * **Сповіщення тут не ввічливість, а головна частина.** Найнебезпечніший
 * стан цієї можливості — забути, що вона ввімкнена: людина відкриває чужу
 * навігацію, та веде її від точки, де її немає. Тому сповіщення незмивне
 * (`setOngoing`), показує саме координати, які бачить світ, і має одну дію —
 * «повернути правду».
 *
 * Тип служби — `specialUse`: це не синхронізація даних і не визначення
 * розташування для себе (своє ми й далі рахуємо власним рушієм), а
 * утримання стану системи на прохання людини.
 */
class FalsePositionService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var beating = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MockPosition.stop(applicationContext)
            stopSelf()
            return START_NOT_STICKY
        }

        val lat = intent?.getDoubleExtra(EXTRA_LAT, Double.NaN) ?: Double.NaN
        val lon = intent?.getDoubleExtra(EXTRA_LON, Double.NaN) ?: Double.NaN
        if (lat.isNaN() || lon.isNaN()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val first = MockPosition.show(applicationContext, lat, lon)
        if (first is MockPosition.State.Refused) {
            // Відмову не ховаємо в лог: людина натиснула й мусить дізнатись,
            // ЧОМУ нічого не сталось, інакше вирішить, що функція зламана.
            notify(refusal = first.reason)
            stopSelf()
            return START_NOT_STICKY
        }

        startForeground(lat, lon)

        if (!beating) {
            beating = true
            scope.launch {
                // Темп навмисно частіший за типове вікно, за яке система
                // визнає фікс застарілим. Дешево: жодного радіо, лише запис
                // у провайдера.
                while (true) {
                    delay(BEAT_MS)
                    MockPosition.show(applicationContext, lat, lon)
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        beating = false
        scope.cancel()
        // Служба гине — світ мусить негайно побачити правду. Лишити підміну
        // без того, хто її тримає й показує, означало б найгірший стан:
        // неправда є, а ознаки про неї немає.
        MockPosition.stop(applicationContext)
        super.onDestroy()
    }

    private fun startForeground(lat: Double, lon: Double) {
        val n = build(
            title = getString(R.string.false_position_title),
            text = getString(R.string.false_position_text, lat, lon),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat_startForeground(this, NOTIFICATION_ID, n)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun notify(refusal: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(this)
        nm.notify(NOTIFICATION_ID, build(getString(R.string.false_position_refused), refusal))
    }

    private fun build(title: String, text: String): Notification {
        ensureChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FalsePositionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(NotificationLook.smallIcon)
            // Підміна триває — це стан ризику, не дія: червоне, не бурштин.
            .setColor(NotificationLook.danger)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(open)
            .addAction(0, getString(R.string.false_position_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "false_position"
        private const val NOTIFICATION_ID = 8801
        private const val BEAT_MS = 900L
        const val ACTION_STOP = "local.phantom.companion.FALSE_POSITION_STOP"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL,
                    context.getString(R.string.false_position_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { setShowBadge(false) },
            )
        }

        /** Почати показувати світові цю точку. */
        fun show(context: Context, lat: Double, lon: Double) {
            val i = Intent(context, FalsePositionService::class.java)
                .putExtra(EXTRA_LAT, lat)
                .putExtra(EXTRA_LON, lon)
            ContextCompat.startForegroundService(context, i)
        }

        /** Повернути світові правду. */
        fun stop(context: Context) {
            val i = Intent(context, FalsePositionService::class.java).setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, i)
        }
    }
}

/** Тип служби потрібен лише з API 34; винесено, щоб гілка читалась. */
private fun ServiceCompat_startForeground(service: Service, id: Int, n: Notification) {
    androidx.core.app.ServiceCompat.startForeground(
        service, id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
    )
}
