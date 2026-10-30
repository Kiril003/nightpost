package local.phantom.companion.self

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.memory.MemoryFactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CSharpTutorTest {

    private var clock = 10_000L
    private val state = FakeTutorState()
    private val reminders = FakeReminders()
    private val memory = SelfMemory(MemoryFactStore(FakeFactDao())) { clock }
    private val tutor = CSharpTutor(
        state = state,
        memory = memory,
        skillText = { t, ok -> "${t.id}:${if (ok) "ok" else "shaky"}" },
        reminders = reminders,
        now = { clock },
    )

    private fun lines(said: List<Say>?) = said!!.map { it.line }

    @Test
    fun `two questions, then a task on what wobbled`() = runBlocking {
        assertEquals(listOf(Line.TUTOR_HELLO, Line.TUTOR_Q_VARS), lines(tutor.hear("Вчи мене C#")))
        assertEquals(listOf(Line.TUTOR_RIGHT, Line.TUTOR_Q_LOOPS), lines(tutor.hear("16")))
        val afterQ2 = tutor.hear("9")!!
        assertEquals(Line.TUTOR_WRONG, afterQ2.first().line)
        assertTrue(afterQ2.any { it.line == Line.TASK_LOOPS })
        assertEquals(CSharpTutor.Step.TASK, state.step)

        val known = memory.known().map { it.text }.toSet()
        assertEquals(setOf("vars:ok", "loops:shaky"), known)
        val (say, at) = reminders.live.values.single()
        assertEquals(Line.TUTOR_REMINDER, say.line)
        assertEquals(clock + CSharpTutor.DAY_MS, at)
    }

    @Test
    fun `code is read, faults named, and good code clears the reminder`() = runBlocking {
        tutor.hear("навчи мене с#")
        tutor.hear("16")
        tutor.hear("9")

        val bad = tutor.hear("for (int i = 1; i <= 5; i++) {\n Console.WriteLine(i)\n")!!
        assertTrue(bad.any { it.line == Line.ISSUE_BRACES })
        assertTrue(bad.any { it.line == Line.ISSUE_SEMICOLON })
        assertTrue(bad.any { it.line == Line.TUTOR_READ_NOT_RUN })
        assertEquals(1, reminders.live.size)

        val good = tutor.hear("for (int i = 1; i <= 5; i++)\n{\n    Console.WriteLine(i);\n}")!!
        assertEquals(Line.TUTOR_PASS, good.first().line)
        assertTrue(reminders.live.isEmpty())
        assertTrue(memory.known().any { it.text == "loops:ok" })
        assertEquals(CSharpTutor.Step.IDLE, state.step)
    }

    @Test
    fun `a day later the shaky topic comes back as a question`() = runBlocking {
        tutor.hear("Вчи мене C#")
        tutor.hear("не знаю")
        tutor.hear("10")
        tutor.hear("стоп")
        clock += CSharpTutor.DAY_MS

        val back = tutor.hear("повторимо")!!
        assertEquals(Line.TUTOR_REVIEW, back.single().line)
        assertEquals(Line.TOPIC_VARS, back.single().args.first())
        assertEquals(Line.TUTOR_REVIEW_RIGHT, tutor.hear("3")!!.first().line)
        assertTrue(memory.known().any { it.text == "vars:ok" })
        assertTrue(reminders.live.isEmpty())
    }

    @Test
    fun `talk that is not for the tutor passes by`() = runBlocking {
        assertNull(tutor.hear("яка сьогодні погода"))
        assertNull(tutor.hear("повторимо"))
        tutor.hear("Вчи мене C#")
        tutor.hear("16")
        tutor.hear("10")
        assertNull(tutor.hear("а котра година?"))
        assertEquals(CSharpTutor.Step.IDLE, state.step)
    }

    @Test
    fun `answers are matched as answers, not as digits somewhere`() {
        assertTrue(CSharpCourse.answered("16", "виведе 16"))
        assertTrue(CSharpCourse.answered("012", "0 1 2"))
        assertTrue(!CSharpCourse.answered("16", "116"))
        assertTrue(CSharpCourse.check(CSharpCourse.METHODS, "static int Square(int x)\n{\n    return x * x;\n}").isEmpty())
        assertTrue(CSharpCourse.check(CSharpCourse.VARS, "int age = 30;\nConsole.WriteLine(age);").isEmpty())
    }
}
