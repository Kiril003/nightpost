package local.phantom.companion.core.sensor.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Postures are built by stating where each DEVICE axis points in the world
 * (East, North, Up) — the rotation matrix's columns are exactly that, so the
 * fixtures read as the physical thing they describe.
 */
class CompassAzimuthTest {

    private fun matrix(
        x: Triple<Float, Float, Float>,
        y: Triple<Float, Float, Float>,
        z: Triple<Float, Float, Float>,
    ) = floatArrayOf(
        x.first, y.first, z.first,
        x.second, y.second, z.second,
        x.third, y.third, z.third,
    )

    private val east = Triple(1f, 0f, 0f)
    private val west = Triple(-1f, 0f, 0f)
    private val north = Triple(0f, 1f, 0f)
    private val south = Triple(0f, -1f, 0f)
    private val up = Triple(0f, 0f, 1f)

    private fun assertDeg(expected: Float, actual: Float?) {
        assertEquals(expected.toDouble(), (actual ?: -1f).toDouble(), 0.5)
    }

    @Test
    fun `flat on its back, top edge north, reads north`() {
        val r = matrix(x = east, y = north, z = up)
        assertDeg(0f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
    }

    @Test
    fun `flat on its back, top edge east, reads east`() {
        val r = matrix(x = south, y = east, z = up)
        assertDeg(90f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
    }

    @Test
    fun `held upright facing north reads north — the posture that used to lie`() {
        // Screen to the operator's face, so the rear camera looks north. The
        // device's +Y is straight at the sky, which is precisely where
        // getOrientation's yaw stops meaning anything: this case returned a
        // heading perpendicular to travel on the street.
        val r = matrix(x = east, y = up, z = south)
        assertDeg(0f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
    }

    @Test
    fun `held upright facing east reads east`() {
        val r = matrix(x = south, y = up, z = west)
        assertDeg(90f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
    }

    @Test
    fun `walking east while reading the screen is never reported as north`() {
        // The literal field report: chevron north, travel east.
        val r = matrix(x = south, y = up, z = west)
        val deg = CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0)!!
        assertEquals(90.0, deg.toDouble(), 0.5)
    }

    @Test
    fun `the same posture reads differently in landscape, because it is`() {
        // Flat, top edge east. Portrait: the operator reads along the top
        // edge, so east. Landscape-90: they read across it, so north.
        val r = matrix(x = south, y = east, z = up)
        assertDeg(90f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
        assertDeg(0f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_90))
        assertDeg(270f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_180))
        assertDeg(180f, CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_270))
    }

    @Test
    fun `a degenerate matrix reports no heading instead of guessing one`() {
        assertNull(CompassAzimuth.fromRotationMatrix(FloatArray(9), CompassAzimuth.ROTATION_0))
        assertNull(CompassAzimuth.fromRotationMatrix(FloatArray(4), CompassAzimuth.ROTATION_0))
    }

    @Test
    fun `every posture on the horizon round-trips to its own bearing`() {
        // Flat phone swept through the full circle: the reported bearing must
        // equal the direction the top edge actually points, everywhere.
        for (deg in 0 until 360 step 15) {
            val rad = Math.toRadians(deg.toDouble())
            val topE = kotlin.math.sin(rad).toFloat()
            val topN = kotlin.math.cos(rad).toFloat()
            // Right edge is 90° clockwise from the top edge.
            val rightE = kotlin.math.sin(rad + Math.PI / 2).toFloat()
            val rightN = kotlin.math.cos(rad + Math.PI / 2).toFloat()
            val r = matrix(
                x = Triple(rightE, rightN, 0f),
                y = Triple(topE, topN, 0f),
                z = up,
            )
            assertDeg(deg.toFloat(), CompassAzimuth.fromRotationMatrix(r, CompassAzimuth.ROTATION_0))
        }
    }

    @Test
    fun `the short way round is the way taken`() {
        assertEquals(20.0, CompassAzimuth.deltaDeg(350f, 10f).toDouble(), 1e-3)
        assertEquals(-20.0, CompassAzimuth.deltaDeg(10f, 350f).toDouble(), 1e-3)
        assertEquals(0.0, CompassAzimuth.deltaDeg(90f, 90f).toDouble(), 1e-3)
    }

    @Test
    fun `smoothing crosses north without spinning the long way`() {
        // 350° → 10° is a 20° turn, not a 340° one; a plain lerp would drag
        // the chevron backwards through the whole compass.
        val next = CompassAzimuth.smooth(previous = 350f, observed = 10f, alpha = 0.5f)
        assertEquals(0.0, next.toDouble(), 1e-3)
    }

    @Test
    fun `the first sample is adopted whole`() {
        assertEquals(42.0, CompassAzimuth.smooth(null, 42f).toDouble(), 1e-3)
    }

    @Test
    fun `smoothing converges on a held reading`() {
        var v: Float? = null
        repeat(40) { v = CompassAzimuth.smooth(v, 120f) }
        assertEquals(120.0, v!!.toDouble(), 0.5)
    }

    @Test
    fun `every output is a legal bearing`() {
        assertEquals(0.0, CompassAzimuth.normalizeDeg(360f).toDouble(), 1e-3)
        assertEquals(350.0, CompassAzimuth.normalizeDeg(-10f).toDouble(), 1e-3)
        assertEquals(1.0, CompassAzimuth.normalizeDeg(721f).toDouble(), 1e-3)
    }
}
