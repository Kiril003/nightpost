package local.phantom.companion.feature.stream.ui.map

import android.content.Context
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.net.map.RouteProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The route line is built from resources: units and modes follow the app language. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteSummaryLocaleTest {

    private val res: Resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    @Config(qualifiers = "en")
    fun `English summary has English units and modes`() {
        assertEquals("800 m · 18 min, on foot", routeSummaryLine(res, 800.0, 1080.0, RouteProfile.FOOT))
        val long = routeSummaryLine(res, 2000.0, 4500.0, RouteProfile.CAR)
        assertTrue(long, Regex("""^2[.,]0 km · 1 h 15 min, drive$""").matches(long))
        assertEquals("800 m · 18 min, bike", routeSummaryLine(res, 800.0, 1080.0, RouteProfile.BIKE))
        assertEquals("800 m · 18 min, transit", routeSummaryLine(res, 800.0, 1080.0, RouteProfile.TRANSIT))
    }

    @Test
    @Config(qualifiers = "uk")
    fun `Ukrainian summary keeps the profile labels, transit now reads транспорт`() {
        val labels = RouteProfile.entries.associateWith {
            routeSummaryLine(res, 800.0, 1080.0, it).substringAfter(", ")
        }
        assertEquals(RouteProfile.FOOT.label, labels[RouteProfile.FOOT])
        assertEquals(RouteProfile.CAR.label, labels[RouteProfile.CAR])
        assertEquals(RouteProfile.BIKE.label, labels[RouteProfile.BIKE])
        assertEquals("транспорт", labels[RouteProfile.TRANSIT])
        assertEquals("800 м · 18 хв, пішки", routeSummaryLine(res, 800.0, 1080.0, RouteProfile.FOOT))
    }
}
