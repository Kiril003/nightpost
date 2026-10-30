package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host tells the app which part of the surface its own chrome is not
 * covering, and the app used to receive both rectangles and throw them away.
 *
 * On a head unit with the routing card down one side that put the driver's
 * triangle underneath the card, and promised a span of road over pixels the
 * host was painting over.
 */
class CarSurfaceAreaTest {

    private val lat = 50.4501
    private val lon = 30.5234

    private fun cam(
        visible: CarMapProjection.Area? = null,
        stable: CarMapProjection.Area? = null,
    ) = CarMapProjection.Camera(
        lat = lat,
        lon = lon,
        bearingDeg = 0.0,
        widthPx = 1920,
        heightPx = 1080,
        spanM = 600.0,
        visible = visible,
        stable = stable,
    )

    @Test
    fun `with no rectangle reported the whole surface is used`() {
        val at = CarMapProjection.anchor(cam())
        assertEquals(960f, at.x, 0.5f)
        assertEquals(1080f * CarMapProjection.PUCK_Y_FRACTION, at.y, 0.5f)
    }

    @Test
    fun `a routing card down the left moves the driver out from under it`() {
        val at = CarMapProjection.anchor(
            cam(visible = CarMapProjection.Area(640, 0, 1920, 1080)),
        )
        assertEquals("centred on what is visible, not on the surface", 1280f, at.x, 0.5f)
    }

    @Test
    fun `chrome across the top shortens the road, it does not stretch it`() {
        val inset = cam(visible = CarMapProjection.Area(0, 300, 1920, 1080))
        assertTrue(
            "600 m must not be drawn across pixels the host is covering",
            CarMapProjection.pixelsPerMetre(inset) < CarMapProjection.pixelsPerMetre(cam()),
        )
        val edge = lat + 600.0 / 111_320.0
        assertEquals(
            "the far end of the span lands at the top of the visible area",
            300f,
            CarMapProjection.project(inset, edge, lon).y,
            2f,
        )
    }

    @Test
    fun `the puck is pulled inside the area the host promises never to cover`() {
        val at = CarMapProjection.anchor(
            cam(
                visible = CarMapProjection.Area(0, 0, 1920, 1080),
                stable = CarMapProjection.Area(0, 0, 1920, 700),
            ),
        )
        assertTrue("was at 842 px, under the host's own bar", at.y <= 700f)
    }

    @Test
    fun `the route and the puck cannot come apart`() {
        // Everything is projected around the same anchor, so moving it for the
        // stable area moves the line with it. A triangle sitting off its own
        // route is worse on a car screen than no map at all.
        val c = cam(
            visible = CarMapProjection.Area(0, 0, 1920, 1080),
            stable = CarMapProjection.Area(0, 0, 1920, 700),
        )
        val here = CarMapProjection.project(c, lat, lon)
        val at = CarMapProjection.anchor(c)
        assertEquals(at.x, here.x, 0.5f)
        assertEquals(at.y, here.y, 0.5f)
    }

    @Test
    fun `an empty rectangle on the first layout pass falls back to the constant`() {
        // The moment the driver plugs in, before the host's first layout, both
        // rectangles can legitimately arrive as an empty Rect — which is
        // exactly 0,0,0,0. That must be the old behaviour, not an undefined
        // position.
        val empty = CarMapProjection.Area(0, 0, 0, 0)
        val at = CarMapProjection.anchor(cam(visible = empty, stable = empty))
        assertEquals(960f, at.x, 0.5f)
        assertEquals(1080f * CarMapProjection.PUCK_Y_FRACTION, at.y, 0.5f)
    }

    @Test
    fun `a degenerate rectangle cannot shrink the map to a sliver`() {
        val silly = cam(visible = CarMapProjection.Area(900, 500, 910, 510))
        assertEquals(960f, CarMapProjection.anchor(silly).x, 0.5f)
        assertTrue(CarMapProjection.pixelsPerMetre(silly) > 0.0)
    }

    @Test
    fun `a rectangle reported outside the surface is clipped, not obeyed`() {
        val at = CarMapProjection.anchor(
            cam(visible = CarMapProjection.Area(-500, -500, 9000, 9000)),
        )
        assertEquals(960f, at.x, 0.5f)
        assertEquals(1080f * CarMapProjection.PUCK_Y_FRACTION, at.y, 0.5f)
    }

    @Test
    fun `a stable area reported off the surface cannot push the puck off screen`() {
        val at = CarMapProjection.anchor(
            cam(stable = CarMapProjection.Area(0, 2000, 1920, 3000)),
        )
        assertTrue("the driver must stay on the display", at.y < 1080f)
        assertEquals(1080f * CarMapProjection.PUCK_Y_FRACTION, at.y, 0.5f)
    }

    @Test
    fun `a stable area too small to hold the puck is ignored rather than obeyed`() {
        val at = CarMapProjection.anchor(cam(stable = CarMapProjection.Area(0, 0, 1920, 40)))
        assertEquals(1080f * CarMapProjection.PUCK_Y_FRACTION, at.y, 0.5f)
    }

    @Test
    fun `clamping never leaves the driver with no road ahead`() {
        // A stable area pinned to the very top would put the puck at the top of
        // the view with the whole span behind it. Silence about the stable area
        // beats a map with no distance left in it.
        val at = CarMapProjection.anchor(
            cam(
                visible = CarMapProjection.Area(0, 0, 1920, 1080),
                stable = CarMapProjection.Area(0, 0, 1920, 70),
            ),
        )
        assertTrue("must keep road above the puck", at.y > 100f)
    }
}
