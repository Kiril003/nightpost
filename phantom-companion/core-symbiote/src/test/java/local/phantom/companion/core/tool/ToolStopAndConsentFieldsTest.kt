package local.phantom.companion.core.tool

import android.app.Application
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.agent.AgentMissionHub
import local.phantom.companion.core.agent.AgentSpec
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.permission.Decision
import local.phantom.companion.core.tool.permission.GateOutcome
import local.phantom.companion.core.tool.permission.InMemoryToolPermissionStore
import local.phantom.companion.core.tool.permission.SymbioteStop
import local.phantom.companion.core.tool.permission.ToolAsk
import local.phantom.companion.core.tool.permission.ToolAskFields
import local.phantom.companion.core.tool.permission.ToolPermissionGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolStopAndConsentFieldsTest {

    private val stubContext = Application()

    @Before
    fun setUp() {
        SymbioteStop.resume()
        AgentMissionHub.clearFinished()
    }

    @After
    fun tearDown() {
        SymbioteStop.resume()
        AgentMissionHub.clearFinished()
    }

    private fun fakeContext() = ToolContext(
        appContext = stubContext,
        operatorId = "op",
        requestId = "req",
        agentDepth = 0,
        approvedPermissions = emptySet(),
    )

    @Test
    fun `ToolExecutor refuses at entry when SymbioteStop is stopped`() = runTest {
        val executor = ToolExecutor()
        var ran = false
        val tool = object : Tool {
            override val id = "wait"
            override val description = "wait"
            override val argsSchema = JsonObject(emptyMap())
            override val permissions = emptySet<ToolPermission>()
            override val risk = ToolRisk.READ
            override val streaming = true
            override val manifest = FeatureManifest(id = "tool.wait", tier = 1, displayName = "Wait")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult {
                ran = true
                return ToolResult.Ok("ok", null, 1L)
            }
        }

        SymbioteStop.stopAll()
        val result = executor.run(tool, JsonObject(emptyMap()), fakeContext()) { }
        assertFalse("інструмент не повинен запускатися під час SymbioteStop", ran)
        assertTrue("очікував Cancelled або Err, отримав $result", result is ToolResult.Cancelled || result is ToolResult.Err)
        assertFalse("відмова через SymbioteStop не повинна відкривати circuit breaker", executor.isOpen("wait"))
    }

    @Test
    fun `ToolExecutor cancels in-flight tool immediately when stopAll fires`() = runTest {
        val executor = ToolExecutor()
        var started = false
        var completedNormally = false
        val slowTool = object : Tool {
            override val id = "slow"
            override val description = "slow"
            override val argsSchema = JsonObject(emptyMap())
            override val permissions = emptySet<ToolPermission>()
            override val risk = ToolRisk.READ
            override val streaming = true
            override val manifest = FeatureManifest(id = "tool.slow", tier = 1, displayName = "Slow")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult {
                started = true
                delay(60_000L)
                completedNormally = true
                return ToolResult.Ok("done", null, 60_000L)
            }
        }

        val deferred = async {
            executor.run(slowTool, JsonObject(emptyMap()), fakeContext()) { }
        }
        testScheduler.runCurrent()
        assertTrue("інструмент мав стартувати", started)

        SymbioteStop.stopAll()
        testScheduler.runCurrent()

        val outcome = deferred.await()
        assertFalse("інструмент не мав дочекатися завершення delay", completedNormally)
        assertTrue("очікував Cancelled після stopAll, отримав $outcome", outcome is ToolResult.Cancelled)
    }

    @Test
    fun `AgentMissionHub refuses request while SymbioteStop is stopped`() {
        SymbioteStop.stopAll()
        val spec = AgentSpec(agentId = "m-stopped", goal = "test mission")
        AgentMissionHub.request(spec, nowMs = 1000L)

        val mission = AgentMissionHub.find("m-stopped")
        assertFalse("під час SymbioteStop не має зʼявлятись активних місій", AgentMissionHub.hasActive)
        if (mission != null) {
            assertEquals(AgentMissionHub.Phase.CANCELLED, mission.phase)
        }
    }

    @Test
    fun `undeclared keys in args are demoted to BODY while declared schema keys stay in header`() {
        val schema = buildJsonObject {
            put("who", JsonPrimitive("string"))
            put("text", JsonPrimitive("string"))
        }
        // Attacker / hallucinated "recipient" key is NOT in people.draft's argsSchema
        val args = """{"recipient":"Шахрай","who":"Мама","text":"Привіт"}"""
        val fields = ToolAskFields.extract("people.draft", args, schema.keys)

        val headerRecipients = fields.filter { it.kind == ToolAsk.FieldKind.RECIPIENT }
        assertEquals("лише оголошений у схемі ключ 'who' може бути RECIPIENT у заголовку", 1, headerRecipients.size)
        assertEquals("who", headerRecipients.single().key)
        assertEquals("Мама", headerRecipients.single().value)

        val bodyFields = fields.filter { it.kind == ToolAsk.FieldKind.BODY }
        assertTrue("неоголошений 'recipient' має опинитись у BODY", bodyFields.any { it.key == "recipient" && it.value == "Шахрай" })
    }

    @Test
    fun `declared people, package, and data keys stay in header even when longer than 120 chars`() {
        val meetupSchema = setOf("people", "place", "at")
        val longPeople = (1..20).joinToString(",") { "\"УчасникНомер$it\"" }
        val meetupFields = ToolAskFields.extract(
            "meetup.create",
            """{"people":[$longPeople],"place":"Кафе"}""",
            meetupSchema,
        )
        val peopleField = meetupFields.firstOrNull { it.key == "people" }
        assertNotNull(peopleField)
        assertEquals(
            "people має бути RECIPIENT у заголовку навіть при >120 символів",
            ToolAsk.FieldKind.RECIPIENT,
            peopleField!!.kind,
        )

        val launchSchema = setOf("package", "query", "action", "data", "extras", "category")
        val longDataUri = "https://bank-phish.example.com/login?" + "a=1&".repeat(40)
        val launchFields = ToolAskFields.extract(
            "launch",
            """{"package":"com.example.app","data":"$longDataUri","category":"android.intent.category.DEFAULT"}""",
            launchSchema,
        )
        val dataField = launchFields.firstOrNull { it.key == "data" }
        val pkgField = launchFields.firstOrNull { it.key == "package" }
        assertNotNull(dataField)
        assertNotNull(pkgField)
        assertEquals("data у launch має класифікуватися як URL у заголовку", ToolAsk.FieldKind.URL, dataField!!.kind)
        assertFalse("package у launch має залишатися в заголовку, а не в BODY", pkgField!!.isScrollableBody)
    }

    @Test
    fun `ToolPermissionGate passes tool argsSchema keys into ToolAsk fields`() = runTest {
        val store = InMemoryToolPermissionStore()
        var capturedAsk: ToolAsk? = null
        val gate = ToolPermissionGate(
            store = store,
            ask = { ask ->
                capturedAsk = ask
                Decision.ONCE
            },
        )
        val peopleDraftTool = object : Tool {
            override val id = "people.draft"
            override val description = "draft"
            override val argsSchema = buildJsonObject {
                put("who", JsonPrimitive("string"))
                put("text", JsonPrimitive("string"))
            }
            override val permissions = emptySet<ToolPermission>()
            override val risk = ToolRisk.OUTWARD
            override val streaming = false
            override val manifest = FeatureManifest(id = "tool.people.draft", tier = 1, displayName = "Draft")
            override suspend fun execute(
                args: JsonObject,
                context: ToolContext,
                emit: suspend (ToolEvent) -> Unit,
            ): ToolResult = ToolResult.Ok("ok", null, 1L)
        }

        val outcome = gate.check(peopleDraftTool, """{"recipient":"Spoof","who":"Олена","text":"Привіт"}""")
        assertTrue(outcome is GateOutcome.Granted)
        val recipients = capturedAsk!!.fields.filter { it.kind == ToolAsk.FieldKind.RECIPIENT }
        assertEquals(1, recipients.size)
        assertEquals("who", recipients.single().key)
        assertEquals("Олена", recipients.single().value)
    }
}
