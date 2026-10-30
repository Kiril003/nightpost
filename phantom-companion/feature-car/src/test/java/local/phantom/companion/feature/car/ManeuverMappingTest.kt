package local.phantom.companion.feature.car

import androidx.car.app.navigation.model.Maneuver
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The router speaks OSRM; the car draws an arrow. A wrong mapping is not a
 * cosmetic bug — it sends a driver into the wrong turn at speed.
 */
class ManeuverMappingTest {

    @Test
    fun `left and right never swap`() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, maneuverTypeOf("turn", "left"))
        assertEquals(Maneuver.TYPE_TURN_NORMAL_RIGHT, maneuverTypeOf("turn", "right"))
    }

    @Test
    fun `sharp and slight are distinct from normal`() {
        assertEquals(Maneuver.TYPE_TURN_SHARP_LEFT, maneuverTypeOf("turn", "sharp left"))
        assertEquals(Maneuver.TYPE_TURN_SLIGHT_RIGHT, maneuverTypeOf("turn", "slight right"))
    }

    @Test
    fun `sharp left is not read as plain left`() {
        // "sharp left" contains "left"; order of the checks is what keeps
        // these apart, and reordering them would silently degrade the arrow.
        val sharp = maneuverTypeOf("turn", "sharp left")
        assertEquals(Maneuver.TYPE_TURN_SHARP_LEFT, sharp)
        assert(sharp != maneuverTypeOf("turn", "left"))
    }

    @Test
    fun `case and spacing from the router do not matter`() {
        assertEquals(Maneuver.TYPE_TURN_NORMAL_LEFT, maneuverTypeOf("TURN", "Left"))
        assertEquals(Maneuver.TYPE_DESTINATION, maneuverTypeOf("Arrive", null))
    }

    @Test
    fun `an unknown maneuver points straight rather than guessing a side`() {
        assertEquals(Maneuver.TYPE_STRAIGHT, maneuverTypeOf("teleport", "sideways"))
        assertEquals(Maneuver.TYPE_STRAIGHT, maneuverTypeOf(null, null))
    }

    @Test
    fun `forks and ramps keep their side`() {
        assertEquals(Maneuver.TYPE_FORK_LEFT, maneuverTypeOf("fork", "left"))
        assertEquals(Maneuver.TYPE_FORK_RIGHT, maneuverTypeOf("fork", "right"))
    }
}
