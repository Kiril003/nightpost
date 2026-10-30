package local.phantom.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.locale.AppLanguage

/**
 * Keeps the mesh reachable while the app is not on screen.
 *
 * A peer that can only be heard while a chat is open is not a peer — it is a
 * feature that works in demos. Somebody writing at 03:00 is exactly the case
 * this product exists for, and it is the case where every screen is dark.
 *
 * Also holds a multicast lock: Android drops multicast packets by default to
 * save battery, and mDNS discovery is multicast — without this the beacon
 * announces into a network nobody is listening to.
 *
 * Черга недоставленого — сегмент спільного рядка [StatusShade], не окрема плашка.
 */
class PeerMeshService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var lock: WifiManager.MulticastLock? = null
    private var booted = false

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * `startForeground` — ПЕРШИМ рядком, до будь-якого дотику до контейнера.
     *
     * Система рахує дедлайн (5 с) від `startForegroundService`, а
     * `(application as PhantomApp).container` — це ЛІНИВИЙ граф: перший дотик
     * будує його цілком. На навантаженому старті це не встигало, і процес
     * падав із `ForegroundServiceDidNotStartInTimeException` ще до першого
     * екрана. Саме цей FATAL приймали за крах континуума.
     *
     * Тому тут лише підняття служби з готовим сповіщенням; контейнер, мультикаст
     * і сітка — уже за дедлайном, у власній області.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        local.phantom.companion.data.BootProbe.mark("service:PeerMeshService")
        StatusShade.attach(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        window.foregrounded()
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Ворота стукають знову на кожен вихід на передній план, поки сітка ще піднімається.
        if (booted) return START_STICKY
        booted = true
        scope.launch {
            val container = runCatching { (application as PhantomApp).container }.getOrNull()
            if (container == null) {
                stopSelf()
                return@launch
            }
            acquireMulticast()
            container.mesh.start(scope)
            runCatching { container.relayFs.start(scope) }
            runCatching { container.disappear.start(scope) }
            runCatching { container.moments.start(scope) }
            runCatching { container.intents.start(scope) }
            runCatching { container.peopleWhere }
            runCatching { container.profileHerald }
        }
        return START_STICKY
    }

    /**
     * mDNS is multicast, and Android silently drops multicast to save power.
     * Without the lock discovery works on the emulator and on nobody's phone.
     */
    private fun acquireMulticast() {
        if (lock?.isHeld == true) return
        val wifi = getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        lock = runCatching {
            wifi.createMulticastLock("phantom:mesh").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        StatusShade.detach(this)
        runCatching { (application as PhantomApp).container.mesh.stop() }
        lock?.takeIf { it.isHeld }?.release()
        lock = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_STOP = "local.phantom.companion.service.mesh.STOP"

        private val window = ForegroundStartWindow()

        /** Started once this device has an identity; harmless to call twice. */
        fun ensureRunning(context: Context) {
            val intent = Intent(context, PeerMeshService::class.java)
            window.startRequested()
            runCatching { deliver(context, intent) }.onFailure { window.startRefused() }
        }

        /**
         * [PeerMeshGate] перемикає службу щоразу, коли реєстр або екран «Люди»
         * змінюються — а системний діалог дозволу робить це кілька разів за
         * секунду. `stopService` усередині вікна підйому коштує процесу.
         */
        fun stop(context: Context) {
            val intent = Intent(context, PeerMeshService::class.java)
            when (window.stopKind()) {
                ForegroundStartWindow.StopKind.STOP_SERVICE ->
                    runCatching { context.stopService(intent) }

                ForegroundStartWindow.StopKind.DELIVER_STOP ->
                    runCatching { deliver(context, intent.setAction(ACTION_STOP)) }
                        .onFailure { runCatching { context.stopService(intent) } }
            }
        }

        private fun deliver(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}
