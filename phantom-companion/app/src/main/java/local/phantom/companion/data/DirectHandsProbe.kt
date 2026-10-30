package local.phantom.companion.data

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.tool.ToolRegistry
import local.phantom.companion.feature.stream.domain.DirectHandKind
import local.phantom.companion.feature.stream.domain.DirectHandsWiring

/**
 * Device truth behind the Hands Deck, kept in `:app` because only something
 * holding a Context can answer it.
 *
 * Two questions per hand: is the tool registered, and does this hardware /
 * this ROM actually have the thing. A hand that fails either is absent from
 * the deck rather than shown greyed out — an offer the tool would always
 * refuse is a promise, not a control.
 */
class DirectHandsProbe(
    private val appContext: Context,
    private val registry: ToolRegistry,
) {

    private val cameraManager: CameraManager? =
        runCatching { appContext.getSystemService(CameraManager::class.java) }.getOrNull()

    private val _torchOn = MutableStateFlow(false)

    /**
     * The camera service is the only honest source for torch state: it also
     * reports a torch switched from the notification shade, and it never
     * reports «on» for a run that failed. Registered once — this is an event
     * callback, not a frame listener, so it costs nothing while the light is
     * off.
     */
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (claim(cameraId)) _torchOn.value = enabled
        }

        override fun onTorchModeUnavailable(cameraId: String) {
            if (claim(cameraId)) _torchOn.value = false
        }
    }

    /** A phone can expose a front flash too; follow whichever unit reported first. */
    @Volatile private var watchedCameraId: String? = null

    @Synchronized
    private fun claim(cameraId: String): Boolean {
        if (watchedCameraId == null) watchedCameraId = cameraId
        return watchedCameraId == cameraId
    }

    @Volatile private var registered = false

    fun wiring(): DirectHandsWiring = DirectHandsWiring(
        // Wrapped rather than referenced so it lands in the caller's IO
        // context: camera enumeration and package resolution both block, and
        // this is built during ViewModel construction on the main thread.
        probe = { probe() },
        torchOn = _torchOn.asStateFlow(),
    )

    private fun probe(): Set<DirectHandKind> {
        val hands = mutableSetOf<DirectHandKind>()
        val flashCamera = findFlashCamera()

        if (registry.get("settings") != null) {
            hands += DirectHandKind.VOLUME_UP
            hands += DirectHandKind.VOLUME_DOWN
            if (flashCamera != null) {
                hands += DirectHandKind.FLASHLIGHT
                startWatchingTorch()
            }
        }
        if (registry.get("phone.timer") != null && hasClockApp()) hands += DirectHandKind.TIMER_5MIN
        // Settings.Panel.ACTION_WIFI ships with the platform from API 29 and
        // minSdk is 29, so a resolve check here could only produce a false no.
        if (registry.get("phone.panel") != null) hands += DirectHandKind.WIFI_PANEL

        return hands
    }

    private fun findFlashCamera(): String? = runCatching {
        cameraManager?.cameraIdList?.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }
    }.getOrNull()

    private fun startWatchingTorch() {
        if (registered) return
        registered = true
        runCatching {
            cameraManager?.registerTorchCallback(torchCallback, Handler(Looper.getMainLooper()))
        }.onFailure { registered = false }
    }

    private fun hasClockApp(): Boolean = runCatching {
        // QUERY_ALL_PACKAGES is declared, so this is not blinded by the
        // Android 11 package-visibility rules.
        appContext.packageManager
            .resolveActivity(Intent(AlarmClock.ACTION_SET_TIMER), 0) != null
    }.getOrDefault(false)
}
