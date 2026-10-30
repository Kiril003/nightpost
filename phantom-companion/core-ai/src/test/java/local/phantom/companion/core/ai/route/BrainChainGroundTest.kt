package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Розум телефона спирається на памʼять: без опори — «не знаю», з опорою — блок і цитати. */
class BrainChainGroundTest {

    private class Brain(private val chunks: List<AIChunk>) : AIRouter {
        val seen = ArrayList<AIIntent>()
        override fun complete(intent: AIIntent): Flow<AIChunk> {
            seen += intent
            return flowOf(*chunks.toTypedArray())
        }
        override suspend fun availableRoutes(): Set<Route> = setOf(Route.CLOUD_GEMINI)
    }

    private class Memory(
        private val know: Map<String, String> = mapOf("3fa2c1d0" to "У тебе є пес Бублик"),
    ) : MemoryGround {
        override suspend fun dontKnow(query: String) = if ("ноутбук" in query) "Не знаю — ти мені цього не казав." else null
        override suspend fun block(query: String) = "${MemoryGround.HEADER_MARK} — записи]\n- [m:3fa2c1d0] У тебе є пес Бублик"
        override suspend fun cite(shortIds: List<String>) = shortIds.mapNotNull { know[it] }
    }

    private fun answer(vararg tokens: String): List<AIChunk> =
        tokens.map { AIChunk.Token(it, Route.CLOUD_GEMINI) } +
            AIChunk.Final(tokens.joinToString(""), 1, Route.CLOUD_GEMINI, 1)

    @Test fun `no grounding — «не знаю» and the model is never asked`() = runTest {
        val brain = Brain(answer("Lenovo ThinkPad"))
        val out = BrainChain(listOf(brain), memory = Memory())
            .complete(AIIntent.Text("Яка марка мого ноутбука?", grounded = true)).toList()
        assertEquals("Не знаю — ти мені цього не казав.", (out.last() as AIChunk.Final).text)
        assertTrue(brain.seen.isEmpty())
    }

    /** Аудит №13: про сам телефон памʼять не свідок — питання йде до розуму з інструментами. */
    @Test fun `a question about the phone itself is not answered «не знаю» by memory`() = runTest {
        val personal = object : MemoryGround {
            override suspend fun dontKnow(query: String) =
                if ("у мене" in query || "в мене" in query) "Не знаю — ти мені цього не казав." else null
            override suspend fun block(query: String): String? = null
            override suspend fun cite(shortIds: List<String>) = emptyList<String>()
        }
        val tools = listOf(local.phantom.companion.core.ai.ToolDefinition("apps.list", "apps", kotlinx.serialization.json.buildJsonObject { }))
        for (ask in listOf("Які додатки у мене встановлені?", "Що в мене в календарі на завтра?")) {
            val brain = Brain(answer("Дивлюсь."))
            val out = BrainChain(listOf(brain), memory = personal)
                .complete(AIIntent.Text(ask, grounded = true, tools = tools)).toList()
            assertEquals(ask, 1, brain.seen.size)
            assertEquals(ask, "Дивлюсь.", (out.last() as AIChunk.Final).text)
        }
    }

    /** Аудит symbiote 25.10: день, гроші й люди — дані застосунку, а не памʼять про людину. */
    @Test fun `day, money and people asks reach the model, a personal fact is still «не знаю»`() = runTest {
        val personal = object : MemoryGround {
            override suspend fun dontKnow(query: String) =
                if ("у мене" in query || "в мене" in query || "мо" in query) "Не знаю — ти мені цього не казав." else null
            override suspend fun block(query: String): String? = null
            override suspend fun cite(shortIds: List<String>) = emptyList<String>()
        }
        val tools = listOf(local.phantom.companion.core.ai.ToolDefinition("day.today", "day", kotlinx.serialization.json.buildJsonObject { }))
        for (ask in listOf("Що в мене сьогодні?", "Скільки в мене на рахунку?", "Хто з моїх друзів поруч?", "Які в мене плани на вечір?")) {
            val brain = Brain(answer("Дивлюсь."))
            val out = BrainChain(listOf(brain), memory = personal)
                .complete(AIIntent.Text(ask, grounded = true, tools = tools)).toList()
            assertEquals(ask, 1, brain.seen.size)
            assertEquals(ask, "Дивлюсь.", (out.last() as AIChunk.Final).text)
        }
        val brain = Brain(answer("Lenovo"))
        val out = BrainChain(listOf(brain), memory = personal)
            .complete(AIIntent.Text("Яка марка мого ноутбука?", grounded = true, tools = tools)).toList()
        assertEquals("Не знаю — ти мені цього не казав.", (out.last() as AIChunk.Final).text)
        assertTrue(brain.seen.isEmpty())
    }

    @Test fun `grounded — the memory block is in the prompt and the answer cites it`() = runTest {
        val brain = Brain(answer("Твого пса звуть Бублик ", "[m:3fa", "2c1d0].", ""))
        val out = BrainChain(listOf(brain), memory = Memory())
            .complete(AIIntent.Text("Як звати мого пса?", systemPrompt = "Ти PHANTOM.", grounded = true)).toList()
        val sys = (brain.seen.single() as AIIntent.Text).systemPrompt!!
        assertTrue(sys, sys.startsWith("Ти PHANTOM.") && "[m:3fa2c1d0] У тебе є пес Бублик" in sys)
        val streamed = out.filterIsInstance<AIChunk.Token>().joinToString("") { it.text }
        assertFalse(streamed, "[m:" in streamed || "3fa" in streamed)
        assertEquals("Твого пса звуть Бублик.\n\nЗ памʼяті: «У тебе є пес Бублик»", (out.last() as AIChunk.Final).text)
    }

    @Test fun `an English answer is not signed in Ukrainian`() = runTest {
        val brain = Brain(answer("Your dog is called Bublyk [m:3fa2c1d0]."))
        val out = BrainChain(listOf(brain), memory = Memory())
            .complete(AIIntent.Text("What is my dog called?", systemPrompt = "You are PHANTOM.", grounded = true)).toList()
        val text = (out.last() as AIChunk.Final).text
        assertEquals("Your dog is called Bublyk.\n\nFrom memory: «У тебе є пес Бублик»", text)
        assertFalse(text, "З памʼяті" in text)
    }

    @Test fun `service calls are not grounded — a translation of a question stays a translation`() = runTest {
        val brain = Brain(answer("What brand is my laptop?"))
        val out = BrainChain(listOf(brain), memory = Memory())
            .complete(AIIntent.Text("Яка марка мого ноутбука?", systemPrompt = "Переклади англійською")).toList()
        assertEquals("What brand is my laptop?", (out.last() as AIChunk.Final).text)
        assertNull((brain.seen.single() as AIIntent.Text).systemPrompt?.takeIf { MemoryGround.HEADER_MARK in it })
    }

    @Test fun `citation filter holds a split marker until it is complete`() {
        val f = CitationFilter()
        assertEquals("Бублик", f.feed("Бублик [m:3f"))
        assertEquals(".", f.feed("a2c1d0]."))
        assertEquals(listOf("3fa2c1d0"), f.seen.toList())
        assertEquals("a [b", f.feed("a [b"))
    }
}
