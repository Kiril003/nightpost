package local.phantom.companion.feature.stream.ui.map.here

import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKey
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Lang
import local.phantom.companion.core.truth.Place
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.TruthState
import local.phantom.companion.core.truth.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HereNowLineTest {

    private val cell = CellId("c")
    private val uk = HereWords(held = "надішлеться пізніше", sent = "надіслано", unsure = "не підтверджено")
    private val en = HereWords(held = "sends later", sent = "sent", unsure = "unconfirmed")

    private fun verdict(kind: FactKind, state: TruthState, reading: Reading?, newest: Long?) = Verdict(
        key = FactKey(Place(cell), kind),
        state = state,
        reading = reading,
        probability = 0.5,
        mass = 0.0,
        supporters = if (newest == null) 0 else 2,
        lastSupportAtMs = newest,
        newestAtMs = newest,
        dissent = false,
    )

    @Test
    fun `a server answer of no reports for every kind keeps the block away`() {
        val rows = FactKind.values().map { HereRow(it, verdict(it, TruthState.UNKNOWN, null, null), held = false, sent = false) }
        val state = HereNowState(cell, HereReach.ON, rows)
        assertFalse(hereNowShows(state))
        assertTrue(hereReportOffered(state))
    }

    @Test
    fun `one compact line names each fact in plain words`() {
        val rows = listOf(
            HereRow(FactKind.POWER, verdict(FactKind.POWER, TruthState.LIKELY, Reading.ON, 1_000L), held = false, sent = false),
            HereRow(FactKind.WATER, null, held = true, sent = false),
        )
        assertEquals("Світло: є · Вода: надішлеться пізніше", hereFactsLine(rows, Lang.UK, uk))
        assertEquals(1_000L, hereFreshestMs(rows))
    }

    @Test
    fun `an old or unsure verdict never prints a wire word`() {
        val rows = listOf(
            HereRow(FactKind.POWER, verdict(FactKind.POWER, TruthState.STALE, Reading.OFF, 5L), held = false, sent = false),
            HereRow(FactKind.ROAD, null, held = false, sent = true),
        )
        val line = hereFactsLine(rows, Lang.EN, en)
        assertEquals("Power: unconfirmed · Road: sent", line)
        for (k in FactKind.values()) assertFalse(k.wire, line.contains(k.wire))
        assertFalse(line.contains('_'))
    }

    @Test
    fun `only yes-or-no kinds are offered for a report`() {
        val rows = FactKind.values().map { HereRow(it, null, held = false, sent = false) }
        val kinds = hereReportKinds(HereNowState(cell, HereReach.ON, rows))
        assertFalse(FactKind.QUEUE in kinds)
        assertTrue(FactKind.POWER in kinds)
    }

    @Test
    fun `a queue reads as its own words, not twice`() {
        val rows = listOf(
            HereRow(FactKind.QUEUE, verdict(FactKind.QUEUE, TruthState.LIKELY, Reading.QUEUE_SHORT, 9L), held = false, sent = false),
            HereRow(FactKind.QUEUE, verdict(FactKind.QUEUE, TruthState.LIKELY, Reading.QUEUE_SHORT, 9L), held = true, sent = false),
        )
        assertEquals("черга до 15 хв · Черга: надішлеться пізніше", hereFactsLine(rows, Lang.UK, uk))
    }
}
