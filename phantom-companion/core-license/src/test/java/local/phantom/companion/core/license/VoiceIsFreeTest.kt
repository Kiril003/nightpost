package local.phantom.companion.core.license

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож на рішення, а не на код.
 *
 * Власник почув системного робота «майже скрізь» і сказав, що його «взагалі не
 * має бути». Живий голос переїхав у вільний ярус — і цей перехід легко скасувати
 * одним рядком, не помітивши: платний перелік читається як звичайний список
 * можливостей, а наслідок чути лише на пристрої, у машині, з увімкненою
 * навігацією. Тому рішення тримає тест, а не памʼять.
 *
 * Шлях до червоного: перенести VoicePremium назад у платне.
 */
class VoiceIsFreeTest {

    // Платне — це те, що дає ключ ПОНАД вільний набір. Окремої DEFAULT_PAID у
    // моделі немає (04.09: посилання на неї не компілювалось із 01.09 і тримало
    // весь тестовий набір core-license незібраним), тож рахуємо різницею.
    private val paidOnly = LicenseModel.grants(LicenseTier.Personal) - LicenseModel.DEFAULT_FREE

    @Test
    fun `the living voice is free`() {
        assertTrue(
            "живий голос знову під замком — власник казав, що робота не має бути",
            LicenseFeature.VoicePremium in LicenseModel.DEFAULT_FREE,
        )
        assertFalse(
            "живий голос повернувся у платний перелік",
            LicenseFeature.VoicePremium in paidOnly,
        )
    }

    @Test
    fun `speaking at all is not a paid feature either`() {
        assertTrue(LicenseFeature.Voice in LicenseModel.DEFAULT_FREE)
        assertFalse(LicenseFeature.Voice in paidOnly)
    }
}
