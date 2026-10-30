package local.phantom.companion.core.resilience

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Lightweight connectivity sensor backed by
 * [ConnectivityManager.NetworkCallback].
 *
 * Exposes [online] as a `StateFlow<Boolean>` so any coroutine in the app
 * can `collect` network state changes without polling.
 *
 * **Permissions**: the registration path requires
 * `android.permission.ACCESS_NETWORK_STATE`.  Declare it in the app's
 * `AndroidManifest.xml`.
 *
 * **Lifecycle**: call [close] when the owning component is destroyed to
 * unregister the callback and stop delivering updates.
 *
 * Unit-testing this class against the real Android system service is
 * impractical in a pure JVM test environment.  Integration coverage lives
 * in the instrumented test suite (Phase 3.B).
 *
 * @param connectivityManager   System service, injected for testability.
 * @param scope                 Coroutine scope used for `StateFlow.emit` calls.
 */
class NetworkProbe(
    private val connectivityManager: ConnectivityManager,
    private val scope: CoroutineScope,
) {
    private val _online = MutableStateFlow(initialOnline())

    /** `true` when the device has an active network with Internet capability. */
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        registerCallback()
    }

    // ── internal ──────────────────────────────────────────────────────────

    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    private fun initialOnline(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasInternet(caps)
    }

    @RequiresPermission(android.Manifest.permission.ACCESS_NETWORK_STATE)
    private fun registerCallback() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch { _online.emit(true) }
            }

            override fun onLost(network: Network) {
                scope.launch { _online.emit(false) }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities,
            ) {
                _online.value = hasInternet(networkCapabilities)
            }
        }

        connectivityManager.registerDefaultNetworkCallback(cb)
        callback = cb
    }

    private fun hasInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)

    /**
     * Unregisters the [ConnectivityManager.NetworkCallback].  Call this
     * from the owning component's `onDestroy` / `close` to avoid leaks.
     */
    fun close() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }
}
