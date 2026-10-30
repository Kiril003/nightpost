package local.phantom.companion.packaging

import local.phantom.companion.BuildConfig
import local.phantom.companion.Distribution
import local.phantom.companion.data.intent.IntentSmsFlavor
import local.phantom.companion.ui.settings.UpdateSection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SeekerDistributionTest {

    @Test fun `ставиться поруч із кабінетною збіркою`() {
        assertEquals("local.phantom.companion.seeker", BuildConfig.APPLICATION_ID.removeSuffix(".debug"))
    }

    @Test fun `кнопки знятих можливостей сховані`() {
        assertFalse(Distribution.overlays)
        assertFalse(Distribution.falsePosition)
        assertFalse(Distribution.backgroundLocation)
        assertFalse(Distribution.isStore)
        // Маніфест store знімає службу спецможливостей і слухача сповіщень — і тут.
        assertFalse(Distribution.systemListeners)
        assertFalse(IntentSmsFlavor.SMS)
    }

    @Test fun `сам себе не оновлює і кабінетних збірок не пропонує`() {
        assertFalse(Distribution.selfUpdate)
        assertFalse(Distribution.cabinetUpdates)
        assertEquals(
            UpdateSection.ViaDappStore,
            UpdateSection.of(developerBuild = false, announced = null, licensed = true, viaDappStore = !Distribution.cabinetUpdates),
        )
    }

    @Test fun `Play Billing у збірку не потрапив`() {
        try {
            Class.forName("com.android.billingclient.api.BillingClient")
            fail("BillingClient на шляху класів seeker")
        } catch (_: ClassNotFoundException) {
            assertTrue(true)
        }
    }
}
