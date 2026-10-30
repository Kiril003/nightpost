package local.phantom.companion.data.nearby

import android.content.Context
import kotlinx.coroutines.CoroutineScope

// Play: без Bluetooth-дозволів у маніфесті — купюра їде лише спільною Wi-Fi або сховком.
internal object NearbyFlavor {
    @Suppress("UNUSED_PARAMETER")
    fun courier(
        context: Context,
        scope: CoroutineScope,
        selfId: () -> String,
        contacts: () -> Collection<String>,
        online: () -> Boolean,
        inbound: (String) -> String?,
    ): NearbyCourier = NoNearby()
}
