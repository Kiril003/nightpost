package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.data.poi.PoiHit
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangITest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun hit(meters: Double) = PoiHit(
        id = 1L,
        lat = 50.45,
        lon = 30.52,
        name = "Test",
        category = "shop",
        subcategory = "",
        tags = emptyMap(),
        distanceMeters = meters,
    )

    @Test
    @Config(qualifiers = "en")
    fun labelsReturnEnglishUnderEn() {
        MapText.bind(context)
        assertEquals("Room", MapText.s(R.string.mapl_i_room))
        assertEquals("Toll booth", MapText.s(R.string.mapl_i_toll_booth))
        assertEquals("340 m", offlineSubtitle(hit(340.0)))
        assertEquals("2.5 km", offlineSubtitle(hit(2500.0)))
    }

    @Test
    @Config(qualifiers = "uk")
    fun labelsReturnUkrainianUnderUk() {
        MapText.bind(context)
        assertEquals("Приміщення", MapText.s(R.string.mapl_i_room))
        assertEquals("Пункт оплати", MapText.s(R.string.mapl_i_toll_booth))
        assertEquals("340 м", offlineSubtitle(hit(340.0)))
        assertEquals("2.5 км", offlineSubtitle(hit(2500.0)))
    }
}
