package local.phantom.companion.feature.stream.ui.map.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The arrow and the voice must agree. The operator's report was the opposite:
 * a glyph saying «straight on» under a cue saying «right».
 *
 * Since the sign grid landed, the arrow is also drawn on one of five canonical
 * angles instead of on whatever OSRM last measured. That is a change of LOOK,
 * never of meaning, and this file pins the line between the two: the side, the
 * word's veto over a stale bearing and the refusal to draw a named turn as a
 * straight run are all still asserted exactly as before; what is new is that
 * the OUTPUT may only ever be one of the five drawable angles.
 */
class ManeuverGlyphTest {

    private val canons = setOf(0f, 35f, -35f, 90f, -90f, 135f, -135f, 180f)

    @Test
    fun `with no bearings the modifier still decides the arrow`() {
        assertEquals(90f, canonBendDeg("right", null), 0.01f)
        assertEquals(-90f, canonBendDeg("left", null), 0.01f)
        assertEquals(0f, canonBendDeg("straight", null), 0.01f)
    }

    @Test
    fun `the real angle beats the bucket, snapped to its canon`() {
        // A junction that bends 42° was drawn as a full 90° corner. It is now
        // drawn as the slight turn it is — but as THE slight turn, not as 42°.
        assertEquals(35f, canonBendDeg("right", 42f), 0.01f)
        assertEquals(-35f, canonBendDeg("left", -42f), 0.01f)
    }

    @Test
    fun `a named turn is never drawn as going straight on`() {
        // OSRM reporting ~0° on a step whose modifier says «right» is the
        // exact contradiction the operator saw. The word wins the side, and
        // the arrow must still bend enough to read as a turn.
        val bend = canonBendDeg("right", 3f)
        assertTrue("an arrow for «поверніть праворуч» must lean right: $bend", bend >= 35f)
    }

    @Test
    fun `bearings disagreeing about the side lose to the spoken word`() {
        // A wrapped or stale bearing must never point the arrow opposite to
        // what the voice just said.
        assertEquals(90f, canonBendDeg("right", -70f), 0.01f)
        assertEquals(-90f, canonBendDeg("left", 70f), 0.01f)
    }

    @Test
    fun `an absurd angle lands on the sharpest canon instead of spinning`() {
        assertEquals(135f, canonBendDeg("right", 900f), 0.01f)
        assertEquals(-135f, canonBendDeg("left", -900f), 0.01f)
    }

    @Test
    fun `a bend too gentle to be a decision is drawn straight`() {
        // The same 18° floor ManeuverPins uses to decide a step is worth a
        // marker at all: below it the route line already shows the bend.
        assertEquals(0f, canonBendDeg("straight", 15f), 0.01f)
        assertEquals(35f, canonBendDeg("straight", 45f), 0.01f)
    }

    @Test
    fun `every drawable angle is one of the five canons`() {
        val modifiers = listOf(
            null, "straight", "left", "right", "slight left", "slight right",
            "sharp left", "sharp right", "uturn",
        )
        val magnitudes = listOf(0f, 3f, 17f, 18f, 42f, 59f, 61f, 95f, 109f, 111f, 179f, 900f)
        val angles: List<Float?> = listOf(null) + magnitudes.flatMap { listOf(it, -it) }
        for (m in modifiers) {
            for (a in angles) {
                val drawn = canonBendDeg(m, a)
                assertTrue(
                    "modifier=$m angle=$a drew $drawn — off the canon grid",
                    canons.any { kotlin.math.abs(it - drawn) < 0.01f },
                )
            }
        }
    }

    @Test
    fun `the roundabout arm follows the exit, not the modifier`() {
        // Exit 2 is straight on — the case the operator reported as «shows
        // straight, says right».
        assertEquals(0f, roundaboutExitBendDeg(2, fallback = 90f), 0.01f)
        assertEquals(90f, roundaboutExitBendDeg(1, fallback = 0f), 0.01f)
        assertEquals(-90f, roundaboutExitBendDeg(3, fallback = 0f), 0.01f)
    }

    @Test
    fun `late exits share the sharpest arm and differ by how much ring is driven`() {
        assertEquals(-135f, roundaboutExitBendDeg(4, fallback = 0f), 0.01f)
        assertEquals(-135f, roundaboutExitBendDeg(6, fallback = 0f), 0.01f)
    }

    @Test
    fun `with no exit number the modifier is all there is`() {
        assertEquals(35f, roundaboutExitBendDeg(null, fallback = 35f), 0.01f)
    }

    @Test
    fun `the ring is driven counter-clockwise and further for later exits`() {
        val first = drivenSweepDeg(90f)
        val second = drivenSweepDeg(0f)
        val third = drivenSweepDeg(-90f)
        assertTrue("every sweep must be positive — CCW on the ground", first > 0f)
        assertTrue("a later exit means more ring: $first < $second < $third",
            first < second && second < third)
    }

    @Test
    fun `the sweep never collapses or wraps past the ring`() {
        assertTrue(drivenSweepDeg(180f) >= 45f)
        assertTrue(drivenSweepDeg(-180f) <= 320f)
    }
}
