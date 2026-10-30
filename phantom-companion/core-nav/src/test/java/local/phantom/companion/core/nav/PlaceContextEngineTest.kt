package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceContextEngineTest {

    private val homeAnchor = PlaceContextEngine.GeoAnchor(
        name = "База (Київ)",
        lat = 50.450,
        lon = 30.520,
        radiusMeters = 200.0,
    )

    private val workAnchor = PlaceContextEngine.GeoAnchor(
        name = "Офіс (Поділ)",
        lat = 50.465,
        lon = 30.515,
        radiusMeters = 150.0,
    )

    @Test
    fun detectsHomeBaseContext() {
        val engine = PlaceContextEngine()
        val state = engine.evaluateContext(
            currentLat = 50.4501,
            currentLon = 30.5201,
            speedMs = 0f,
            homeAnchor = homeAnchor,
            workAnchor = workAnchor,
        )

        assertEquals(PlaceContextEngine.SemanticPlaceMode.HOME_BASE, state.mode)
        assertEquals("База (Київ)", state.activeAnchorName)
        assertFalse(state.homeSentryActive)
        assertEquals("ZEN_MINIMAL", state.suggestedMapMode)
    }

    @Test
    fun detectsWorkOfficeAndArmsHomeSentry() {
        val engine = PlaceContextEngine()
        val state = engine.evaluateContext(
            currentLat = 50.4651,
            currentLon = 30.5151,
            speedMs = 0f,
            homeAnchor = homeAnchor,
            workAnchor = workAnchor,
        )

        assertEquals(PlaceContextEngine.SemanticPlaceMode.WORK_OFFICE, state.mode)
        assertEquals("Офіс (Поділ)", state.activeAnchorName)
        assertTrue(state.homeSentryActive)
    }

    @Test
    fun detectsActiveTransit() {
        val engine = PlaceContextEngine()
        val state = engine.evaluateContext(
            currentLat = 50.480,
            currentLon = 30.500,
            speedMs = 15.0f, // 54 km/h
            homeAnchor = homeAnchor,
            workAnchor = workAnchor,
        )

        assertEquals(PlaceContextEngine.SemanticPlaceMode.TRANSIT_ACTIVE, state.mode)
        assertEquals("TRANSIT", state.suggestedMapMode)
        assertTrue(state.homeSentryActive)
    }

    @Test
    fun escalatesToDangerSectorOnAlert() {
        val engine = PlaceContextEngine()
        val state = engine.evaluateContext(
            currentLat = 50.450,
            currentLon = 30.520,
            speedMs = 0f,
            homeAnchor = homeAnchor,
            isAlertActiveInArea = true,
        )

        assertEquals(PlaceContextEngine.SemanticPlaceMode.DANGER_SECTOR, state.mode)
        assertEquals("TACTICAL", state.suggestedMapMode)
    }
}
