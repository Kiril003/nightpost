package local.phantom.companion.core.data.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GtfsFeedRegistryTest {

    @Test
    fun `Lviv centre resolves the Lviv feed`() {
        val feed = GtfsFeedRegistry.feedFor(49.84, 24.03)
        assertEquals("Львів", feed?.city)
    }

    @Test
    fun `Lviv suburb inside radius still resolves`() {
        // Vynnyky, ~8 km east of the centre.
        val feed = GtfsFeedRegistry.feedFor(49.815, 24.13)
        assertEquals("Львів", feed?.city)
    }

    @Test
    fun `Kyiv has no verified feed - honest null`() {
        assertNull(GtfsFeedRegistry.feedFor(50.45, 30.52))
    }

    @Test
    fun `Zhytomyr oblast has no feed - honest null`() {
        assertNull(GtfsFeedRegistry.feedFor(50.25, 28.66))
    }
}
