package local.phantom.companion.data.nearby

import android.content.Context
import kotlinx.coroutines.CoroutineScope

internal object NearbyFlavor {
    fun courier(
        context: Context,
        scope: CoroutineScope,
        selfId: () -> String,
        contacts: () -> Collection<String>,
        online: () -> Boolean,
        inbound: (String) -> String?,
    ): NearbyCourier = GmsNearbyCourier(context.applicationContext, scope, selfId, contacts, online, inbound)
}
