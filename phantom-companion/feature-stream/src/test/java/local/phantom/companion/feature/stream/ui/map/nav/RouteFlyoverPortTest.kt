package local.phantom.companion.feature.stream.ui.map.nav

import kotlinx.coroutines.test.runTest
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPose
import local.phantom.companion.feature.stream.ui.map.atlas.EdgeInsets
import local.phantom.companion.feature.stream.ui.map.atlas.GeoBounds
import local.phantom.companion.feature.stream.ui.map.atlas.GeoPoint
import local.phantom.companion.feature.stream.ui.map.atlas.MapPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-1 — обліт більше не пише камеру сам, і головне про нього лишилось
 * тим самим: палець ріже політ посеред етапу. Порт віддає `false` на
 * `onCancel` MapLibre, а політ спиняється на першому ж `false`.
 *
 * Раніше це можна було довести тільки на пристрої — тепер обліт не знає про
 * MapLibre, тож правило тримає звичайний JVM-тест.
 */
class RouteFlyoverPortTest {

    private class FakePort(private val cutAtLeg: Int = -1) : MapPort {
        val legs = mutableListOf<CameraPose>()
        var fitted: GeoBounds? = null

        override fun pose(): CameraPose? = null
        override fun move(pose: CameraPose) = Unit
        override fun flyTo(target: GeoPoint, zoom: Double?, durationMs: Int) = Unit
        override fun fit(bounds: GeoBounds, padding: EdgeInsets, durationMs: Int) { fitted = bounds }
        override fun zoomBy(levels: Double, durationMs: Int) = Unit
        override fun bearingTo(degrees: Double, durationMs: Int) = Unit
        override fun tiltTo(degrees: Double, durationMs: Int) = Unit
        override fun flyover(path: List<GeoPoint>, durationMs: Int) = Unit

        override suspend fun animateAwait(pose: CameraPose, durationMs: Int): Boolean {
            legs += pose
            return legs.size != cutAtLeg
        }
    }

    private val corridor = (0..40).map { 50.4000 + it * 0.0010 to 30.5000 + it * 0.0007 }

    @Test
    fun `a full flight walks every anchor and frames the corridor`() = runTest {
        val port = FakePort()
        RouteFlyover.fly(port, corridor)

        // Свуп на старт + по легу на кожен наступний якір.
        assertEquals(9, port.legs.size)
        assertEquals(RouteFlyover.boundsOf(corridor), port.fitted)
    }

    @Test
    fun `a cut leg stops the flight there and nothing frames afterwards`() = runTest {
        val port = FakePort(cutAtLeg = 3)
        RouteFlyover.fly(port, corridor)

        assertEquals(3, port.legs.size)
        assertEquals(null, port.fitted)
    }

    @Test
    fun `a cut swoop never starts the corridor at all`() = runTest {
        val port = FakePort(cutAtLeg = 1)
        RouteFlyover.fly(port, corridor)

        assertEquals(1, port.legs.size)
        assertEquals(null, port.fitted)
    }

    @Test
    fun `a route of one point is not a flight`() = runTest {
        val port = FakePort()
        RouteFlyover.fly(port, listOf(50.45 to 30.52))

        assertTrue(port.legs.isEmpty())
        assertFalse(port.fitted != null)
    }
}
