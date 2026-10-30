package local.phantom.companion.self

import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.ui.messenger.PhantomSelfRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomSelfTest {

    private var brain = false
    private val memory = SelfMemory(MemoryFactStore(FakeFactDao()))
    private val self = PhantomSelf(
        memory,
        CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
        hasBrain = { brain },
        render = ::plain,
    )

    @Test
    fun `without a mind PHANTOM says so and still remembers`() = runBlocking {
        assertEquals("NO_BRAIN()", self.answer("розкажи анекдот"))
        assertTrue(self.answer("Мене звати Кирило")!!.startsWith("HEARD_NO_BRAIN"))
        val list = self.answer("що ти про мене знаєш")!!
        assertTrue(list.contains("Тебе звати Кирило — FROM_HEARD()"))
    }

    @Test
    fun `without a mind a shelter ask is left to the tools on the phone`() = runBlocking {
        val rules = local.phantom.companion.core.ai.route.OnDeviceToolRules()
        val withRules = PhantomSelf(
            memory,
            CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
            hasBrain = { false },
            render = ::plain,
            toolAsk = rules::recognizes,
        )
        assertNull(withRules.answer("Де найближче укриття?"))
        assertNull(withRules.answer("Проклади маршрут до вокзалу"))
        assertEquals("NO_BRAIN()", withRules.answer("розкажи анекдот"))
    }

    /** Аудит №13: «які додатки у мене» — питання до телефона, а не до памʼяті про людину. */
    @Test
    fun `a question about the phone is left to the tools, not answered from memory`() = runBlocking {
        val rules = local.phantom.companion.core.ai.route.OnDeviceToolRules()
        val withRules = PhantomSelf(
            memory,
            CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
            hasBrain = { true },
            render = ::plain,
            toolAsk = rules::recognizes,
            phoneAsk = rules::asksPhone,
        )
        assertNull(withRules.answer("Які додатки у мене встановлені?"))
        assertNull(withRules.answer("Що в мене в календарі на завтра?"))
    }

    /** Аудит symbiote 25.10: памʼять порожня, а день, рахунок і друзі — у застосунку, тож відповідає інструмент. */
    @Test
    fun `day, money and people asks are left to the tools, a personal fact is still not known`() = runBlocking {
        val rules = local.phantom.companion.core.ai.route.OnDeviceToolRules()
        val withRules = PhantomSelf(
            memory,
            CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
            hasBrain = { true },
            render = ::plain,
            toolAsk = rules::recognizes,
            phoneAsk = rules::asksPhone,
        )
        for (ask in listOf("Що в мене сьогодні?", "Скільки в мене на рахунку?", "Хто з моїх друзів поруч?", "Які в мене плани на вечір?")) {
            assertNull(ask, withRules.answer(ask))
        }
        assertEquals("Не знаю — ти мені цього не казав.", withRules.answer("Яка марка мого ноутбука?"))
    }

    @Test
    fun `with a mind, heard facts go quietly into memory and the talk goes on`() = runBlocking {
        brain = true
        assertNull(self.answer("Я живу у Львові. Порадь каву поруч"))
        assertEquals(listOf("Ти живеш у Львові"), memory.known().map { it.text })
        assertTrue(self.promptMemory().contains("Ти живеш у Львові"))
    }

    @Test
    fun `not now and dont do this answer the last visit`() = runBlocking {
        val store = MemoryInitiativeStore()
        val ini = Initiative(store)
        val talk = PhantomSelf(memory, CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()), { true }, ::plain, ini) { 1_000L }
        ini.offer(Initiative.Visit("v", Initiative.Kind.LESSON, "k", "b", "w", 0))
        ini.decide(Initiative.Moment(java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), false))
        assertEquals("VISIT_NOT_NOW()", talk.answer("Не зараз"))
        assertEquals(1_000L + Initiative.NOT_NOW_MS, store.s.queue.single().dueAtMs)
        ini.decide(Initiative.Moment(1_000L + Initiative.NOT_NOW_MS + java.time.LocalDate.now().atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), false))
        assertEquals("VISIT_DONT()", talk.answer("так не роби"))
        assertTrue(Initiative.Kind.LESSON in store.s.muted)
        talk.answer("вчи мене C#")
        assertTrue(store.s.muted.isEmpty())
    }

    @Test
    fun `remember, list, forget in chat`() = runBlocking {
        brain = true
        assertEquals("REMEMBERED(Ти любиш каву)", self.answer("Запамʼятай, що я люблю каву"))
        assertEquals("FORGOT(Ти любиш каву)", self.answer("Забудь про каву"))
        assertEquals("KNOWS_NOTHING()", self.answer("Що ти про мене знаєш?"))
        assertEquals("FORGOT_NOTHING(каву)", self.answer("Забудь про каву"))
    }

    @Test
    fun `reminder precheck answers before model, falls through on empty sink, and leaves promises to commitments`() = runBlocking {
        val kyiv = java.time.ZoneId.of("Europe/Kyiv")
        val clock = java.time.LocalDateTime.of(2026, 10, 15, 10, 0).atZone(kyiv).toInstant().toEpochMilli()
        val commitStore = InMemoryCommitmentStore()
        val commitService = CommitmentService(
            store = commitStore,
            roster = { emptyList() },
            now = { clock },
            zone = { kyiv },
        )
        val recorded = mutableListOf<Pair<String, Long>>()
        var nextId = "rem_1"
        val talk = PhantomSelf(
            memory = memory,
            tutor = CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()),
            hasBrain = { true },
            render = ::plain,
            commitments = commitService,
            reminders = ReminderSink { text, atMs ->
                recorded += text to atMs
                nextId
            },
            english = { false },
            now = { clock },
        )

        val reply = talk.answer("remind me in 20 minutes to call back")
        assertEquals("REMINDER_SET(Call back,сьогодні 10:20)", reply)
        assertEquals(listOf("Call back" to (clock + 20 * 60_000L)), recorded)

        recorded.clear()
        nextId = ""
        assertNull(talk.answer("remind me in 20 minutes to call back"))
        assertEquals(1, recorded.size)

        recorded.clear()
        nextId = "rem_2"
        val commitReply = talk.answer("Нагадаю Олі фото завтра")
        assertEquals("COMMITMENT_SAVED_DUE(Оля,фото,WHEN_TOMORROW)", commitReply)
        assertTrue(recorded.isEmpty())
        assertEquals(1, commitStore.listOpen().size)
    }
}

