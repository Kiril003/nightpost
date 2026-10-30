package local.phantom.companion.ui.tools

import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.agent.AgentProgressSink
import local.phantom.companion.core.agent.AgentSpec
import local.phantom.companion.core.agent.AgentSupervisor
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.AIRouter
import local.phantom.companion.core.ai.Route
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.diary.InMemoryDecisionDiary
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.InMemoryToolPermissionStore
import local.phantom.companion.core.tool.permission.SymbioteStop
import local.phantom.companion.core.tool.permission.ToolAsk
import local.phantom.companion.core.tool.permission.ToolConsentPrompter
import local.phantom.companion.core.tool.permission.ToolPermissionGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * N3 (G4): Consent dialog shows typed fields in fixed positions (recipient,
 * amount, url, method before body), AgentSupervisor does not truncate args at
 * 160 chars, and long bodies are rendered in a scrollable container without
 * ellipsis.
 */
class ToolConsentHostTest {

    @Before
    fun setUp() {
        SymbioteStop.resume()
    }

    @After
    fun tearDown() {
        SymbioteStop.resume()
    }

    @Test
    fun `recipient is visible in fixed header position before a 2 KB body`() {
        val body2Kb = "Б".repeat(2048) + "_КІНЕЦЬ_ЛИСТА"
        // Adversarial key order: 2 KB body placed FIRST in JSON to push recipient far down.
        val rawArgs = buildJsonObject {
            put("text", JsonPrimitive(body2Kb))
            put("who", JsonPrimitive("Олена Коваль"))
        }.toString()

        val ask = ToolAsk(
            toolId = "people.draft",
            description = "Приготувати лист людині",
            risk = ToolRisk.OUTWARD,
            argsPreview = rawArgs,
        )
        val model = buildToolConsentModel(ask)

        assertTrue("отримувач мусить бути в шапці фіксованих полів", model.headerFields.isNotEmpty())
        val firstHeader = model.headerFields.first()
        assertEquals(ToolAsk.FieldKind.RECIPIENT, firstHeader.kind)
        assertEquals("Олена Коваль", firstHeader.value)

        assertEquals(1, model.bodyFields.size)
        val bodyField = model.bodyFields.single()
        assertEquals(ToolAsk.FieldKind.BODY, bodyField.kind)
        assertTrue("тіло 2 КБ не мусить обрізатися", bodyField.value.length > 2048)
        assertTrue("кінець 2 КБ тіла мусить бути збережений повністю", bodyField.value.endsWith("_КІНЕЦЬ_ЛИСТА"))
    }

    @Test
    fun `spoof test - recipient pushed past 160 chars through AgentSupervisor is still shown first`() = runTest {
        val padding = "П".repeat(320)
        val toolCallJson = """{"type":"tool-call","tool":"people.draft","args":{"text":"$padding","who":"Марта"}}"""
        val doneJson = """{"type":"agent-done","summary":"готово","artifacts":[]}"""

        val router = object : AIRouter {
            private var step = 0
            override fun complete(intent: AIIntent): Flow<AIChunk> {
                val reply = if (step++ == 0) toolCallJson else doneJson
                return flowOf(AIChunk.Final(reply, tokensUsed = 1, route = Route.CLOUD_GEMINI, durationMs = 1L))
            }
            override suspend fun availableRoutes() = setOf(Route.CLOUD_GEMINI)
        }
        val draftTool = object : Tool {
            override val id = "people.draft"
            override val description = "Чернетка листа"
            override val argsSchema = buildJsonObject {
                put("who", JsonPrimitive("string"))
                put("text", JsonPrimitive("string"))
            }
            override val permissions = emptySet<ToolPermission>()
            override val risk = ToolRisk.OUTWARD
            override val streaming = false
            override val manifest = FeatureManifest(id = "tool.people.draft", tier = 1, displayName = "Чернетка")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult = ToolResult.Ok("ok", null, 1L)
        }

        val prompter = ToolConsentPrompter()
        val gate = ToolPermissionGate(InMemoryToolPermissionStore(), prompter::request)
        val supervisor = AgentSupervisor(
            cloudRouter = router,
            localRouter = router,
            toolRegistryLookup = { if (it == draftTool.id) draftTool else null },
            toolRegistryList = { listOf(draftTool) },
            diary = InMemoryDecisionDiary(),
            toolGate = gate::check,
            toolContextFactory = { _, depth, reqId ->
                ToolContext(
                    appContext = android.app.Application(),
                    operatorId = "op",
                    requestId = reqId,
                    agentDepth = depth,
                    approvedPermissions = emptySet(),
                )
            },
        )

        val runDeferred = async {
            supervisor.run(
                AgentSpec(goal = "напиши Марті", allowedTools = setOf("people.draft"), maxSteps = 3),
                AgentProgressSink { },
            )
        }
        testScheduler.runCurrent()

        val pending = prompter.pending.value
        assertNotNull("вікно згоди має чекати на відповідь оператора", pending)
        val model = buildToolConsentModel(pending!!.ask)

        assertEquals(
            "навіть коли модель сховала who за >160 символами тексту, отримувач стоїть першим",
            ToolAsk.FieldKind.RECIPIENT,
            model.headerFields.first().kind,
        )
        assertEquals("Марта", model.headerFields.first().value)
        assertEquals(padding, model.bodyFields.single().value)

        pending.answerWith(Decision.ONCE)
        runDeferred.await()
    }

    @Test
    fun `typed fields order recipient amount url method before body regardless of JSON key order`() {
        val rawArgs = buildJsonObject {
            put("body", JsonPrimitive("payload-body"))
            put("method", JsonPrimitive("POST"))
            put("url", JsonPrimitive("https://api.example.com/pay"))
            put("currency", JsonPrimitive("USDC"))
            put("amount", JsonPrimitive("25"))
            put("recipient", JsonPrimitive("Olia"))
        }.toString()

        val ask = ToolAsk(
            toolId = "http",
            description = "HTTP",
            risk = ToolRisk.OUTWARD,
            argsPreview = rawArgs,
            askEveryCall = true,
        )
        val model = buildToolConsentModel(ask)

        assertEquals(
            listOf(
                ToolAsk.FieldKind.RECIPIENT,
                ToolAsk.FieldKind.AMOUNT,
                ToolAsk.FieldKind.URL,
                ToolAsk.FieldKind.METHOD,
            ),
            model.headerFields.map { it.kind },
        )
        assertEquals("Olia", model.headerFields[0].value)
        assertEquals("25 USDC", model.headerFields[1].value)
        assertEquals("https://api.example.com/pay", model.headerFields[2].value)
        assertEquals("POST", model.headerFields[3].value)
        assertEquals(listOf(ToolAsk.FieldKind.BODY), model.bodyFields.map { it.kind })
        assertEquals("payload-body", model.bodyFields.single().value)
    }

    @Test
    fun `ToolConsentHost source uses verticalScroll for body and never truncates args with maxLines 4`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-symbiote").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("repo root not found") as Nothing
        val hostFile = File(root, "app/src/main/java/local/phantom/companion/ui/tools/ToolConsentHost.kt")
        val src = hostFile.readText()

        assertTrue("ToolConsentHost мусить мати verticalScroll для довгих тіл", src.contains("verticalScroll"))
        assertFalse("ToolConsentHost не мусить різати аргументи через maxLines = 4", src.contains("maxLines = 4"))
    }
}
