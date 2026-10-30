package local.phantom.companion.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteTrimTest {

    // A straight run east along a Kyiv latitude; ~71 m per 0.001° of longitude.
    private val line = listOf(
        50.4500 to 30.5000,
        50.4500 to 30.5010,
        50.4500 to 30.5020,
        50.4500 to 30.5030,
    )

    @Test
    fun `at the start the whole route is still ahead`() {
        val ahead = RouteTrim.remaining(line, 50.4500, 30.5000)
        assertEquals(30.5000, ahead.first().second, 1e-6)
        assertEquals(30.5030, ahead.last().second, 1e-6)
    }

    @Test
    fun `the road behind is dropped`() {
        val ahead = RouteTrim.remaining(line, 50.4500, 30.5020)
        assertEquals("the head sits under the operator", 30.5020, ahead.first().second, 1e-5)
        assertEquals(30.5030, ahead.last().second, 1e-6)
        assertTrue("everything behind is gone", ahead.none { it.second < 30.5019 })
    }

    @Test
    fun `the head sits under the operator, not at the next shape point`() {
        // Between two vertices: a trim that snapped to the next vertex would
        // put the line's head 35 m ahead of the dot and stutter every segment.
        val ahead = RouteTrim.remaining(line, 50.4500, 30.5005)
        assertEquals(30.5005, ahead.first().second, 1e-5)
    }

    @Test
    fun `arriving consumes the line rather than leaving a stub`() {
        val ahead = RouteTrim.remaining(line, 50.4500, 30.5030)
        assertTrue("a one-point line draws nothing and must not be emitted", ahead.isEmpty())
    }

    @Test
    fun `off route the whole line survives`() {
        // ~1.1 km north of the corridor: there is no honest projection, and
        // erasing the road ahead off a bad snap is the one unforgivable trim.
        val ahead = RouteTrim.remaining(line, 50.4600, 30.5015)
        assertEquals(line.size, ahead.size)
        assertEquals(line.first(), ahead.first())
    }

    @Test
    fun `a degenerate line is returned untouched`() {
        assertEquals(emptyList<Pair<Double, Double>>(), RouteTrim.remaining(emptyList(), 50.0, 30.0))
        val single = listOf(50.45 to 30.50)
        assertEquals(single, RouteTrim.remaining(single, 50.45, 30.50))
    }

    @Test
    fun `the two halves meet exactly at the operator`() {
        val ahead = RouteTrim.remaining(line, 50.4500, 30.5014)
        val behind = RouteTrim.travelled(line, 50.4500, 30.5014)
        assertEquals(behind.last().first, ahead.first().first, 1e-9)
        assertEquals(behind.last().second, ahead.first().second, 1e-9)
    }

    @Test
    fun `the halves together are never shorter than the whole`() {
        val at = 30.5017
        val ahead = RouteTrim.remaining(line, 50.4500, at)
        val behind = RouteTrim.travelled(line, 50.4500, at)
        val total = RouteTrim.cumulative(line).last()
        val sum = RouteTrim.cumulative(ahead).last() + RouteTrim.cumulative(behind).last()
        assertEquals(total, sum, 1.0)
    }

    @Test
    fun `walking the route only ever shortens what is left`() {
        var previous = Double.MAX_VALUE
        var lon = 30.5000
        while (lon <= 30.5030) {
            val ahead = RouteTrim.remaining(line, 50.4500, lon)
            val left = if (ahead.size < 2) 0.0 else RouteTrim.cumulative(ahead).last()
            assertTrue("remaining grew at lon=$lon: $left > $previous", left <= previous + 1e-6)
            previous = left
            lon += 0.0002
        }
    }

    @Test
    fun `cumulative distance is monotonic and starts at zero`() {
        val cum = RouteTrim.cumulative(line)
        assertEquals(0.0, cum.first(), 1e-9)
        for (i in 1 until cum.size) assertTrue(cum[i] > cum[i - 1])
    }
}
