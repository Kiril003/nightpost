package local.phantom.companion.core.predict.proactive

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProactiveLlmReasonerTest {

    private class FakeLlm(private val reply: String) {
        var lastPrompt: String? = null
        val generate: suspend (String) -> String = { prompt ->
            lastPrompt = prompt
            reply
        }
    }

    private fun frame(signals: Map<String, String>) =
        ProactiveFrame(epochMs = 1L, signals = signals, salienceHint = 50)

    private suspend fun judgeWith(reply: String): ProactiveUtterance? =
        ProactiveLlmReasoner(FakeLlm(reply).generate).judge(frame(mapOf("k" to "v")), "", emptyList())

    // (a) SILENT in its various shapes → null.
    @Test
    fun silent_variants_returnNull() = runTest {
        assertNull(judgeWith("SILENT"))
        assertNull(judgeWith("  silent  "))
        assertNull(judgeWith("\"SILENT\""))
    }

    // (b) blank reply → null.
    @Test
    fun blankReply_returnsNull() = runTest {
        assertNull(judgeWith("   "))
        assertNull(judgeWith(""))
    }

    // (c) garbage non-JSON → null (fail-safe).
    @Test
    fun garbageReply_returnsNull() = runTest {
        assertNull(judgeWith("what even is this, no json here"))
        assertNull(judgeWith("{not valid json at all"))
        assertNull(judgeWith("{\"say\": }"))
    }

    // (d) valid CRITICAL JSON, no tool.
    @Test
    fun validCriticalJson_parsed() = runTest {
        val u = judgeWith("""{"say":"GPS підмінено — не довіряй позиції","urgency":"CRITICAL"}""")
        assertNotNull(u)
        assertEquals("GPS підмінено — не довіряй позиції", u!!.say)
        assertEquals(Urgency.CRITICAL, u.urgency)
        assertNull(u.toolCall)
    }

    // (e) tool field parsed; missing urgency → NOTABLE; empty say → null.
    @Test
    fun toolAndDefaults_andEmptySay() = runTest {
        val withTool = judgeWith("""{"say":"Пін на мапі","tool":"map.pin","urgency":"AMBIENT"}""")
        assertEquals("map.pin", withTool!!.toolCall)
        assertEquals(Urgency.AMBIENT, withTool.urgency)

        val noUrgency = judgeWith("""{"say":"Затор попереду"}""")
        assertEquals(Urgency.NOTABLE, noUrgency!!.urgency)

        assertNull(judgeWith("""{"say":"","urgency":"CRITICAL"}"""))
    }

    // (f) the prompt carries signals, recentlySaid and recentDialogue.
    @Test
    fun promptContainsContext() = runTest {
        val fake = FakeLlm("SILENT")
        val reasoner = ProactiveLlmReasoner(fake.generate)
        val signals = mapOf("gnss_trust" to "SPOOFED", "route" to "12 min, jam ahead")
        val recentlySaid = listOf("Раніше вже казав про затор", "І про батарею")
        val recentDialogue = "Оператор: де ми зараз?"

        reasoner.judge(
            ProactiveFrame(epochMs = 0L, signals = signals, salienceHint = 80),
            recentDialogue,
            recentlySaid,
        )

        val prompt = fake.lastPrompt!!
        assertTrue(prompt.contains("SPOOFED"))
        assertTrue(prompt.contains("12 min, jam ahead"))
        assertTrue(prompt.contains("Раніше вже казав про затор"))
        assertTrue(prompt.contains("І про батарею"))
        assertTrue(prompt.contains("Оператор: де ми зараз?"))
    }
}
