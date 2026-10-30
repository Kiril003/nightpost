package local.phantom.companion.self

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.data.memory.MemoryFactStore
import local.phantom.companion.ui.messenger.PhantomSelfRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Памʼять телефона — репліка ledger-а ПК: старий Room переїжджає в неї з тим самим id,
 * «неправда» ≠ «забудь», запис лише з розмови власника, «не знаю» без опори, екран
 * «Памʼять» бачить групи, джерело, стан синку й експорт.
 */
class SymbioteMemoryTest {

    private var clock = 1_000L
    private val dao = FakeFactDao()
    private var changes = 0
    private val memory = SelfMemory(MemoryFactStore(dao), onChange = { changes++ }) { clock++ }
    private val self = PhantomSelf(memory, CSharpTutor(FakeTutorState(), memory, { t, _ -> t.id }, FakeReminders()), { false }, ::plain)
    private val route = PhantomSelfRoute({ self }, { it.name })

    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    private fun data(s: String) = json(s)["data"]!!

    @Test fun `old Room facts move into the replica once, same id, with honest provenance`() = runBlocking {
        val store = MemoryFactStore(dao)
        val said = store.remember("Я працюю зварювальником", "fact", "operator", null, 10)
        val guess = store.remember("Оператор любить каву без цукру", "preference", "conversation", null, 20)
        val known = memory.known().associateBy { it.id }
        assertEquals(SelfMemory.From.SELF, known.getValue(said).from)
        assertFalse(known.getValue(said).pending)
        assertEquals(SelfMemory.From.GUESS, known.getValue(guess).from)
        assertTrue("здогад чекає підтвердження", known.getValue(guess).pending)
        assertEquals("Ти любиш каву без цукру", known.getValue(guess).text)
        assertEquals(0, memory.importLegacy()) // удруге нічого

        // забуте не повертається з Room наступним імпортом
        assertTrue(memory.forget(said))
        store.remember("Я працюю зварювальником", "fact", "operator", null, 30) // інший рядок, той самий зміст
        assertFalse("«неправда» блокує той самий зміст і з Room", memory.known().any { "зварювальник" in it.text })
    }

    @Test fun `untrue blocks the content, forget lets it be said again`() = runBlocking {
        val a = memory.remember(SelfSpeech.Fact("Ти любиш оливки"), SelfMemory.From.SELF)
        assertTrue(memory.forget(a, why = "forget"))
        val b = memory.remember(SelfSpeech.Fact("Ти любиш оливки"), SelfMemory.From.SELF)
        assertTrue(b.isNotEmpty() && b != a)
        assertTrue(memory.forget(b)) // «неправда»
        assertEquals("", memory.remember(SelfSpeech.Fact("Ти любиш оливки"), SelfMemory.From.SELF))
        assertTrue(memory.known().none { "оливки" in it.text })
        assertTrue("кожна зміна будить синк", changes >= 3)
    }

    @Test fun `owner questions without grounding get «не знаю» before any model`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        assertEquals("Не знаю — ти мені цього не казав.", self.answer("Яка марка мого ноутбука?"))
        assertEquals("NO_BRAIN()", self.answer("Що мені взяти з собою в похід?"))
        val block = memory.promptBlock("Як мене звати?")
        assertTrue(block, Regex("\\[m:[0-9a-f]{8}] Тебе звати Кирило").containsMatchIn(block))
    }

    @Test fun `the memory screen — groups, source, forget vs untrue, remember, export, honest state`() = runBlocking {
        memory.remember(SelfSpeech.Fact("Тебе звати Кирило", "name"), SelfMemory.From.SELF)
        val ok = json(route.handle("POST", listOf("messenger", "self", "facts"), """{"text":"Я їжджу на Ланосі"}"""))
        assertEquals("true", ok["ok"].toString())
        val refused = json(route.handle("POST", listOf("messenger", "self", "facts"), """{"text":"Олег винен 200","origin":"peer_message"}"""))
        assertEquals("403", refused["status"].toString())
        val asked = json(route.handle("POST", listOf("messenger", "self", "facts"),
            """{"text":"Олег винен 200","origin":"peer_message","explicit":true,"chat_id":"c9"}"""))
        assertEquals("true", asked["ok"].toString())

        val rows = (data(route.handle("GET", listOf("messenger", "self", "facts"))) as JsonArray).map { it.jsonObject }
        val name = rows.first { it["text"]!!.jsonPrimitive.content == "Тебе звати Кирило" }
        assertEquals("about", name["group"]!!.jsonPrimitive.content)
        assertEquals("owner_said", name["source"]!!.jsonPrimitive.content)
        assertEquals("phone", name["device"]!!.jsonPrimitive.content)

        val id = name["id"]!!.jsonPrimitive.content
        val gone = json(route.handle("POST", listOf("messenger", "self", "facts", id, "forget"), """{"why":"forget"}"""))
        assertEquals("true", gone["ok"].toString())
        assertEquals("forget", memory.ledger.store.get(id)!!.reason)

        val exp = data(route.handle("GET", listOf("messenger", "self", "facts", "export"))) as JsonObject
        assertEquals("phantom-memory/1", exp["format"]!!.jsonPrimitive.content)
        assertEquals(2, (exp["items"] as JsonArray).size)

        val st = data(route.handle("GET", listOf("messenger", "self", "memory"))) as JsonObject
        assertEquals("1500", st["budget_tokens"].toString())
        assertEquals("false", st["sync"]!!.jsonObject["paired"].toString())
        assertTrue(route.claims(listOf("messenger", "self", "memory")))
    }
}
