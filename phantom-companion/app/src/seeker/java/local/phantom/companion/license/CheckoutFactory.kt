package local.phantom.companion.license

import android.content.Context
import kotlinx.coroutines.CoroutineScope

internal object CheckoutFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, scope: CoroutineScope, license: LicenseService): Checkout? = null
}
