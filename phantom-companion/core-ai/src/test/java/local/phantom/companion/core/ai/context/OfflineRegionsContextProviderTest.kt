package local.phantom.companion.core.ai.context

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MAP-AI-2 — pins the OFFLINE_REGIONS prompt block contract.
 *
 * The formatter is what the AI sees in `enrichedContext`; regressions
 * here ripple into prompt drift, so cover the empty / single / multi /
 * incomplete / size-tier branches explicitly.
 */
class OfflineRegionsContextProviderTest {

    @Test
    fun `formatter emits none when list is empty`() {
        assertEquals(
            "[OFFLINE_REGIONS:none]",
            OfflineRegionsContextFormatter.format(emptyList()),
        )
    }

    @Test
    fun `formatter humanises single MB-sized region`() {
        val region = OfflineRegionSummary(
            name = "Львів-центр",
            sizeBytes = 12L * 1024L * 1024L + 300L * 1024L,
            ready = true,
        )
        assertEquals(
            "[OFFLINE_REGIONS: Львів-центр@12.3MB]",
            OfflineRegionsContextFormatter.format(listOf(region)),
        )
    }

    @Test
    fun `formatter humanises kB-sized region`() {
        val region = OfflineRegionSummary(
            name = "tiny",
            sizeBytes = 768L * 1024L,
            ready = true,
        )
        assertEquals(
            "[OFFLINE_REGIONS: tiny@768kB]",
            OfflineRegionsContextFormatter.format(listOf(region)),
        )
    }

    @Test
    fun `formatter humanises GB-sized region with two decimals`() {
        val region = OfflineRegionSummary(
            name = "Europe-fat",
            sizeBytes = 2L * 1024L * 1024L * 1024L + 100L * 1024L * 1024L,
            ready = true,
        )
        assertEquals(
            "[OFFLINE_REGIONS: Europe-fat@2.10GB]",
            OfflineRegionsContextFormatter.format(listOf(region)),
        )
    }

    @Test
    fun `formatter annotates incomplete region with asterisk`() {
        val region = OfflineRegionSummary(
            name = "downloading",
            sizeBytes = 5L * 1024L * 1024L,
            ready = false,
        )
        assertEquals(
            "[OFFLINE_REGIONS: downloading@5.0MB*]",
            OfflineRegionsContextFormatter.format(listOf(region)),
        )
    }

    @Test
    fun `formatter joins multi-region list with comma separator preserving order`() {
        val regions = listOf(
            OfflineRegionSummary("Kyiv", 8L * 1024L * 1024L, ready = true),
            OfflineRegionSummary("Lviv", 12L * 1024L * 1024L, ready = false),
            OfflineRegionSummary("Odessa", 4L * 1024L * 1024L, ready = true),
        )
        assertEquals(
            "[OFFLINE_REGIONS: Kyiv@8.0MB, Lviv@12.0MB*, Odessa@4.0MB]",
            OfflineRegionsContextFormatter.format(regions),
        )
    }

    @Test
    fun `provider lambda returns current snapshot of supplier`() {
        var snapshot: List<OfflineRegionSummary> = emptyList()
        val provider = OfflineRegionsContextProvider { snapshot }
        assertEquals(emptyList<OfflineRegionSummary>(), provider.current())

        snapshot = listOf(OfflineRegionSummary("now", 1L * 1024L * 1024L, ready = true))
        assertEquals(snapshot, provider.current())
    }

    @Test
    fun `formatter emits bytes label for sub-kB region`() {
        val region = OfflineRegionSummary(
            name = "stub",
            sizeBytes = 500L,
            ready = true,
        )
        assertEquals(
            "[OFFLINE_REGIONS: stub@500B]",
            OfflineRegionsContextFormatter.format(listOf(region)),
        )
    }
}
