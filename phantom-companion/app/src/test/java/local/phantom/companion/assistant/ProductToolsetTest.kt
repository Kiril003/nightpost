package local.phantom.companion.assistant

import android.content.ContextWrapper
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductToolsetTest {

    private val expectedIds = listOf(
        // READ (13)
        "day.today",
        "calendar.list",
        "task.list",
        "reminder.list",
        "memory.list",
        "people.list",
        "circles.list",
        "place.search",
        "place.nearby",
        "where.people",
        "wallet.balance",
        "map.viewport",
        "chat.unread_summary",
        // LOCAL_WRITE (6)
        "reminder.set",
        "reminder.cancel",
        "calendar.add",
        "task.add",
        "memory.remember",
        "memory.forget",
        // PROPOSAL (6)
        "meetup.create",
        "bill.split",
        "people.draft",
        "pay.draft",
        "request.draft",
        "share.location.draft",
    )

    private val registryBackingIds = listOf(
        "calendar.list",
        "task.list",
        "reminder.list",
        "memory.list",
        "people.list",
        "geo.search",
        "geo.nearby",
        "reminder.add",
        "reminder.remove",
        "calendar.add",
        "task.add",
        "memory.save",
        "memory.forget",
        "meetup.create",
        "bill.split",
        "people.draft",
    )

    private val ctx = ToolContext(
        appContext = ContextWrapper(null),
        operatorId = "op",
        requestId = "req",
        approvedPermissions = emptySet(),
    )

    @Test
    fun `exposes exact 25 card ids in order and no forbidden wartime or device ids`() {
        val toolset = ProductToolset(registryBackingIds.map { fakeTool(it) }, ProductSources())

        assertEquals(25, ProductToolset.IDS.size)
        assertEquals(expectedIds, ProductToolset.IDS)
        assertEquals(expectedIds, toolset.tools.map { it.id })

        val forbiddenPrefixes = listOf(
            "threat.", "exfil.", "medevac.", "shell", "pc.",
            "accessibility", "screen.read", "launch", "apps.", "device.", "phone.",
        )
        for (tool in toolset.tools) {
            for (prefix in forbiddenPrefixes) {
                assertFalse("Forbidden id ${tool.id}", tool.id.startsWith(prefix))
            }
        }
    }

    @Test
    fun `approval classes and backing ids match specification`() {
        val toolset = ProductToolset(registryBackingIds.map { fakeTool(it) }, ProductSources())

        expectedIds.subList(0, 13).forEach { id ->
            assertEquals(id, Approval.READ, toolset.approvalOf(id))
        }
        expectedIds.subList(13, 19).forEach { id ->
            assertEquals(id, Approval.LOCAL_WRITE, toolset.approvalOf(id))
        }
        expectedIds.subList(19, 25).forEach { id ->
            assertEquals(id, Approval.PROPOSAL, toolset.approvalOf(id))
        }
        assertNull(toolset.approvalOf("unknown.tool"))

        assertEquals("geo.search", toolset.backingId("place.search"))
        assertEquals("geo.nearby", toolset.backingId("place.nearby"))
        assertEquals("reminder.add", toolset.backingId("reminder.set"))
        assertEquals("reminder.remove", toolset.backingId("reminder.cancel"))
        assertEquals("memory.save", toolset.backingId("memory.remember"))
        assertEquals("calendar.list", toolset.backingId("calendar.list"))
        assertNull(toolset.backingId("day.today"))
        assertNull(toolset.backingId("circles.list"))
        assertNull(toolset.backingId("where.people"))
        assertNull(toolset.backingId("wallet.balance"))
        assertNull(toolset.backingId("map.viewport"))
        assertNull(toolset.backingId("chat.unread_summary"))
        assertNull(toolset.backingId("pay.draft"))
        assertNull(toolset.backingId("request.draft"))
        assertNull(toolset.backingId("share.location.draft"))

        assertEquals(21, toolset.offered.size)
        assertEquals("reminder.set", toolset.resolve("reminder.add")?.id)
        assertNull(toolset.resolve("pay.draft"))
    }

    @Test
    fun `every tool has non-empty latin description under 100 chars and schema object`() {
        val toolset = ProductToolset(registryBackingIds.map { fakeTool(it) }, ProductSources())

        for (tool in toolset.tools) {
            assertTrue(tool.id, tool.description.isNotBlank())
            assertTrue("${tool.id}: ${tool.description.length}", tool.description.length <= 100)
            assertFalse("${tool.id} has Cyrillic: ${tool.description}", tool.description.any { it in '\u0400'..'\u04FF' })
            assertNotNull(tool.id, tool.argsSchema)
        }
    }

    @Test
    fun `stubs and missing backing tools return unavailable Err without throwing`() = runBlocking {
        val emptyToolset = ProductToolset(emptyList(), ProductSources())

        for (stubId in listOf("chat.unread_summary", "pay.draft", "request.draft", "share.location.draft", "reminder.set", "place.search")) {
            val tool = emptyToolset.get(stubId)!!
            val res = tool.execute(JsonObject(emptyMap()), ctx) {}
            assertTrue("$stubId expected Err, got $res", res is ToolResult.Err)
            val err = res as ToolResult.Err
            assertEquals("unavailable", err.code)
            assertTrue(err.message.isNotBlank())
            assertFalse(err.message.any { it in '\u0400'..'\u04FF' })
        }
    }

    @Test
    fun `alias routing executes backing tool with exact args and preserves READ or LOCAL_WRITE result`() = runBlocking {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        val backing = registryBackingIds.map { id ->
            fakeTool(id) { args ->
                calls += id to args
                ToolResult.Ok("ran:$id", buildJsonObject { put("id", id) }, 3L)
            }
        }
        val toolset = ProductToolset(backing, ProductSources())
        val sampleArgs = buildJsonObject { put("text", "Buy milk") }

        val res = toolset.get("reminder.set")!!.execute(sampleArgs, ctx) {}
        assertEquals(1, calls.size)
        assertEquals("reminder.add", calls.single().first)
        assertEquals(sampleArgs, calls.single().second)
        assertEquals(ToolResult.Ok("ran:reminder.add", buildJsonObject { put("id", "reminder.add") }, 3L), res)
    }

    @Test
    fun `proposal tools guarantee kind draft in structured Ok result and leave Err untouched`() = runBlocking {
        val backingOk = listOf(
            fakeTool("meetup.create") { ToolResult.Ok("drafted", null, 5L) },
            fakeTool("bill.split") { ToolResult.Ok("split", buildJsonObject { put("total", 100) }, 6L) },
            fakeTool("people.draft") { ToolResult.Err("no_person", "Not found", 2L) },
        )
        val toolset = ProductToolset(backingOk, ProductSources())

        val meetupRes = toolset.get("meetup.create")!!.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok
        assertEquals("draft", meetupRes.structured!!["kind"]!!.jsonPrimitive.content)

        val billRes = toolset.get("bill.split")!!.execute(JsonObject(emptyMap()), ctx) {} as ToolResult.Ok
        assertEquals("draft", billRes.structured!!["kind"]!!.jsonPrimitive.content)
        assertEquals("100", billRes.structured!!["total"]!!.jsonPrimitive.content)

        val errRes = toolset.get("people.draft")!!.execute(JsonObject(emptyMap()), ctx) {}
        assertEquals(ToolResult.Err("no_person", "Not found", 2L), errRes)
    }

    private fun fakeTool(
        id: String,
        onExecute: suspend (JsonObject) -> ToolResult = { ToolResult.Ok("ok:$id", null, 1L) },
    ): Tool = object : Tool {
        override val id: String = id
        override val description: String = "Цириллиця опис $id"
        override val argsSchema: JsonObject = JsonObject(mapOf("q" to JsonPrimitive("string?")))
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = ToolRisk.READ
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(id = "tool.$id", tier = 1, displayName = id)
        override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult =
            onExecute(args)
    }
}
