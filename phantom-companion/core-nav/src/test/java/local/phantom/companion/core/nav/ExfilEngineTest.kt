package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExfilEngineTest {

    @Test
    fun escapesAwayFromClosestThreat() {
        val opLat = 50.450
        val opLon = 30.520

        // Threat 1 km to the South (50.441, 30.520)
        val threats = listOf(
            Triple(50.441, 30.520, "Артобстріл / Південь"),
            Triple(50.500, 30.520, "Далека загроза / Північ"),
        )

        val target = ExfilEngine.computeExfilTarget(
            operatorLat = opLat,
            operatorLon = opLon,
            threats = threats,
            desiredDistanceM = 3000.0,
        )

        assertNotNull(target)
        // Vector from south threat through operator must point NORTH (approx 0° bearing)
        assertEquals(0.0, target.escapeBearingDeg.toDouble(), 5.0)
        // Target latitude must be greater than operator latitude
        assertTrue(target.targetLat > opLat)
        assertTrue(target.nearestThreatDistanceM in 900.0..1100.0)
        assertTrue(target.explanationUk.contains("Артобстріл"))
    }

    @Test
    fun routesToSafeAnchorInEscapeCone() {
        val opLat = 50.450
        val opLon = 30.520

        // Threat south (50.441, 30.520) -> Escape is North (0°)
        val threats = listOf(Triple(50.441, 30.520, "Загроза"))

        // Shelter 2 km to the North (50.468, 30.520)
        val safeAnchors = listOf(
            50.468 to 30.520, // North safe anchor
            50.430 to 30.520, // South anchor (inside danger zone, must be ignored)
        )

        val target = ExfilEngine.computeExfilTarget(
            operatorLat = opLat,
            operatorLon = opLon,
            threats = threats,
            safeAnchors = safeAnchors,
            desiredDistanceM = 4000.0,
        )

        assertEquals(50.468, target.targetLat, 1e-4)
        assertEquals(30.520, target.targetLon, 1e-4)
        assertTrue(target.explanationUk.contains("безпечне укриття"))
    }
}
