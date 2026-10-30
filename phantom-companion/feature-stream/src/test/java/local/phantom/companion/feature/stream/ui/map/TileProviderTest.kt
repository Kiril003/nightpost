package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MAP-1 — pin the provider-resolution + fallback contract that the
 * Map surface relies on. The operator's first-run experience must
 * always show *some* map even with no API keys configured, so the
 * fallback-to-OpenFreeMap behaviour is load-bearing and worth a test.
 */
class TileProviderTest {

    @Test
    fun `OpenFreeMap urls work without an api key`() {
        val urls = resolveStyleUrls(TileProviderId.OPENFREEMAP, apiKey = null, customUrl = null)
        assertTrue(urls.light.startsWith("https://tiles.openfreemap.org/"))
        assertTrue(urls.dark.startsWith("https://tiles.openfreemap.org/"))
        assertFalse("light style url leaked an api_key", urls.light.contains("api_key"))
        assertFalse("dark style url leaked an api_key", urls.dark.contains("api_key"))
    }

    @Test
    fun `MapTiler with valid key substitutes into the style url`() {
        val urls = resolveStyleUrls(TileProviderId.MAPTILER, apiKey = "abc123", customUrl = null)
        assertTrue(urls.light.contains("api.maptiler.com"))
        assertTrue(urls.light.contains("key=abc123"))
        assertTrue(urls.dark.contains("dark"))
    }

    @Test
    fun `MapTiler without key falls back to OpenFreeMap`() {
        val urls = resolveStyleUrls(TileProviderId.MAPTILER, apiKey = null, customUrl = null)
        assertTrue("must fall back to a working style", urls.light.contains("openfreemap"))
    }

    @Test
    fun `Stadia without key falls back to OpenFreeMap`() {
        val urls = resolveStyleUrls(TileProviderId.STADIA, apiKey = "  ", customUrl = null)
        assertTrue(urls.light.contains("openfreemap"))
    }

    @Test
    fun `Custom URL with a value is used for both light and dark`() {
        val urls = resolveStyleUrls(
            TileProviderId.CUSTOM,
            apiKey = null,
            customUrl = "https://example.com/style.json",
        )
        assertEquals("https://example.com/style.json", urls.light)
        assertEquals("https://example.com/style.json", urls.dark)
    }

    @Test
    fun `Custom URL blank falls back to OpenFreeMap`() {
        val urls = resolveStyleUrls(TileProviderId.CUSTOM, apiKey = null, customUrl = "")
        assertTrue(urls.light.contains("openfreemap"))
    }

    @Test
    fun `requires-key flags match the provider catalogue`() {
        assertFalse(providerRequiresKey(TileProviderId.OPENFREEMAP))
        assertFalse(providerRequiresKey(TileProviderId.CUSTOM))
        assertTrue(providerRequiresKey(TileProviderId.MAPTILER))
        assertTrue(providerRequiresKey(TileProviderId.STADIA))
    }

    @Test
    fun `light and dark urls differ for providers that ship a real dark style`() {
        val mt = mapTilerUrls("k")
        assertNotEquals(mt.light, mt.dark)
        val st = stadiaUrls("k")
        assertNotEquals(st.light, st.dark)
    }

    @Test
    fun `every provider carries its own display-name resource`() {
        val ids = TileProviderId.values().map { it.displayNameRes }
        assertEquals("two providers share a display-name resource", ids.size, ids.distinct().size)
        ids.forEach { res -> assertNotEquals(0, res) }
    }
}
