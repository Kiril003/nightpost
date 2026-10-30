package local.phantom.companion.core.data.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class TransitDelayMemoryTest {

    @get:Rule val tmp = TemporaryFolder()

    private val zone = ZoneId.of("Europe/Prague")
    private fun at(day: Int, hour: Int, min: Int = 0) =
        ZonedDateTime.of(2026, 9, day, hour, min, 0, 0, zone).toInstant().toEpochMilli()
    private val line = TransitDelayMemory.lineKey("R8", "train", "RegioJet")

    @Test
    fun `nothing is claimed before the line was seen three times`() {
        val m = TransitDelayMemory(null)
        m.observe(line, "t1", at(28, 8), 300, zone)
        m.observe(line, "t2", at(29, 8, 10), 300, zone)
        assertNull(m.typicalDelaySec(line, at(30, 8), zone))
        m.observe(line, "t3", at(30, 8, 20), 300, zone)
        assertEquals(300, m.typicalDelaySec(line, at(1, 8, 40), zone))
    }

    @Test
    fun `one trip seen on many refreshes counts once`() {
        val m = TransitDelayMemory(null)
        repeat(5) { m.observe(line, "same", at(28, 8), 600, zone) }
        assertNull(m.typicalDelaySec(line, at(28, 8), zone))
    }

    @Test
    fun `rush hour and night are different questions`() {
        val m = TransitDelayMemory(null)
        listOf("a", "b", "c").forEach { m.observe(line, it, at(28, 8), 420, zone) }
        assertNull(m.typicalDelaySec(line, at(28, 23), zone))
    }

    @Test
    fun `what was learned survives a restart`() {
        val f = File(tmp.root, "transit/delays.tsv")
        val m = TransitDelayMemory(f)
        listOf("a", "b", "c").forEach { m.observe(line, it, at(28, 8), 240, zone) }
        m.flush()
        assertEquals(240, TransitDelayMemory(f).typicalDelaySec(line, at(29, 8, 30), zone))
    }

    @Test
    fun `an absurd delay is not learned`() {
        val m = TransitDelayMemory(null)
        listOf("a", "b", "c").forEach { m.observe(line, it, at(28, 8), 90_000, zone) }
        assertNull(m.typicalDelaySec(line, at(28, 8), zone))
    }
}
