package local.phantom.companion.ui.messenger

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Куди йде голос розмови: слухавка, гучномовець, Bluetooth, дріт.
 *
 * WebView на старті захоплення сам ставить гучномовець (A52, 08.10:
 * `setCommunicationDevice(speaker)` від нашого uid), тож голосовий дзвінок
 * звучав як гучний звʼязок із луною.
 */
class CallAudioRoute(context: Context) {

    enum class Kind(val key: String) { EARPIECE("earpiece"), SPEAKER("speaker"), BLUETOOTH("bluetooth"), WIRED("wired") }

    private val app: Context = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)
    private val power: PowerManager? = app.getSystemService(PowerManager::class.java)
    private var ear: PowerManager.WakeLock? = null
    private var legacy: Kind? = null
    private var picked = false

    // Розмова, яку цей маршрут веде: є, поки служба тримає лінію. Звідси й стеження за пристроєм.
    private var holding = false
    private var holdVideo = false
    private var fights = 0
    private var fightsSince = 0L
    private var watcher: AudioManager.OnCommunicationDeviceChangedListener? = null

    @Synchronized
    fun available(): List<Kind> {
        val am = audio ?: return emptyList()
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.availableCommunicationDevices
        } else {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        }
        return devices.mapNotNull { kindOf(it.type) }.distinct().sortedBy { it.ordinal }
    }

    @Synchronized
    fun current(): Kind? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return legacy
        return audio?.communicationDevice?.let { kindOf(it.type) }
    }

    @Synchronized
    fun select(kind: Kind): Boolean {
        val am = audio ?: return false
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val device = am.availableCommunicationDevices.firstOrNull { kindOf(it.type) == kind } ?: return false
            am.setCommunicationDevice(device)
        } else {
            selectLegacy(am, kind)
        }
        if (ok) {
            legacy = kind
            holdEar(kind == Kind.EARPIECE)
        }
        return ok
    }

    /** На початку розмови, якщо людина ще нічого не вибрала: гарнітура, інакше слухавка (відео — гучномовець). */
    @Synchronized
    fun settle(video: Boolean) {
        if (picked) return
        holding = true
        holdVideo = video
        fights = 0
        select(wanted(video))
        watch()
    }

    private fun wanted(video: Boolean): Kind {
        val have = available()
        return listOf(Kind.BLUETOOTH, Kind.WIRED).firstOrNull { it in have }
            ?: if (video) Kind.SPEAKER else Kind.EARPIECE
    }

    /**
     * WebView ставить гучномовець сам, коли рушій запускає захоплення (A52, 08.10), а голос
     * співрозмовника може піти вже ПІСЛЯ нашого settle. Тому поки розмова наша й людина не
     * вибирала, зміну пристрою вертаємо — але не більш як [MAX_FIGHTS] разів за [FIGHT_WINDOW_MS]:
     * рушій, що ставить своє без кінця, не мусить заганяти нас у війну.
     */
    @Synchronized
    internal fun onCommunicationDeviceChanged() {
        if (!holding || picked) return
        val want = wanted(holdVideo)
        if (current() == want) return
        val now = System.nanoTime() / 1_000_000L
        if (now - fightsSince > FIGHT_WINDOW_MS) {
            fightsSince = now
            fights = 0
        }
        if (fights >= MAX_FIGHTS) return
        fights++
        select(want)
    }

    private fun watch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || watcher != null) return
        val am = audio ?: return
        val listener = AudioManager.OnCommunicationDeviceChangedListener { onCommunicationDeviceChanged() }
        runCatching { am.addOnCommunicationDeviceChangedListener(app.mainExecutor, listener) }
            .onSuccess { watcher = listener }
    }

    @Synchronized
    @Suppress("DEPRECATION")
    fun release() {
        picked = false
        holding = false
        fights = 0
        holdEar(false)
        val am = audio ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            watcher?.let { runCatching { am.removeOnCommunicationDeviceChangedListener(it) } }
            watcher = null
            runCatching { am.clearCommunicationDevice() }
        } else {
            if (legacy == Kind.BLUETOOTH) {
                runCatching { am.stopBluetoothSco() }
                am.isBluetoothScoOn = false
            }
            am.isSpeakerphoneOn = false
        }
        legacy = null
    }

    fun describe(): JSONObject = JSONObject()
        .put("routes", JSONArray(available().map { it.key }))
        .put("current", current()?.key ?: JSONObject.NULL)

    @Synchronized
    fun choose(key: String): JSONObject {
        val kind = Kind.entries.firstOrNull { it.key == key }
        val ok = kind != null && select(kind)
        if (ok) picked = true
        return JSONObject().put("ok", ok).put("current", current()?.key ?: JSONObject.NULL)
    }

    @Suppress("DEPRECATION")
    private fun selectLegacy(am: AudioManager, kind: Kind): Boolean {
        if (kind !in available()) return false
        if (kind == Kind.BLUETOOTH) {
            am.startBluetoothSco()
            am.isBluetoothScoOn = true
        } else if (legacy == Kind.BLUETOOTH) {
            am.stopBluetoothSco()
            am.isBluetoothScoOn = false
        }
        am.isSpeakerphoneOn = kind == Kind.SPEAKER
        return true
    }

    private fun holdEar(on: Boolean) {
        if (!on) {
            ear?.takeIf { it.isHeld }?.let { runCatching { it.release(PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY) } }
            return
        }
        val pm = power ?: return
        if (!pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) return
        val lock = ear ?: pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "phantom:call-ear")
            .apply { setReferenceCounted(false) }
            .also { ear = it }
        if (!lock.isHeld) lock.acquire(EAR_MAX_MS)
    }

    companion object {
        private const val EAR_MAX_MS = 4 * 60 * 60 * 1000L
        private const val MAX_FIGHTS = 3
        private const val FIGHT_WINDOW_MS = 10_000L

        @Volatile private var shared: CallAudioRoute? = null

        /** Один маршрут на процес: замок близькості й вибір людини мусять пережити окремі запити мосту. */
        fun shared(context: Context): CallAudioRoute = synchronized(this) {
            shared?.takeIf { it.app === context.applicationContext }
                ?: CallAudioRoute(context).also { shared = it }
        }

        fun kindOf(type: Int): Kind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> Kind.EARPIECE
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> Kind.SPEAKER
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> Kind.BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET -> Kind.WIRED
            else -> null
        }
    }
}
