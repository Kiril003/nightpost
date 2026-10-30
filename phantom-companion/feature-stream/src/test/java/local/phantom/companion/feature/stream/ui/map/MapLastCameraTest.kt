package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapLastCameraTest {
    private val prefs = MapLastCamera.prefs(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun nothingSavedMeansNoSpot() {
        prefs.edit().clear().commit()
        assertNull(MapLastCamera.read(prefs))
    }

    @Test
    fun lastPlaceComesBack() {
        prefs.edit().clear().commit()
        MapLastCamera.write(prefs, 49.8397, 24.0297, 13.5)
        val spot = MapLastCamera.read(prefs)!!
        assertEquals(49.8397, spot.lat, 1e-4)
        assertEquals(24.0297, spot.lon, 1e-4)
        assertEquals(13.5, spot.zoom, 1e-6)
    }

    @Test
    fun worldViewAtNullIslandIsNotAPlace() {
        prefs.edit().clear().commit()
        MapLastCamera.write(prefs, 0.0, 0.0, 2.0)
        assertNull(MapLastCamera.read(prefs))
    }
}
