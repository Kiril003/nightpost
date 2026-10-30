package local.phantom.companion.license

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import local.phantom.companion.BuildConfig

/**
 * Store-збірка продає ліцензію лише через Google Play Billing. Порожній
 * каталог (PLAY_PRODUCTS не задано при складанні) — чесне «купити нічого»,
 * а не кнопка, за якою Play відповість «товару немає».
 */
internal object CheckoutFactory {
    fun create(context: Context, scope: CoroutineScope, license: LicenseService): Checkout? {
        val catalogue = PlayCatalogue.parse(BuildConfig.PLAY_PRODUCTS)
        if (catalogue.isEmpty()) return null
        return PlayCheckout(context, scope, license, catalogue)
    }
}
