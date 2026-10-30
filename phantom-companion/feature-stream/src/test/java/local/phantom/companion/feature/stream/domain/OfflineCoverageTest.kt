package local.phantom.companion.feature.stream.domain

import local.phantom.companion.feature.stream.domain.OfflineCoverage.Area
import local.phantom.companion.feature.stream.domain.OfflineCoverage.Stored
import local.phantom.companion.feature.stream.domain.OfflineCoverage.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCoverageTest {

    private val day = "https://tiles.openfreemap.org/styles/liberty"
    private val night = "https://tiles.openfreemap.org/styles/dark"

    /** Kyiv-ish box, and a smaller one inside it. */
    private val big = Area(northLat = 50.55, eastLon = 30.70, southLat = 50.35, westLon = 30.35)
    private val inside = Area(northLat = 50.48, eastLon = 30.55, southLat = 50.44, westLon = 30.48)
    private val elsewhere = Area(northLat = 49.90, eastLon = 24.10, southLat = 49.80, westLon = 23.95)

    @Test
    fun `nothing stored is nothing stored`() {
        assertEquals(Verdict.None, OfflineCoverage.verdict(inside, day, emptyList()))
    }

    @Test
    fun `a pack that swallows the view in the style on screen covers it`() {
        val stored = listOf(Stored(big, day, complete = true))
        assertEquals(Verdict.Covered, OfflineCoverage.verdict(inside, day, stored))
    }

    @Test
    fun `the same pack at night is the wrong style, not missing`() {
        // The trap: a region is downloaded against ONE style URL, and the app
        // flips basemap at dusk. «Не збережено» would send the operator to
        // download an area he already has.
        val stored = listOf(Stored(big, day, complete = true))
        assertEquals(Verdict.WrongStyle, OfflineCoverage.verdict(inside, night, stored))
    }

    @Test
    fun `a pack that only clips the view is partial, not covered`() {
        val clipping = Area(northLat = 50.46, eastLon = 30.60, southLat = 50.30, westLon = 30.50)
        val stored = listOf(Stored(clipping, day, complete = true))
        assertEquals(Verdict.Partial, OfflineCoverage.verdict(inside, day, stored))
    }

    @Test
    fun `a pack somewhere else says nothing about here`() {
        val stored = listOf(Stored(elsewhere, day, complete = true))
        assertEquals(Verdict.None, OfflineCoverage.verdict(inside, day, stored))
    }

    @Test
    fun `a half-downloaded pack does not count as coverage`() {
        // Claiming an unfinished download covers the view is the same lie as
        // claiming an absent one does — the tiles are not there either way.
        val stored = listOf(Stored(big, day, complete = false))
        assertEquals(Verdict.None, OfflineCoverage.verdict(inside, day, stored))
    }

    @Test
    fun `the right style wins over a wrong-style pack covering the same ground`() {
        val stored = listOf(
            Stored(big, night, complete = true),
            Stored(big, day, complete = true),
        )
        assertEquals(Verdict.Covered, OfflineCoverage.verdict(inside, day, stored))
    }

    @Test
    fun `an inside-out viewport is refused rather than answered`() {
        val nonsense = Area(northLat = 50.30, eastLon = 30.40, southLat = 50.50, westLon = 30.60)
        assertEquals(
            Verdict.None,
            OfflineCoverage.verdict(nonsense, day, listOf(Stored(big, day, complete = true))),
        )
    }

    // ── covers() — the point-query form used by the tap-to-interrogate popover ──

    @Test
    fun `a point inside the area is covered`() {
        assertTrue(OfflineCoverage.covers(big, lat = 50.45, lon = 30.50))
    }

    @Test
    fun `a point outside the area is not covered`() {
        assertFalse(OfflineCoverage.covers(big, lat = 49.90, lon = 24.10))
    }

    @Test
    fun `an inside-out area covers nothing, not everything`() {
        val nonsense = Area(northLat = 50.30, eastLon = 30.40, southLat = 50.50, westLon = 30.60)
        assertFalse(OfflineCoverage.covers(nonsense, lat = 50.40, lon = 30.50))
    }

    // ── uncoveredRects() — the honesty hatch's geometry, design-critic §S9 ──

    private fun rectArea(a: Area): Double = (a.northLat - a.southLat) * (a.eastLon - a.westLon)

    @Test
    fun `nothing downloaded leaves the whole viewport uncovered`() {
        assertEquals(listOf(inside), OfflineCoverage.uncoveredRects(inside, emptyList()))
    }

    @Test
    fun `a pack that swallows the viewport leaves nothing uncovered`() {
        assertTrue(OfflineCoverage.uncoveredRects(inside, listOf(big)).isEmpty())
    }

    @Test
    fun `a pack somewhere else leaves the viewport untouched`() {
        assertEquals(listOf(inside), OfflineCoverage.uncoveredRects(inside, listOf(elsewhere)))
    }

    @Test
    fun `a pack that only clips a corner leaves the rest as strips, area-conserving`() {
        // `inside`'s NE corner is clipped by a pack straddling its border.
        val corner = Area(northLat = 50.60, eastLon = 30.60, southLat = 50.46, westLon = 30.50)
        val holes = OfflineCoverage.uncoveredRects(inside, listOf(corner))
        assertTrue("a clipped corner must leave at least one strip", holes.isNotEmpty())
        // No strip claims ground the original viewport didn't have, and none
        // overlaps the covered rectangle — the hatch must never paint over
        // ground that IS downloaded.
        holes.forEach { h ->
            assertTrue(OfflineCoverage.contains(inside, h))
            assertFalse(OfflineCoverage.overlaps(h, corner))
        }
        // Area conservation: uncovered + covered-inside-view == whole view,
        // to a tight epsilon (floating point). Computed directly rather than
        // hand-derived, so the assertion tracks the fixtures.
        val clipN = minOf(inside.northLat, corner.northLat)
        val clipS = maxOf(inside.southLat, corner.southLat)
        val clipE = minOf(inside.eastLon, corner.eastLon)
        val clipW = maxOf(inside.westLon, corner.westLon)
        val intersectionArea = (clipN - clipS) * (clipE - clipW)
        val sumHoles = holes.sumOf { rectArea(it) }
        assertEquals(rectArea(inside), sumHoles + intersectionArea, 1e-9)
    }

    @Test
    fun `a hole in the middle produces four conserving strips`() {
        val view = Area(northLat = 10.0, eastLon = 10.0, southLat = 0.0, westLon = 0.0)
        val hole = Area(northLat = 6.0, eastLon = 6.0, southLat = 4.0, westLon = 4.0)
        val holes = OfflineCoverage.uncoveredRects(view, listOf(hole))
        assertEquals(4, holes.size)
        holes.forEach { h -> assertFalse(OfflineCoverage.overlaps(h, hole)) }
        assertEquals(rectArea(view) - rectArea(hole), holes.sumOf { rectArea(it) }, 1e-9)
    }

    @Test
    fun `two disjoint packs both get cut out`() {
        val view = Area(northLat = 10.0, eastLon = 10.0, southLat = 0.0, westLon = 0.0)
        val left = Area(northLat = 10.0, eastLon = 2.0, southLat = 0.0, westLon = 0.0)
        val right = Area(northLat = 10.0, eastLon = 10.0, southLat = 0.0, westLon = 8.0)
        val holes = OfflineCoverage.uncoveredRects(view, listOf(left, right))
        holes.forEach { h ->
            assertFalse(OfflineCoverage.overlaps(h, left))
            assertFalse(OfflineCoverage.overlaps(h, right))
        }
        assertEquals(
            rectArea(view) - rectArea(left) - rectArea(right),
            holes.sumOf { rectArea(it) },
            1e-9,
        )
    }

    @Test
    fun `an inside-out viewport uncovers nothing rather than everything`() {
        val nonsense = Area(northLat = 50.30, eastLon = 30.40, southLat = 50.50, westLon = 30.60)
        assertTrue(OfflineCoverage.uncoveredRects(nonsense, emptyList()).isEmpty())
    }
}
