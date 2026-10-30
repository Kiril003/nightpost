package local.phantom.companion.feature.stream.ui.map

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 18.10: полиця шукала категорії за українською назвою — англійська й чеська падали на старті капсули. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en")
class NearbyCatsLocaleTest {

    @Test
    fun `english shelf still has twelve cards and hides UA-only abroad`() {
        MapText.bind(ApplicationProvider.getApplicationContext())
        assertEquals(12, NEARBY_CATS_ALL.size)
        val abroad = nearbyCatsFor("DE")
        assertEquals(10, abroad.size)
        assertFalse(abroad.any { it.label == "Shelters" })
        assertEquals(12, nearbyCatsFor("UA").size)
    }
}
