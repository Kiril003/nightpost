package local.phantom.companion.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.webkit.WebView
import local.phantom.companion.core.net.peer.PeerCallLine
import org.json.JSONObject

/** Що тримає розмову живою, коли екран згас, а Android вбив рендерер WebView. */
object CallKeep {

    private const val TAG = "nightpost:call"
    private const val CPU_CAP_MS = 4 * 60 * 60 * 1000L

    /** HIGH_PERF з Android 14 нічого не робить; LOW_LATENCY діє лише при ввімкненому екрані — сон без екрана тримає процесорний замок. */
    @Suppress("DEPRECATION")
    fun wifiMode(sdk: Int = Build.VERSION.SDK_INT): Int =
        if (sdk >= 34) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF

    class Locks(context: Context) {
        private val wifi = runCatching {
            context.applicationContext.getSystemService(WifiManager::class.java)
                ?.createWifiLock(wifiMode(), TAG)?.apply { setReferenceCounted(false) }
        }.getOrNull()
        private val cpu = runCatching {
            context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)?.apply { setReferenceCounted(false) }
        }.getOrNull()

        val wifiHeld: Boolean get() = wifi?.isHeld == true
        val cpuHeld: Boolean get() = cpu?.isHeld == true

        fun take() {
            runCatching { wifi?.acquire() }
            runCatching { cpu?.acquire(CPU_CAP_MS) }
        }

        fun drop() {
            runCatching { if (wifi?.isHeld == true) wifi.release() }
            runCatching { if (cpu?.isHeld == true) cpu.release() }
        }
    }

    interface Renderer {
        val key: Any get() = this
        val priority: Int
        val waived: Boolean
        fun set(priority: Int, waived: Boolean)
    }

    class WebRenderer(private val view: WebView) : Renderer {
        override val key: Any get() = view
        override val priority: Int get() = view.rendererRequestedPriority
        override val waived: Boolean get() = view.rendererPriorityWaivedWhenNotVisible
        override fun set(priority: Int, waived: Boolean) = view.setRendererPriorityPolicy(priority, waived)
    }

    private val saved = java.util.WeakHashMap<Any, Pair<Int, Boolean>>()

    /** Розмова йде — рендерер важливий і тоді, коли WebView не видно; скінчилась — як було. */
    fun renderer(r: Renderer, call: Boolean): Unit = synchronized(saved) {
        if (call) {
            if (r.key !in saved) saved[r.key] = r.priority to r.waived
            r.set(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        } else {
            val was = saved.remove(r.key) ?: return
            r.set(was.first, was.second)
        }
    }

    /** Що сказати новій сторінці: лише розмова, що вже йде, має що відновлювати. */
    fun resumeOf(line: PeerCallLine.Line, video: Boolean, name: (String) -> String): JSONObject? {
        val talking = line as? PeerCallLine.Line.Talking ?: return null
        return JSONObject()
            .put("call_id", talking.callId)
            .put("peer_id", talking.peerId)
            .put("name", name(talking.peerId))
            .put("media", if (video) "video" else "audio")
    }
}
