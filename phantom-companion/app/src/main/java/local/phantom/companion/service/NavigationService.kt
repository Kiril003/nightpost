package local.phantom.companion.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import local.phantom.companion.MainActivity
import local.phantom.companion.PhantomApp
import local.phantom.companion.R
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.NavigationEngine
import local.phantom.companion.core.nav.maneuverShort
import java.util.Locale
import kotlin.math.roundToInt

/**
 * NAV-FGS (brief 03 §4.1) — the one thing only a Service can do for a drive:
 * **hold the process alive and keep GPS legal while the app is backgrounded.**
 *
 * The drive itself is NOT owned here. It belongs to the process-scoped
 * [NavigationEngine] on [AppContainer.scope] — the same design that lets the
 * car head navigate with no Activity alive (see NavigationEngine's header).
 * Before this service, that engine kept *running* when the phone was
 * backgrounded, but the OS was free to (a) kill the process to reclaim memory
 * and (b) throttle FusedLocationProvider delivery to a trickle without a
 * foreground service of type `location` — so a drive that left the screen
 * either froze on a stale fix or died outright. This service closes both holes
 * and shows the operator an honest ongoing notification while it does.
 *
 * Lifecycle owned by [AppContainer]: it starts the service when
 * `navEngine.state.guiding` flips true and stops it when the drive ends
 * (Idle/Arrived). The service is a mirror of that state, never a second
 * source of truth — killing and restarting it must not perturb the drive.
 */
class NavigationService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var stateWatchJob: Job? = null

    /** The last content actually handed to the shade. */
    private var posted: Shade? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        local.phantom.companion.data.BootProbe.mark("service:NavigationService")
        // Підйом — ПЕРШИМ, із мінімальним «чекаю»: дедлайн 5 с система рахує від
        // startForegroundService, а `navEngine` живе в лінивому контейнері, який
        // перший дотик будує цілком. Відмова нижче теж мусить статись ПІСЛЯ
        // підйому — службу запустили як передню, і зупинка без startForeground
        // це те саме порушення, що й запізнення.
        val waiting = shadeOf(progress = null, blind = true, waitingTitle = waitingTitle(), locale = appLocale())
        posted = waiting
        startForegroundLocation(buildNotification(waiting))

        val container = (application as PhantomApp).container
        val engine = container.navEngine

        // If we were (re)started while no drive is live — a sticky restart after
        // the OS killed us, or a race with a `stop` — do not pin a phantom
        // notification. Reflect the truth and stand down.
        if (!engine.state.value.guiding) {
            Log.i(TAG, "NavigationService started with no live drive — stopping")
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        val first = shadeOf(engine.state.value.progress, engine.state.value.blind, waitingTitle(), appLocale())
        posted = first
        updateNotification(first)

        // Mirror the engine's published NavState into the notification. When the
        // drive ends (the operator arriving, or `nav.stop`), the service stops
        // itself — the AppContainer driver would stop it too, but self-stopping
        // on the terminal state avoids a stale banner for the hop in between.
        stateWatchJob = scope.launch {
            engine.state.collectLatest { st ->
                if (!st.guiding) {
                    stopForegroundCompat()
                    stopSelf()
                    return@collectLatest
                }
                // The engine publishes a NavState per fix — up to 4 Hz. The
                // shade renders metres rounded to ten and whole minutes, so
                // almost every one of those is the same two lines. Posting it
                // anyway is a Binder round-trip and a fresh PendingIntent
                // several times a second for a row nobody saw change.
                val shade = shadeOf(st.progress, st.blind, waitingTitle(), appLocale())
                if (shade == posted) return@collectLatest
                posted = shade
                updateNotification(shade)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        stateWatchJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundLocation(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            0
        }
        runCatching {
            ServiceCompat.startForeground(this, NOTIF_ID, notification, type)
        }.onFailure { t ->
            // ForegroundServiceStartNotAllowed / missing-permission: never crash
            // the process over the notification — the engine keeps driving on
            // its own scope even if the FGS could not be promoted.
            Log.e(TAG, "startForeground(location) failed — drive continues without the FGS", t)
        }
    }

    private fun waitingTitle(): String = getString(R.string.nav_service_status_waiting)

    private fun updateNotification(shade: Shade) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(shade))
    }

    private fun buildNotification(shade: Shade): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val piFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getActivity(this, 0, intent, piFlags)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(NotificationLook.smallIcon)
            .setColor(NotificationLook.accent)
            .setContentTitle(shade.title)
            .apply { shade.text?.let { setContentText(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            // Маневр і назва дороги — це куди їде екіпаж. Замкнений екран на
            // торпеді читає їх без жодного дотику.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(NotificationLook.smallIcon)
                    .setColor(NotificationLook.accent)
                    .setContentTitle(getString(R.string.nav_service_status_default))
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .build(),
            )
            .build()
    }

    /** The service context is wrapped in the app language (see attachBaseContext). */
    private fun appLocale(): Locale = resources.configuration.locales[0] ?: Locale.getDefault()

    private fun stopForegroundCompat() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    companion object {
        const val CHANNEL_ID = "phantom_navigation_v1"
        const val NOTIF_ID = 4203
        private const val TAG = "NavigationService"

        /** Exactly what the shade shows. Two drives that render this equal are
         *  indistinguishable to the operator, so only one of them is posted. */
        internal data class Shade(val title: String, val text: String?)

        internal fun shadeOf(
            progress: GuidanceProgress?,
            blind: Boolean,
            waitingTitle: String,
            locale: Locale,
        ): Shade = Shade(
            title = if (blind || progress == null) {
                waitingTitle
            } else {
                maneuverShort(
                    progress.maneuverType,
                    progress.maneuverModifier,
                    progress.roadName,
                    progress.exitNumber,
                    locale = locale,
                ).replaceFirstChar { it.uppercase() }
            },
            text = progress?.let {
                if (locale.language == "uk") remainingLineUk(it) else remainingLine(it, "km", "m", "min")
            },
        )

        /** «650 м · 3 хв» — coarse, TTS-free, locale-pinned so a uk_UA default
         *  never renders «1,2 км» where the tests (and the rest of the UI)
         *  expect a dot. */
        internal fun remainingLineUk(p: GuidanceProgress): String = remainingLine(p, "км", "м", "хв")

        private fun remainingLine(p: GuidanceProgress, km: String, m: String, min: String): String {
            val distM = p.remainingDistanceM
            val dist = if (distM >= 950.0) {
                "%.1f $km".format(Locale.ROOT, distM / 1000.0)
            } else {
                "${(distM / 10.0).roundToInt() * 10} $m"
            }
            val minutes = (p.remainingDurationS / 60.0).roundToInt().coerceAtLeast(1)
            return "$dist · $minutes $min"
        }
    }
}
