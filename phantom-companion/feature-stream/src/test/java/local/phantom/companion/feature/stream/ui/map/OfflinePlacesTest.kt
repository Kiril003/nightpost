package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflinePlacesTest {

    private fun chunk(
        id: Long,
        index: Int,
        count: Int = 3,
        done: Long = 100,
        need: Long = 100,
        bytes: Long = 1_000,
        active: Boolean = false,
    ) = RegionInfo(
        id = id,
        name = "Житомирська область · ${index + 1}/$count",
        completedResourceCount = done,
        requiredResourceCount = need,
        completedResourceSizeBytes = bytes,
        isComplete = need > 0 && done == need,
        downloadState = if (active) 1 else 0,
        active = active,
        placeId = "obl-ua-18",
        placeNameUa = "Житомирська область",
        chunkIndex = index,
        chunkCount = count,
        minZoom = 10,
        maxZoom = 14,
    )

    @Test
    fun `all three chunks finished reads as complete`() {
        val s = OfflinePlaces.group(listOf(chunk(1, 0), chunk(2, 1), chunk(3, 2))).single()
        assertTrue(s.complete)
        assertEquals(3, s.doneChunks)
        assertEquals(100, s.percent)
    }

    @Test
    fun `a chunk that was never created keeps the place incomplete`() {
        // The download of part 3 failed to even be created. Every part that
        // exists is finished — and the place is still not «завантажено».
        val s = OfflinePlaces.group(listOf(chunk(1, 0), chunk(2, 1))).single()
        assertFalse("two of three parts read as complete", s.complete)
        assertEquals(3, s.plannedChunks)
        assertEquals(2, s.presentChunks)
    }

    @Test
    fun `an unfinished chunk keeps the place incomplete`() {
        val parts = listOf(chunk(1, 0), chunk(2, 1), chunk(3, 2, done = 40, active = true))
        val s = OfflinePlaces.group(parts).single()
        assertFalse(s.complete)
        assertTrue(s.downloading)
        assertEquals(80, s.percent)
    }

    @Test
    fun `a paused place is not reported as downloading`() {
        val s = OfflinePlaces.group(listOf(chunk(1, 0), chunk(2, 1, done = 10))).single()
        assertFalse(s.downloading)
        assertFalse(s.complete)
    }

    @Test
    fun `the current-view pack is left alone`() {
        val view = RegionInfo(
            id = 9, name = "регіон · 16.08 12:00",
            completedResourceCount = 10, requiredResourceCount = 10,
            completedResourceSizeBytes = 500, isComplete = true,
            downloadState = 0,
        )
        assertTrue(OfflinePlaces.group(listOf(view)).isEmpty())
    }

    @Test
    fun `the zoom range a place was ordered at survives into the status`() {
        val s = OfflinePlaces.group(listOf(chunk(1, 0), chunk(2, 1), chunk(3, 2))).single()
        assertEquals(10, s.minZoom)
        assertEquals(14, s.maxZoom)
    }
}
