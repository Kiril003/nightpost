package local.phantom.companion.core.tool.impl

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CanvasToolsTest {

    private fun ctx(): ToolContext = ToolContext(
        appContext = android.app.Application(),
        operatorId = "op",
        requestId = "req",
        agentDepth = 1,
        approvedPermissions = emptySet(),
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, JsonPrimitive(v)) } }

    private val validSpec =
        """{"title":"бюджет","nodes":[{"n":"stat","label":"разом","value":"20 400"}]}"""

    @Before
    fun reset() {
        CanvasRegistry.clear()
        CanvasRegistry.bindOnScreen { null }
    }

    @Test
    fun `full build plans, delegates sections to sub-agents, then supervises`() = runTest {
        val roles = mutableListOf<String>()
        val tool = CanvasBuildTool(
            complete = { system, user ->
                when {
                    system.contains("ГОЛОВНИЙ архітектор") -> {
                        roles += "plan"
                        """{"title":"вихід","sections":[
                           {"title":"спорядження","brief":"перелік"},
                           {"title":"маршрут","brief":"кроки"}]}"""
                    }
                    user.contains("Ти ПІДАГЕНТ") -> {
                        roles += "section"
                        """{"nodes":[{"n":"text","text":"вузол розділу"}]}"""
                    }
                    else -> {
                        roles += "supervisor"
                        "ГОТОВО"
                    }
                }
            },
            newCanvasId = { "built1" },
        )
        val notices = mutableListOf<String>()
        val result = tool.execute(args("goal" to "план виходу"), ctx()) { ev ->
            if (ev is ToolEvent.Notice) notices += ev.message
        }

        assertTrue(result is ToolResult.Ok)
        result as ToolResult.Ok
        assertEquals(listOf("plan", "section", "section", "supervisor"), roles)
        assertTrue(notices.any { it.contains("підагент 1/2") })

        val payload = kotlinx.serialization.json.Json
            .parseToJsonElement(result.output).jsonObject["payload"]!!.jsonObject
        assertEquals("built1", payload["canvas_id"]!!.jsonPrimitive.content)
        assertEquals("1", payload["version"]!!.jsonPrimitive.content)
        // heading + node, per delegated section
        assertEquals(4, payload["nodes"]!!.jsonArray.size)
        assertEquals(
            "2",
            result.structured!!["delegated_sections"]!!.jsonPrimitive.content,
        )
        assertTrue(CanvasRegistry.get("built1")!!.contains("вузол розділу"))
    }

    @Test
    fun `a section the sub-agent could not build is drawn as a gap, not invented`() = runTest {
        val tool = CanvasBuildTool(
            complete = { system, user ->
                when {
                    system.contains("ГОЛОВНИЙ архітектор") ->
                        """{"title":"звіт","sections":[
                           {"title":"є дані","brief":"a"},{"title":"нема даних","brief":"b"}]}"""
                    user.contains("Ти ПІДАГЕНТ") && user.contains("нема даних") -> "вибач, не можу"
                    user.contains("Ти ПІДАГЕНТ") -> """{"nodes":[{"n":"text","text":"ок"}]}"""
                    else -> "ГОТОВО"
                }
            },
            newCanvasId = { "gap1" },
        )
        val result = tool.execute(args("goal" to "звіт"), ctx()) { }

        assertTrue(result is ToolResult.Ok)
        val spec = (result as ToolResult.Ok).output
        assertTrue(spec.contains("не зібрано"))
        assertTrue(spec.contains("нема даних"))
        assertEquals("1", result.structured!!["failed_sections"]!!.jsonPrimitive.content)
    }

    @Test
    fun `quick mode is one draft plus one supervision round`() = runTest {
        val calls = mutableListOf<String>()
        val tool = CanvasBuildTool(
            complete = { _, user -> calls += user; validSpec },
            newCanvasId = { "quick1" },
        )
        val result = tool.execute(args("goal" to "бюджет", "mode" to "quick"), ctx()) { }
        assertTrue(result is ToolResult.Ok)
        assertEquals(2, calls.size)
        assertTrue(calls[1].contains("СУПЕРВІЗОР"))
    }

    @Test
    fun `nothing parsable anywhere is an honest error, not an invented canvas`() = runTest {
        val tool = CanvasBuildTool(complete = { _, _ -> "ніякого JSON тут нема" })
        val result = tool.execute(args("goal" to "g"), ctx()) { }
        assertTrue(result is ToolResult.Err)
        assertEquals("bad_draft", (result as ToolResult.Err).code)
    }

    @Test
    fun `patch bumps version, keeps canvas_id and survives the verify pass`() = runTest {
        CanvasRegistry.put(
            "p1",
            """{"title":"звіт","version":2,"canvas_id":"p1","nodes":[{"n":"text","text":"x"}]}""",
        )
        val patched =
            """{"title":"звіт","nodes":[{"n":"text","text":"x"},{"n":"stat","value":"99"}]}"""
        val tool = CanvasPatchTool(complete = { _, user ->
            if (user.contains("СУПЕРВІЗОР")) "ГОТОВО" else {
                assertTrue(user.contains("звіт")) // current spec reaches the model
                patched
            }
        })
        val result = tool.execute(
            args("canvas_id" to "p1", "instruction" to "додай суму"),
            ctx(),
        ) { }

        assertTrue(result is ToolResult.Ok)
        val payload = kotlinx.serialization.json.Json
            .parseToJsonElement((result as ToolResult.Ok).output)
            .jsonObject["payload"]!!.jsonObject
        assertEquals("p1", payload["canvas_id"]!!.jsonPrimitive.content)
        assertEquals("3", payload["version"]!!.jsonPrimitive.content)
        assertEquals(2, payload["nodes"]!!.jsonArray.size)
    }

    @Test
    fun `patch delegates one section to a sub-agent and splices what comes back`() = runTest {
        CanvasRegistry.put(
            "d1",
            """{"title":"витрати","version":1,"canvas_id":"d1","nodes":[{"n":"text","text":"старе"}]}""",
        )
        var sawHandedBlock = false
        val tool = CanvasPatchTool(complete = { _, user ->
            when {
                user.contains("Ти ПІДАГЕНТ") -> """{"nodes":[{"n":"text","text":"нове"}]}"""
                user.contains("СУПЕРВІЗОР") -> "ГОТОВО"
                else -> {
                    sawHandedBlock = user.contains("Готовий блок від підагента")
                    """{"title":"витрати","nodes":[{"n":"text","text":"нове"}]}"""
                }
            }
        })
        val result = tool.execute(
            args("canvas_id" to "d1", "instruction" to "перероби", "section" to "їжа"),
            ctx(),
        ) { }

        assertTrue(result is ToolResult.Ok)
        assertTrue(sawHandedBlock)
        assertTrue((result as ToolResult.Ok).structured!!["delegated"]!!.jsonPrimitive.content == "true")
    }

    @Test
    fun `after a restart the canvas card in the stream is the identity`() = runTest {
        // Empty registry = the process died; the card is what persisted.
        CanvasRegistry.clear()
        CanvasRegistry.bindOnScreen { id ->
            if (id == null || id == "restored1") {
                """{"title":"мапа","version":3,"canvas_id":"restored1","nodes":[{"n":"text","text":"x"}]}"""
            } else null
        }
        val tool = CanvasPatchTool(complete = { _, user ->
            if (user.contains("СУПЕРВІЗОР")) "ГОТОВО"
            else """{"title":"мапа","nodes":[{"n":"text","text":"ширше"}]}"""
        })

        // No canvas_id at all — «зроби ширшим» right after a cold launch.
        val result = tool.execute(args("instruction" to "зроби ширшим"), ctx()) { }
        assertTrue(result is ToolResult.Ok)
        val payload = kotlinx.serialization.json.Json
            .parseToJsonElement((result as ToolResult.Ok).output)
            .jsonObject["payload"]!!.jsonObject
        assertEquals("restored1", payload["canvas_id"]!!.jsonPrimitive.content)
        assertEquals("4", payload["version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a canvas with no card and no registry entry is canvas_not_found`() = runTest {
        val tool = CanvasPatchTool(complete = { _, _ -> validSpec })
        val byId = tool.execute(args("canvas_id" to "ghost", "instruction" to "x"), ctx()) { }
        assertTrue(byId is ToolResult.Err)
        assertEquals("canvas_not_found", (byId as ToolResult.Err).code)

        val bare = tool.execute(args("instruction" to "зроби ширшим"), ctx()) { }
        assertTrue(bare is ToolResult.Err)
        assertEquals("canvas_not_found", (bare as ToolResult.Err).code)
        assertFalse(bare.message.contains("ghost"))
    }
}
