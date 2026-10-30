package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveEngineTest {

    private class FakeReasoner(
        private val responder: (ProactiveFrame) -> ProactiveUtterance?
    ) : ProactiveReasoner {
        val calls = mutableListOf<ProactiveFrame>()
        override suspend fun judge(
            frame: ProactiveFrame,
            recentDialogue: String,
            recentlySaid: List<String>
        ): ProactiveUtterance? {
            calls.add(frame)
            return responder(frame)
        }
    }

    private class FakeSink : UtteranceSink {
        val emitted = mutableListOf<ProactiveUtterance>()
        override suspend fun emit(utterance: ProactiveUtterance, frame: ProactiveFrame) {
            emitted.add(utterance)
        }
    }

    private fun frame(salience: Int, signals: Map<String, String>, epoch: Long = 0L) =
        ProactiveFrame(epochMs = epoch, signals = signals, salienceHint = salience)

    // (a) below the salience floor → the model is never woken.
    @Test
    fun lowSalience_reasonerNeverCalled() = runTest {
        val reasoner = FakeReasoner { ProactiveUtterance("x", urgency = Urgency.AMBIENT) }
        val sink = FakeSink()
        val engine = ProactiveEngine(
            frames = flowOf(frame(5, mapOf("k" to "a"))),
            reasoner = reasoner, sink = sink, clock = { 0L }, salienceFloor = 15
        )
        engine.run()
        assertTrue(reasoner.calls.isEmpty())
        assertTrue(sink.emitted.isEmpty())
    }

    // (b) a salient frame → judged once → utterance reaches the sink.
    @Test
    fun salientFrame_utteranceReachesSink() = runTest {
        val reasoner = FakeReasoner {
            ProactiveUtterance("Spoof detected", toolCall = "map.pin", urgency = Urgency.CRITICAL)
        }
        val sink = FakeSink()
        val engine = ProactiveEngine(
            frames = flowOf(frame(80, mapOf("gnss_trust" to "SPOOFED"))),
            reasoner = reasoner, sink = sink, clock = { 0L }, salienceFloor = 15
        )
        engine.run()
        assertEquals(1, reasoner.calls.size)
        assertEquals(1, sink.emitted.size)
        assertEquals("Spoof detected", sink.emitted.first().say)
        assertEquals(listOf("Spoof detected"), engine.recentlySaid)
    }

    // (c) two salient frames inside minGap → only ONE delivery (pacing), driven by the injected clock.
    @Test
    fun gapGuard_deliversOnlyOnceWithinMinGap() = runTest {
        var now = 0L
        val reasoner = FakeReasoner { ProactiveUtterance("msg-${it.signals["k"]}", urgency = Urgency.NOTABLE) }
        val sink = FakeSink()
        val frames = flow {
            emit(frame(50, mapOf("k" to "a")))
            now = 5_000L // still inside minGap of 20_000
            emit(frame(50, mapOf("k" to "b")))
        }
        val engine = ProactiveEngine(
            frames = frames, reasoner = reasoner, sink = sink,
            clock = { now }, minGapMs = 20_000L, salienceFloor = 15
        )
        engine.run()
        assertEquals(1, sink.emitted.size)
    }

    // (d) same say returned twice across the gap → delivered once (anti-repetition).
    @Test
    fun antiRepetition_sameSayDeliveredOnce() = runTest {
        var now = 0L
        val reasoner = FakeReasoner { ProactiveUtterance("Heads up", urgency = Urgency.NOTABLE) }
        val sink = FakeSink()
        val frames = flow {
            emit(frame(50, mapOf("k" to "a")))
            now = 25_000L // beyond minGap, so the gap guard does NOT block the second
            emit(frame(50, mapOf("k" to "b")))
        }
        val engine = ProactiveEngine(
            frames = frames, reasoner = reasoner, sink = sink,
            clock = { now }, minGapMs = 20_000L, salienceFloor = 15
        )
        engine.run()
        assertEquals(2, reasoner.calls.size) // both asked
        assertEquals(1, sink.emitted.size)   // only one spoken
    }

    // (e) the model chooses silence → nothing delivered.
    @Test
    fun silentReasoner_nothingDelivered() = runTest {
        val reasoner = FakeReasoner { null }
        val sink = FakeSink()
        val engine = ProactiveEngine(
            frames = flowOf(frame(80, mapOf("k" to "a"))),
            reasoner = reasoner, sink = sink, clock = { 0L }, salienceFloor = 15
        )
        engine.run()
        assertEquals(1, reasoner.calls.size)
        assertTrue(sink.emitted.isEmpty())
        assertTrue(engine.recentlySaid.isEmpty())
    }

    // (f) an unchanged-signals frame repeated → the model is not re-invoked.
    @Test
    fun unchangedSignals_reasonerNotReInvoked() = runTest {
        var now = 0L
        val reasoner = FakeReasoner { ProactiveUtterance("something", urgency = Urgency.AMBIENT) }
        val sink = FakeSink()
        val frames = flow {
            emit(frame(50, mapOf("route" to "clear")))
            now = 25_000L // beyond the gap, so only the unchanged-signals gate can skip
            emit(frame(50, mapOf("route" to "clear")))
        }
        val engine = ProactiveEngine(
            frames = frames, reasoner = reasoner, sink = sink,
            clock = { now }, minGapMs = 20_000L, salienceFloor = 15
        )
        engine.run()
        assertEquals(1, reasoner.calls.size)
    }
}
