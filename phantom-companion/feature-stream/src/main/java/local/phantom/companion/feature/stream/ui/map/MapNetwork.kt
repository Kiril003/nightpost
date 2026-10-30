package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

private fun Context.hasInternet(): Boolean {
    val cm = getSystemService(ConnectivityManager::class.java) ?: return true
    val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) } ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

@Composable
internal fun rememberMapOnline(): State<Boolean> {
    val context = LocalContext.current
    val online = remember { mutableStateOf(context.hasInternet()) }
    DisposableEffect(context) {
        val main = Handler(Looper.getMainLooper())
        // A handover between Wi-Fi and mobile fires onLost first; settling avoids
        // reloading the whole style twice for one hiccup.
        val net = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.removeCallbacksAndMessages(null)
                main.post { online.value = true }
            }
            override fun onLost(network: Network) {
                main.postDelayed({ online.value = context.hasInternet() }, MAP_NET_SETTLE_MS)
            }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val registered = cm != null && runCatching { cm.registerDefaultNetworkCallback(net, main) }.isSuccess
        onDispose {
            main.removeCallbacksAndMessages(null)
            if (registered) runCatching { cm?.unregisterNetworkCallback(net) }
        }
    }
    return online
}

private const val MAP_NET_SETTLE_MS = 1_500L
