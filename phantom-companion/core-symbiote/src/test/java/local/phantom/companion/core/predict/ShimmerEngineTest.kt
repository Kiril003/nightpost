package local.phantom.companion.core.predict

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShimmerEngineTest {

    private val home6pm = ShimmerEngine.Bucket(
        state = "FOCUS", hour = 18, location = "home", lastAction = null,
    )

    @Test
    fun `no observations yields no suggestion`() {
        val e = ShimmerEngine()
        assertNull(e.suggest(home6pm))
    }

    @Test
    fun `repeated observation suggests`() {
        val e = ShimmerEngine()
        repeat(5) { e.observe(home6pm, "lights_on_living") }
        val s = e.suggest(home6pm)
        assertNotNull(s)
        assertEquals("lights_on_living", s!!.action)
        assertEquals(1.0, s.probability, 0.001)
    }

    @Test
    fun `noisy bucket stays under shimmer threshold`() {
        val e = ShimmerEngine(shimmerThreshold = 0.5)
        e.observe(home6pm, "lights_on_living")
        e.observe(home6pm, "open_email")
        e.observe(home6pm, "play_music")
        assertNull(e.suggest(home6pm))
    }

    @Test
    fun `probability is hit count over total`() {
        val e = ShimmerEngine()
        e.observe(home6pm, "lights_on_living")
        e.observe(home6pm, "lights_on_living")
        e.observe(home6pm, "open_email")
        assertEquals(2.0 / 3.0, e.probability(home6pm, "lights_on_living"), 0.001)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bad hour rejected`() {
        ShimmerEngine.Bucket("F", 25, "home", null)
    }
}
