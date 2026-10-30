package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLangHTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    @Config(qualifiers = "en")
    fun englishLabelsUnderEnLocale() = runTest(UnconfinedTestDispatcher()) {
        MapText.bind(context)

        val coord = NominatimClient(baseUrl = "", isEmulatorDev = true)
            .search("50.4501, 30.5234", limit = 1, near = null)
            .single()
        assertEquals("Coordinates (50.45010, 30.52340)", coord.displayName)
        assertEquals("Coordinates", coord.address?.road)

        assertEquals("Shelter source unavailable", MapText.s(R.string.mapl_h_shelter_source_down))

        assertEquals("mirror: on", MapText.s(R.string.mapl_h_mirror_on))
        assertEquals("windshield mirror", MapText.s(R.string.mapl_h_mirror_for_glass))
    }

    @Test
    @Config(qualifiers = "uk")
    fun ukrainianLabelsUnderUkLocale() = runTest(UnconfinedTestDispatcher()) {
        MapText.bind(context)

        val coord = NominatimClient(baseUrl = "", isEmulatorDev = true)
            .search("50.4501, 30.5234", limit = 1, near = null)
            .single()
        assertEquals("Координати (50.45010, 30.52340)", coord.displayName)
        assertEquals("Координати", coord.address?.road)

        assertEquals(
            "Джерело укриттів недоступне, це не означає, що їх нема",
            MapText.s(R.string.mapl_h_shelter_source_down),
        )

        assertEquals("дзеркало: увімкнено", MapText.s(R.string.mapl_h_mirror_on))
        assertEquals("дзеркало для скла", MapText.s(R.string.mapl_h_mirror_for_glass))
    }
}
