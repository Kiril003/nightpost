package local.phantom.companion.core.design.license

import androidx.compose.runtime.compositionLocalOf
import local.phantom.companion.core.license.LicenseFeature

/**
 * Що цьому пристрою дозволено з платного. Живе у core-design, бо це єдиний
 * модуль, який бачать і застосунок, і потік екранів; сам вердикт рахує
 * LicenseService у застосунку.
 */
interface Entitlements {
    fun has(feature: LicenseFeature): Boolean

    /**
     * Скільки днів функція відкрита ЛИШЕ пробою, або null, якщо вона вільна,
     * куплена чи вже замкнена. Проба дає повний «Особистий» на 14 днів, тож
     * без цього людина дізнається про платність аж у мить втрати.
     */
    fun trialDaysLeft(feature: LicenseFeature): Int?

    /** Дорога з-під замка: екран ліцензії. */
    fun openLicense()
}

// Замкнено, поки застосунок не підставив живий вердикт. Забутий провайдер має
// виглядати як зачинені двері з ключем поруч, а не як безкоштовна роздача.
//
// Не static: провайдер огортає весь граф навігації, і static-локаль на зміну
// вердикту перескладала б УСЕ дерево — активація ключа посеред супроводу
// перебудувала б живу мапу.
val LocalEntitlements = compositionLocalOf<Entitlements> {
    object : Entitlements {
        override fun has(feature: LicenseFeature) = false
        override fun trialDaysLeft(feature: LicenseFeature): Int? = null
        override fun openLicense() = Unit
    }
}
