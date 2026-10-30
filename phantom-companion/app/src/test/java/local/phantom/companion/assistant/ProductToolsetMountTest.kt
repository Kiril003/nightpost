package local.phantom.companion.assistant

import java.io.File
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductToolsetMountTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("repo root not found")
    }

    private fun fakeTool(id: String): Tool = object : Tool {
        override val id: String = id
        override val description: String = "desc $id"
        override val argsSchema: JsonObject = JsonObject(mapOf("q" to JsonPrimitive("string?")))
        override val permissions: Set<ToolPermission> = emptySet()
        override val risk: ToolRisk = ToolRisk.READ
        override val streaming: Boolean = false
        override val manifest: FeatureManifest = FeatureManifest(id = "tool.$id", tier = 1, displayName = id)
        override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult =
            ToolResult.Ok("ok:$id", null, 1L)
    }

    @Test
    fun `the chat offers product ids, not registry ids`() {
        val registry = listOf("reminder.add", "memory.save", "geo.search", "shell", "screen.tap").map(::fakeTool)
        val toolset = ProductToolset(registry, ProductSources())
        val offeredIds = toolset.offered.map { it.id }.toSet()

        assertTrue(offeredIds.containsAll(listOf("reminder.set", "memory.remember", "place.search")))
        assertTrue(offeredIds.containsAll(listOf("day.today", "circles.list", "where.people", "wallet.balance", "map.viewport")))
        assertFalse("reminder.add" in offeredIds)
        assertFalse("memory.save" in offeredIds)
        assertFalse("geo.search" in offeredIds)
        assertFalse("shell" in offeredIds)
        assertFalse("screen.tap" in offeredIds)
    }

    @Test
    fun `stubs are never offered`() {
        val registry = listOf(
            "calendar.list", "task.list", "reminder.list", "memory.list", "people.list",
            "geo.search", "geo.nearby", "reminder.add", "reminder.remove",
            "calendar.add", "task.add", "memory.save", "memory.forget",
            "meetup.create", "bill.split", "people.draft",
        ).map(::fakeTool)
        val toolset = ProductToolset(registry, ProductSources())
        val offeredIds = toolset.offered.map { it.id }.toSet()

        assertFalse("pay.draft" in offeredIds)
        assertFalse("request.draft" in offeredIds)
        assertFalse("share.location.draft" in offeredIds)
        assertFalse("chat.unread_summary" in offeredIds)

        val partialToolset = ProductToolset(listOf(fakeTool("reminder.add")), ProductSources())
        val partialIds = partialToolset.offered.map { it.id }.toSet()
        assertTrue("reminder.set" in partialIds)
        assertFalse("calendar.list" in partialIds)
        assertFalse("meetup.create" in partialIds)
    }

    @Test
    fun `a rules call by registry id resolves to its product tool`() {
        val registry = listOf("reminder.add", "memory.save", "geo.search", "meetup.create").map(::fakeTool)
        val toolset = ProductToolset(registry, ProductSources())

        assertEquals("reminder.set", toolset.resolve("reminder.add")?.id)
        assertEquals("reminder.set", toolset.resolve("reminder.set")?.id)
        assertEquals("memory.remember", toolset.resolve("memory.save")?.id)
        assertEquals("place.search", toolset.resolve("geo.search")?.id)
        assertEquals("meetup.create", toolset.resolve("meetup.create")?.id)
        assertEquals("day.today", toolset.resolve("day.today")?.id)
        assertNull(toolset.resolve("pay.draft"))
        assertNull(toolset.resolve("reminder.remove"))
        assertNull(toolset.resolve("nope"))
    }

    @Test
    fun `the product id keeps the consent the backing tool asks for`() {
        val outward = object : Tool by fakeTool("people.draft") {
            override val risk: ToolRisk = ToolRisk.OUTWARD
            override val askEveryCall: Boolean = true
        }
        val toolset = ProductToolset(listOf(outward, fakeTool("reminder.add")), ProductSources())

        val draft = toolset.resolve("people.draft")!!
        assertEquals(ToolRisk.OUTWARD, draft.risk)
        assertTrue(draft.askEveryCall)
        assertEquals(Approval.PROPOSAL, toolset.approvalOf(draft.id))
        assertEquals(ToolRisk.READ, toolset.resolve("reminder.add")!!.risk)
    }

    @Test
    fun `the Nightpost chat builds its tools from ProductToolset`() {
        val managerSrc = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt").readText()
        val turnsSrc = File(repoRoot(), "app/src/main/java/local/phantom/companion/ui/messenger/DialogueTurns.kt").readText()

        assertTrue(
            "AgencySessionManager must wire buildProductToolset(messengerToolset(container.toolRegistry.tools.value))",
            "buildProductToolset(messengerToolset(container.toolRegistry.tools.value))" in managerSrc,
        )
        assertFalse(
            "DialogueTurns must not retain MESSENGER_TOOL_IDS",
            "MESSENGER_TOOL_IDS" in turnsSrc,
        )
    }
}
