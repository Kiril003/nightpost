package local.phantom.companion.feature.stream.ui.map

import java.io.File
import local.phantom.companion.feature.stream.ui.map.atlas.CameraService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceMotionTest {
    private val dir = "src/main/java/local/phantom/companion/feature/stream/ui/map"
    private fun src(name: String) = File("$dir/$name").readText()

    @Test
    fun `reduced motion lands on the place without a flight`() {
        assertEquals(0, PlaceFocus.flyMs(reduced = true))
        assertEquals(CameraService.FOCUS_MS, PlaceFocus.flyMs(reduced = false))
    }

    @Test
    fun `a zero flight moves the camera instead of a swallowed animate`() {
        assertTrue(src("MapAtlasPort.kt").contains("if (durationMs <= 0) map.moveCamera(update) else map.animateCamera(update, durationMs)"))
    }

    @Test
    fun `the place flight follows the motion setting`() {
        val screen = src("MapScreen.kt")
        assertTrue(screen.contains("PlaceFocus.flyMs(local.phantom.companion.core.design.motion.PhantomMotion.reducedMotion())"))
        assertTrue(screen.contains("focusResult(t.lat, t.lon, viewportHeightDp(), placeFlyMs)"))
    }

    @Test
    fun `the place sheet springs in and snaps when motion is reduced`() {
        val s = src("MapSheetScaffold.kt")
        val sheet = s.substring(s.indexOf("internal fun MapPlaceSheet("))
        assertTrue(sheet.contains("PhantomMotion.reducedMotion()"))
        assertTrue(sheet.contains("spring(Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)"))
        assertTrue(sheet.contains("EnterTransition.None"))
    }
}
