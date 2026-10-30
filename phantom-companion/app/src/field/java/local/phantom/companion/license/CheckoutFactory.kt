package local.phantom.companion.license

import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Польова збірка їде повз Play і купівлі в застосунку не має: ключ купують на
 * сайті й вводять на екрані ліцензії. Близнюк цього файла лежить у src/store.
 */
internal object CheckoutFactory {
    @Suppress("UNUSED_PARAMETER")
    fun create(context: Context, scope: CoroutineScope, license: LicenseService): Checkout? = null
}
