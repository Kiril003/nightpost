package local.phantom.companion.service

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.ui.messenger.CallAudioRoute

/** Тримає процес, мікрофон і камеру, поки йде розмова: рушій живе у WebView, і замкнений екран забирав у нього все. */
open class PeerCallService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    internal val keep by lazy { CallKeep.Locks(this) }

    /** До Android 13 служба має власний контекст: без обгортки «Розмова триває» в шторці — мовою телефона. */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    /**
     * Кожен вхід спершу виходить на передній план — навіть той, що прийшов зупинити службу.
     * `startForegroundService` без `startForeground` вбиває ПРОЦЕС (`ForegroundServiceDidNotStartInTimeException`),
     * і `stopSelf()` до нього — теж: система рахує це за «не встигла».
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = PeerCallNotifications.ongoingNotification(
            this,
            intent?.getStringExtra(EXTRA_WHO).orEmpty(),
            intent?.getStringExtra(EXTRA_TEXT) ?: getString(local.phantom.companion.R.string.peer_call_talking),
        )
        val stopping = intent?.action == ACTION_STOP
        if (stopping || !granted(Manifest.permission.RECORD_AUDIO)) {
            lift(notification, listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST))
            leave()
            return START_NOT_STICKY
        }
        val video = intent?.getBooleanExtra(EXTRA_VIDEO, false) == true
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (video && granted(Manifest.permission.CAMERA)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        // Камеру система може не пустити (дозвіл відкликали, тип не оголошено): мікрофон мусить вціліти.
        val lifted = lift(
            notification,
            listOf(type, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST),
        )
        if (lifted == null) {
            leave()
            return START_NOT_STICKY
        }
        window.foregrounded()
        running = true
        keep.take()
        CallAudioRoute.shared(this).settle(video)
        return START_NOT_STICKY
    }

    private fun leave() {
        window.foregrounded()
        running = false
        keep.drop()
        stopSelf()
    }

    /** Перший тип, який система прийняла, або null, якщо не прийняла жодного. */
    private fun lift(notification: Notification, types: List<Int>): Int? {
        for (type in types.distinct()) {
            val taken = runCatching { foreground(notification, type) }
                .onFailure { Log.w(TAG, "startForeground type=$type", it) }
                .isSuccess
            if (taken) return type
        }
        return null
    }

    internal open fun foreground(notification: Notification, type: Int) =
        ServiceCompat.startForeground(this, PeerCallNotifications.ID, notification, type)

    override fun onDestroy() {
        running = false
        keep.drop()
        CallAudioRoute.shared(this).release()
        super.onDestroy()
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "PhantomCall"
        private const val EXTRA_VIDEO = "video"
        private const val EXTRA_WHO = "who"
        private const val EXTRA_TEXT = "text"
        internal const val ACTION_STOP = "local.phantom.companion.service.call.STOP"

        @Volatile internal var running = false

        /** Вікно між `startForegroundService` і `startForeground`: у ньому службу не гасять, їй шлють зупинку. */
        internal val window = ForegroundStartWindow()

        fun intent(context: Context, video: Boolean): Intent =
            Intent(context, PeerCallService::class.java).putExtra(EXTRA_VIDEO, video)

        // startForegroundService без вдалого startForeground вбиває процес за 10 с. Мікрофонна служба
        // з фону не стартує (Android 14), тож з фону систему не кличемо: запущена служба вже тримає
        // процес, а текст її плашки оновив notify з тим самим id.
        internal fun start(context: Context, who: String, text: String, video: Boolean) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            if (!onScreen()) {
                if (running) CallAudioRoute.shared(context).settle(video)
                return
            }
            val intent = intent(context, video).putExtra(EXTRA_WHO, who).putExtra(EXTRA_TEXT, text)
            window.startRequested()
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure {
                    window.startRefused()
                    Log.w(TAG, "call service not started", it)
                }
        }

        /**
         * Лише передній план. `IMPORTANCE_VISIBLE` (200) і навіть службовий 125 тут не годяться:
         * `PeerMeshService` живе всю розмову, тож замкнений екран — це 125, і з нього мікрофонну
         * службу система не пускає.
         */
        private fun onScreen(): Boolean {
            val me = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(me)
            return me.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }

        /**
         * Служба ще не вийшла на передній план — `stopService` зараз коштував би процесу.
         * Тоді їй їде наміром зупинка: вона вийде, а тоді піде.
         */
        internal fun stop(context: Context) {
            val intent = Intent(context, PeerCallService::class.java)
            when (window.stopKind()) {
                ForegroundStartWindow.StopKind.STOP_SERVICE ->
                    runCatching { context.stopService(intent) }

                ForegroundStartWindow.StopKind.DELIVER_STOP ->
                    runCatching { ContextCompat.startForegroundService(context, intent.setAction(ACTION_STOP)) }
                        .onFailure { runCatching { context.stopService(intent) } }
            }
        }
    }
}
