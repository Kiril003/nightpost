package local.phantom.companion.self

import local.phantom.companion.self.TurnClock.Mark
import local.phantom.companion.self.TurnClock.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Годинник ходу телефона (L0): мітки, процентилі, розбиття за джерелом.
 * Процентиль — найближчий ранг, те саме правило, що й core/turn_clock.py на ПК.
 */
class TurnClockTest {

    private class Fake(var ns: Long = 5_000_000_000L) : () -> Long {
        override fun invoke(): Long = ns
        fun ms(d: Long) { ns += d * 1_000_000L }
    }

    @Test
    fun `percentile is nearest rank, the same rule as the PC bench`() {
        val xs = listOf(100L, 200, 300, 400, 500, 600, 700, 800, 900, 1000)
        assertEquals(500L, TurnClock.percentile(xs, 50))
        assertEquals(1000L, TurnClock.percentile(xs, 95))
        assertEquals(900L, TurnClock.percentile(xs, 90))
        assertEquals(100L, TurnClock.percentile(xs, 0))
        assertEquals(7L, TurnClock.percentile(listOf(7L), 95))
        assertEquals(2000L, TurnClock.percentile(listOf(3000L, 1000, 2000), 50))
        assertNull(TurnClock.percentile(emptyList(), 50))
    }

    @Test
    fun `marks count from bridge_in and the first one wins`() {
        val now = Fake()
        val clock = TurnClock(nowNanos = now)
        clock.begin("t1", label = "greet")
        now.ms(40)
        assertTrue(clock.mark("t1", Mark.FIRST_TOKEN))
        now.ms(500)
        assertFalse("перший токен один", clock.mark("t1", Mark.FIRST_TOKEN))
        assertTrue(clock.mark("t1", Mark.FIRST_VISIBLE))
        now.ms(60)
        clock.finish("t1", ok = true)

        val t = clock.turn("t1")!!
        assertEquals(40L, t.spanMs(Mark.BRIDGE_IN, Mark.FIRST_TOKEN))
        assertEquals(540L, t.spanMs(Mark.BRIDGE_IN, Mark.FIRST_VISIBLE))
        assertEquals(600L, t.spanMs(Mark.BRIDGE_IN, Mark.FINAL))
        assertNull(t.spanMs(Mark.BRIDGE_IN, Mark.DECIDED))
        assertEquals(true, t.ok)
    }

    @Test
    fun `wiring mistakes are refused, not counted as speed`() {
        val now = Fake()
        val clock = TurnClock(nowNanos = now)
        assertFalse(clock.mark("ghost", Mark.FIRST_TOKEN))
        clock.begin("t1")
        assertFalse("раніше за bridge_in", clock.mark("t1", Mark.FIRST_TOKEN, atNs = now.ns - 1))
        assertFalse("bridge_in ставить лише begin", clock.mark("t1", Mark.BRIDGE_IN))
        assertEquals(setOf(Mark.BRIDGE_IN), clock.turn("t1")!!.marksNs.keys)
    }

    @Test
    fun `decide marks the moment and the source once`() {
        val now = Fake()
        val clock = TurnClock(nowNanos = now)
        clock.begin("t1")
        now.ms(12)
        assertTrue(clock.decide("t1", Source.PC))
        now.ms(5)
        assertFalse(clock.decide("t1", Source.CLOUD))
        val t = clock.turn("t1")!!
        assertEquals(Source.PC, t.source)
        assertEquals(12L, t.spanMs(Mark.BRIDGE_IN, Mark.DECIDED))
        // спостерігач може назвати джерело, не вдаючи момент рішення
        clock.begin("t2")
        clock.attribute("t2", Source.REFLEX, basis = "observed")
        assertEquals(Source.REFLEX, clock.turn("t2")!!.source)
        assertEquals("observed", clock.turn("t2")!!.basis)
        assertNull(clock.turn("t2")!!.spanMs(Mark.BRIDGE_IN, Mark.DECIDED))
    }

    @Test
    fun `report splits by source and skips turns still in flight`() {
        val now = Fake()
        val clock = TurnClock(nowNanos = now)
        // три ходи через ПК: 2.9 с, 9.0 с, 17.3 с до кінця; перший токен = кінець (відповідь цілою)
        for ((i, total) in listOf(2900L, 9000L, 17300L).withIndex()) {
            clock.begin("pc$i")
            clock.decide("pc$i", Source.PC)
            now.ms(total)
            clock.mark("pc$i", Mark.FIRST_TOKEN)
            clock.mark("pc$i", Mark.FIRST_VISIBLE)
            clock.finish("pc$i", ok = true)
        }
        // рефлекс: 40 мс
        clock.begin("rx")
        clock.attribute("rx", Source.REFLEX, basis = "observed")
        now.ms(40)
        clock.mark("rx", Mark.FIRST_VISIBLE)
        clock.finish("rx", ok = true)
        // хмара впала на 4.2 с
        clock.begin("cl")
        clock.decide("cl", Source.CLOUD)
        now.ms(4200)
        clock.finish("cl", ok = false, reason = "429")
        // ще в дорозі — у звіт не йде
        clock.begin("busy")
        clock.decide("busy", Source.PC)

        val r = clock.report()
        val pc = r.bySource.getValue(Source.PC)
        assertEquals(3, pc.turns)
        assertEquals(0, pc.failed)
        assertEquals(9000L, pc.spans.getValue(Mark.FINAL).p50Ms)
        assertEquals(17300L, pc.spans.getValue(Mark.FINAL).p95Ms)
        assertEquals(9000L, pc.spans.getValue(Mark.FIRST_VISIBLE).p50Ms)
        assertEquals(40L, r.bySource.getValue(Source.REFLEX).spans.getValue(Mark.FIRST_VISIBLE).p50Ms)
        val cl = r.bySource.getValue(Source.CLOUD)
        assertEquals(1, cl.failed)
        assertEquals(0, cl.spans.getValue(Mark.FIRST_VISIBLE).n)
        assertEquals(5, r.all.turns)
        assertEquals(1, r.all.failed)
        assertFalse(r.bySource.containsKey(Source.OFFLINE))
        val json = r.toJson()
        assertTrue(json, json.contains("\"pc\":{\"turns\":3,\"failed\":0"))
        assertTrue(json, json.contains("\"final\":{\"n\":3,\"p50_ms\":9000,\"p95_ms\":17300}"))
    }

    @Test
    fun `log line names source and spans from bridge_in`() {
        val now = Fake()
        val clock = TurnClock(nowNanos = now)
        clock.begin("t1", label = "greet")
        clock.decide("t1", Source.CLOUD)
        now.ms(1234)
        clock.mark("t1", Mark.FIRST_TOKEN)
        now.ms(766)
        clock.finish("t1", ok = true)
        val line = clock.line("t1")
        assertEquals(
            "turn=t1 label=greet source=cloud basis=decided ok=true reason= decided=0 first_visible=- first_token=1234 final=2000",
            line,
        )
    }

    @Test
    fun `history is bounded`() {
        val clock = TurnClock(nowNanos = Fake(), keep = 3)
        for (i in 0 until 5) clock.begin("t$i")
        assertEquals(listOf("t2", "t3", "t4"), clock.turns().map { it.id })
        clock.clear()
        assertTrue(clock.turns().isEmpty())
    }
}