class PhantomSelfRouteTest {
    private fun json(s: String) = kotlinx.serialization.json.Json.parseToJsonElement(s) as kotlinx.serialization.json.JsonObject

    private val memory = SelfMemory(MemoryFactStore(FakeFactDao()))
    private val self = PhantomSelf(memory, CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()), { false }, ::plain)

    @Test
    fun `the not-true button forgets through the route`() = runBlocking {
        val route = PhantomSelfRoute({ self }, { it.name })
        val id = memory.remember(SelfSpeech.Fact("Ти любиш каву"), SelfMemory.From.HEARD)
        assertTrue(route.handle("GET", listOf("messenger", "self", "facts")).contains("Ти любиш каву"))
        val gone = json(route.handle("POST", listOf("messenger", "self", "facts", id, "forget")))
        assertEquals("true", gone["ok"].toString())
        assertTrue(memory.known().isEmpty())
    }

    @Test
    fun `one-shot ask answers or says there is no mind`() = runBlocking {
        val none = PhantomSelfRoute({ self }, { it.name }, noMind = { "нема" })
        val r = json(none.handle("POST", listOf("messenger", "self", "ask"), """{"text":"переклади"}"""))
        assertEquals("503", r["status"].toString())
        assertEquals("\"нема\"", r["detail"].toString())
        val some = PhantomSelfRoute({ self }, { it.name }, ask = { p, _ -> "echo:$p" })
        val a = json(some.handle("POST", listOf("messenger", "self", "ask"), """{"text":"hi"}"""))
        assertEquals("{\"answer\":\"echo:hi\"}", a["data"].toString())
    }
}
