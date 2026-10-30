package local.phantom.companion.core.ai.route

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIError
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.ai.ToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainChainTest {

    private class Brain(
        private val routes: Set<Route>,
        private val chunks: List<AIChunk>,
    ) : AIRouter {
        var calls = 0
        override fun complete(intent: AIIntent): Flow<AIChunk> {
            calls++
            return flowOf(*chunks.toTypedArray())
        }
        override suspend fun availableRoutes(): Set<Route> = routes
    }

    private val absentLocal = Brain(emptySet(), listOf(AIChunk.ErrorChunk(AIError.NoNetwork(), route = Route.CLOUD_GEMINI)))
    private val noKeyCloud = Brain(emptySet(), listOf(AIChunk.ErrorChunk(AIError.CloudRejected(401, "no API key configured"), route = Route.CLOUD_GEMINI)))

    private fun answer(route: Route, text: String) = listOf(
        AIChunk.Token(text, route),
        AIChunk.Final(text, tokensUsed = 1, route = route, durationMs = 1),
    )

    private fun tool(name: String) = ToolDefinition(name, "", JsonObject(emptyMap()))

    @Test
    fun `без жодного розуму ланцюг не вдає, що він є`() = runTest {
        val chain = BrainChain(listOf(noKeyCloud, absentLocal))
        assertTrue(
            "правила інструментів — не розум; інакше AiBootstrapper бачить Ready і нічого не шукає",
            chain.availableRoutes().isEmpty(),
        )
    }

    @Test
    fun `без розуму звичайне питання дістає чесний порожній стан, а не текст`() = runTest {
        val out = BrainChain(listOf(noKeyCloud, absentLocal)).complete(AIIntent.Text("розкажи щось цікаве")).toList()
        assertEquals(1, out.size)
        val err = out.single() as AIChunk.ErrorChunk
        assertTrue(err.cause is AIError.NoBrain)
        assertTrue(out.none { it is AIChunk.Token || it is AIChunk.Final })
    }

    @Test
    fun `недоступний розум пропускається, наступний відповідає під своїм маршрутом`() = runTest {
        val cloud = Brain(setOf(Route.CLOUD_GEMINI), answer(Route.CLOUD_GEMINI, "привіт"))
        val offlinePc = Brain(emptySet(), answer(Route.PC_NODE, "не мав би"))
        val chain = BrainChain(listOf(offlinePc, cloud))
        assertEquals(setOf(Route.CLOUD_GEMINI), chain.availableRoutes())
        val out = chain.complete(AIIntent.Text("привіт")).toList()
        assertEquals(0, offlinePc.calls)
        assertEquals(1, cloud.calls)
        assertEquals(listOf(Route.CLOUD_GEMINI, Route.CLOUD_GEMINI), out.map { (it as? AIChunk.Token)?.route ?: (it as AIChunk.Final).route })
    }

    @Test
    fun `ПК першим, хмара не чіпається, кожен шматок підписаний ПК`() = runTest {
        val pc = Brain(setOf(Route.PC_NODE), answer(Route.PC_NODE, "з ПК"))
        val cloud = Brain(setOf(Route.CLOUD_GEMINI), answer(Route.CLOUD_GEMINI, "з хмари"))
        val out = BrainChain(listOf(pc, cloud)).complete(AIIntent.Text("привіт")).toList()
        assertEquals(0, cloud.calls)
        assertTrue(out.all { (it as? AIChunk.Token)?.route ?: (it as AIChunk.Final).route == Route.PC_NODE })
    }

    @Test
    fun `ПК упав — відповідає хмара під своїм імʼям`() = runTest {
        val pc = Brain(setOf(Route.PC_NODE), listOf(AIChunk.ErrorChunk(AIError.PcUnavailable(503, "AI unavailable"), route = Route.PC_NODE)))
        val cloud = Brain(setOf(Route.CLOUD_GEMINI), answer(Route.CLOUD_GEMINI, "з хмари"))
        val out = BrainChain(listOf(pc, cloud)).complete(AIIntent.Text("привіт")).toList()
        assertEquals(answer(Route.CLOUD_GEMINI, "з хмари"), out)
    }

    @Test
    fun `відмова справжнього розуму видна як його відмова, а не як «розуму немає»`() = runTest {
        val crash = AIError.CloudRejected(429, "quota")
        val local = Brain(setOf(Route.CLOUD_GEMINI), listOf(AIChunk.ErrorChunk(crash, route = Route.CLOUD_GEMINI)))
        val out = BrainChain(listOf(local)).complete(AIIntent.Text("привіт")).toList()
        assertEquals(crash, (out.single() as AIChunk.ErrorChunk).cause)
    }

    @Test
    fun `без розуму прохання про укриття все одно кличе інструмент, підписаний правилами`() = runTest {
        // Імʼя — те, під яким інструмент стоїть у реєстрі (MapNearestShelterTool.id).
        val intent = AIIntent.Text("Де найближче укриття?", tools = listOf(tool("shelter.nearest")))
        val call = BrainChain(listOf(absentLocal)).complete(intent).toList().single() as AIChunk.ToolCall
        assertEquals("shelter.nearest", call.name)
        assertEquals(Route.ON_DEVICE_RULES, call.route)
    }

    @Test
    fun `без розуму маршрут кличе map route з ціллю в полі to`() = runTest {
        val intent = AIIntent.Text("Проклади маршрут до Золотих воріт", tools = listOf(tool("map.route")))
        val call = BrainChain(listOf(absentLocal)).complete(intent).toList().single() as AIChunk.ToolCall
        assertEquals("map.route", call.name)
        assertEquals("""{"to":"Золотих воріт"}""", call.arguments)
    }

    @Test
    fun `старий результат інструмента в історії не підміняє нове питання`() = runTest {
        val intent = AIIntent.Text(
            "що ти вмієш",
            history = listOf(
                AIIntent.Text.Turn(AIIntent.Text.Role.TOOL, """{"ok":true,"output":"20°C"}"""),
                AIIntent.Text.Turn(AIIntent.Text.Role.USER, "дякую"),
            ),
        )
        val out = BrainChain(listOf(absentLocal)).complete(intent).toList()
        assertTrue((out.single() as AIChunk.ErrorChunk).cause is AIError.NoBrain)
    }

    @Test
    fun `результат інструмента показується вмістом, а не конвертом`() = runTest {
        val intent = AIIntent.Text(
            "де укриття",
            history = listOf(AIIntent.Text.Turn(AIIntent.Text.Role.TOOL, """{"ok":true,"output":"{\"name\":\"Школа 5\",\"distance_m\":320}"}""")),
        )
        val final = BrainChain(listOf(absentLocal)).complete(intent).toList().last() as AIChunk.Final
        assertEquals("• Назва: Школа 5\n• Відстань, м: 320", final.text)
        assertEquals(Route.ON_DEVICE_RULES, final.route)
    }
}
