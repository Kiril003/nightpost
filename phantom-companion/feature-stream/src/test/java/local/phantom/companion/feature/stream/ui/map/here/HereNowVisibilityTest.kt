package local.phantom.companion.feature.stream.ui.map.here

import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKey
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Place
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.TruthState
import local.phantom.companion.core.truth.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HereNowVisibilityTest {

    private val cell = CellId("c")
    private val silent = listOf(
        HereRow(FactKind.PHARMACY, verdict = null, held = false, sent = false),
        HereRow(FactKind.POWER, verdict = null, held = false, sent = false),
    )
    private val said = Verdict(
        key = FactKey(Place(cell), FactKind.POWER),
        state = TruthState.LIKELY,
        reading = Reading.ON,
        probability = 0.9,
        mass = 3.0,
        supporters = 3,
        lastSupportAtMs = 1L,
        newestAtMs = 1L,
        dissent = false,
    )

    @Test
    fun `no card while loading, unreachable, off or when nobody has said anything`() {
        for (reach in HereReach.values()) {
            assertFalse(reach.name, hereNowShows(HereNowState(cell, reach, silent)))
        }
        assertFalse(hereNowShows(HereNowState(cell, HereReach.ON, emptyList())))
    }

    @Test
    fun `a verdict from people shows the card with that row only`() {
        val rows = silent + HereRow(FactKind.WATER, verdict = said, held = false, sent = false)
        val state = HereNowState(cell, HereReach.ON, rows)
        assertTrue(hereNowShows(state))
        assertEquals(listOf(FactKind.WATER), hereNowRows(state).map { it.kind })
    }

    @Test
    fun `my own held or sent report shows the card`() {
        val held = HereNowState(cell, HereReach.ON, silent + HereRow(FactKind.ROAD, null, held = true, sent = false))
        val sent = HereNowState(cell, HereReach.ON, silent + HereRow(FactKind.ROAD, null, held = false, sent = true))
        assertTrue(hereNowShows(held))
        assertTrue(hereNowShows(sent))
        assertFalse(hereNowShows(sent.copy(reach = HereReach.UNREACHABLE)))
    }

    @Test
    fun `reporting stays offered as a quiet row when the card is hidden`() {
        val state = HereNowState(cell, HereReach.ON, silent)
        assertFalse(hereNowShows(state))
        assertTrue(hereReportOffered(state))
        assertFalse(hereReportOffered(state.copy(reach = HereReach.LOADING)))
    }
}
