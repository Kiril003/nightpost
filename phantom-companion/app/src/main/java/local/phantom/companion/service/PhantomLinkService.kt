package local.phantom.companion.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import local.phantom.companion.PhantomApp
import local.phantom.companion.core.data.locale.AppLanguage

/**
 * Persistent foreground service holding the WS link to PHANTOM OS alive.
 *
 * Lifecycle: started after a successful pairing (or on subsequent app
 * launches if pairing data is present). Restarted by the system after
 * doze; reconnect backoff lives inside [local.phantom.companion.net.PhantomLink].
 *
 * On Android 14, foreground services with type `dataSync` must declare
 * `FOREGROUND_SERVICE_DATA_SYNC` and finish a sync within ~6 hours unless
 * promoted to a different type. The link service explicitly stops itself
 * when the user unpairs from the Pulse screen.
 *
 * Шторка належить [StatusShade]: цей рядок — один сегмент спільного рядка, а
 * не власне сповіщення.
 */
class PhantomLinkService : Service() {

    // Мова Nightpost до Android 13: служба має власний контекст, а не
    // застосунку, тож без обгортки її рядки (сповіщення) були б мовою телефона.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        alive = true
        local.phantom.companion.data.BootProbe.mark("service:PhantomLinkService")
        // Підйом — ПЕРШИМ: дедлайн 5 с система рахує від startForegroundService,
        // а перший дотик до контейнера будує весь лінивий граф і його не встигає.
        // Тип мусить збігатися з оголошеним у маніфесті: 0 на Android 14
        // означає «без типу», і система відмовляє в підйомі.
        StatusShade.attach(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            },
        )
        val container = (application as PhantomApp).container
        container.link.start()
        container.symbiote.start()
        return START_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        alive = false
        stopSelf()
    }

    override fun onDestroy() {
        alive = false
        StatusShade.detach(this)
        val container = (application as PhantomApp).container
        container.link.stop()
        super.onDestroy()
    }

    companion object {
        @Volatile
        var alive: Boolean = false

        fun ensureRunning(context: Context) {
            val container = PhantomApp.container()
            if (container.store.load() == null) return
            runCatching {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, PhantomLinkService::class.java),
                )
            }.onFailure { Log.w("PhantomLink", "служба звʼязку не піднялась", it) }
        }
    }
}
