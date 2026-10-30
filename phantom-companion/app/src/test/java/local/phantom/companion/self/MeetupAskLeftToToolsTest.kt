package local.phantom.companion.self

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.ai.route.OnDeviceToolRules
import local.phantom.companion.core.data.memory.MemoryFactStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Без розуму памʼять PHANTOM відповідала «розуму немає» на будь-що, що не вміла
 * сама. Прохання про зустріч і рахунок воно має лишити інструментам на
 * телефоні — інакше з «Спитати Nightpost» до `meetup.create` не дійти.
 */
class MeetupAskLeftToToolsTest {

    private val memory = SelfMemory(MemoryFactStore(FakeFactDao()))
    private val rules = OnDeviceToolRules()
    private val self = PhantomSelf(
        memory,
        CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
        hasBrain = { false },
        render = ::plain,
        toolAsk = rules::recognizes,
    )

    @Test
    fun `without a mind the meetup and bill asks go on to the tools`() = runBlocking {
        assertNull(self.answer("збери Олену й Петра в Cafe X о 19:00"))
        assertNull(self.answer("Збери Олену, Петра і Марту у кафе «Рояль» завтра о 7 вечора"))
        assertNull(self.answer("розділи рахунок"))
        assertNull(self.answer("розділи рахунок порівну"))
    }

    @Test
    fun `an ordinary collect request is still answered as no mind`() = runBlocking {
        assertEquals("NO_BRAIN()", self.answer("збери звіт до 18:00"))
    }
}
