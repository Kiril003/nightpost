package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Обхід A52 18.10: на одне «Ти тут?» помічник відповів двічі — «На звʼязку. Чим
 * допомогти?» і «Так, на звʼязку. Чим можу допомогти?». Дві різні відповіді —
 * це два походи до моделі (і два запити в денний ліміт) на одне питання.
 */
class AskGateTest {

    private var now = 1_000L
    private val gate = AskGate(windowMs = 10_000L) { now }

    @Test
    fun `the same question asked again while the first is in flight gets no second answer`() {
        assertNotNull(gate.admit("q1", "Ти тут?"))
        assertNull(gate.admit("q1", "Ти тут?"))
    }

    @Test
    fun `the same words resent under a new id within the window are one question`() {
        val first = gate.admit("q1", "Ти тут?")!!
        now += 3_000
        gate.settle(first, failed = false)
        assertNull(gate.admit("q2", "  ти   тут? "))
    }

    @Test
    fun `after the window the same words are a new question`() {
        gate.settle(gate.admit("q1", "Ти тут?")!!, failed = false)
        now += 10_001
        assertNotNull(gate.admit("q2", "Ти тут?"))
    }

    @Test
    fun `a failed ask can be asked again at once`() {
        gate.settle(gate.admit("q1", "Чому море солоне?")!!, failed = true)
        assertNotNull(gate.admit("q1", "Чому море солоне?", retry = true))
        gate.settle(gate.admit("q9", "Hi")!!, failed = true)
        assertNotNull(gate.admit("q10", "Hi"))
    }

    @Test
    fun `a different question is never held back`() {
        assertNotNull(gate.admit("q1", "Ти тут?"))
        assertNotNull(gate.admit("q2", "Яка погода?"))
    }

    @Test
    fun `a resent web line keeps one question id`() {
        val id = AskGate.questionIdFor("c_1729300000_ab12cd")
        assertEquals(id, AskGate.questionIdFor("c_1729300000_ab12cd"))
        assertTrue(id!!.startsWith("msg_"))
        assertEquals("msg_x1", AskGate.questionIdFor("msg_x1"))
        assertNull(AskGate.questionIdFor(""))
        assertNull(AskGate.questionIdFor("../../etc"))
        assertNull(AskGate.questionIdFor("c".repeat(200)))
    }

    @Test
    fun `the bridge and the dialogue both go through the gate`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь репозиторію не знайдено") as Nothing
        val pkg = "app/src/main/java/local/phantom/companion/ui/messenger"
        val bridge = File(root, "$pkg/WebMessengerBridge.kt").readText()
        val agency = File(root, "$pkg/AgencySessionManager.kt").readText()
        assertTrue("міст не впізнає повторений лист", "AskGate.questionIdFor(" in bridge)
        assertTrue("діалог не питає ворота", "asks.admit(" in agency && "asks.settle(" in agency)
    }
}
