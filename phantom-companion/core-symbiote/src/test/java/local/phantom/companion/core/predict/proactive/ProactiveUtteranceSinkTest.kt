package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveUtteranceSinkTest {

    private class Rec {
        val spoken = mutableListOf<String>()
        val whispered = mutableListOf<String>()
        val carded = mutableListOf<String>()
        fun sink() = ProactiveUtteranceSink(
            speak = { spoken += it },
            card = { u, _ -> carded += u.say },
            whisper = { whispered += it },
        )
    }

    private val frame = ProactiveFrame(epochMs = 0L, signals = emptyMap(), salienceHint = 50)

    @Test
    fun notable_isSpoken_andCarded_notWhispered() = runTest {
        val r = Rec()
        r.sink().emit(ProactiveUtterance("Затор попереду", urgency = Urgency.NOTABLE), frame)
        assertEquals(listOf("Затор попереду"), r.spoken)
        assertEquals(listOf("Затор попереду"), r.carded)
        assertTrue(r.whispered.isEmpty())
    }

    @Test
    fun critical_isSpoken_andCarded() = runTest {
        val r = Rec()
        r.sink().emit(ProactiveUtterance("GPS підмінено", urgency = Urgency.CRITICAL), frame)
        assertEquals(listOf("GPS підмінено"), r.spoken)
        assertEquals(listOf("GPS підмінено"), r.carded)
        assertTrue(r.whispered.isEmpty())
    }

    @Test
    fun ambient_isWhispered_andCarded_notSpoken() = runTest {
        val r = Rec()
        r.sink().emit(ProactiveUtterance("Поруч є укриття", urgency = Urgency.AMBIENT), frame)
        assertTrue(r.spoken.isEmpty())
        assertEquals(listOf("Поруч є укриття"), r.whispered)
        assertEquals(listOf("Поруч є укриття"), r.carded)
    }

    @Test
    fun cardIsAlwaysEmitted() = runTest {
        val r = Rec()
        val s = r.sink()
        s.emit(ProactiveUtterance("a", urgency = Urgency.AMBIENT), frame)
        s.emit(ProactiveUtterance("b", urgency = Urgency.NOTABLE), frame)
        s.emit(ProactiveUtterance("c", urgency = Urgency.CRITICAL), frame)
        assertEquals(listOf("a", "b", "c"), r.carded)
    }
}
