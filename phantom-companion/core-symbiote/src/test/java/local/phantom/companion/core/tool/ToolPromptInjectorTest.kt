package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import local.phantom.companion.core.constitution.FeatureManifest
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolPromptInjectorTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeManifest(id: String) = FeatureManifest(
        id = id,
        tier = 14,
        displayName = "Test $id",
    )

    private fun makeTool(
        id: String,
        description: String,
        argsSchema: JsonObject,
    ): Tool = object : Tool {
        override val id = id
        override val description = description
        override val argsSchema = argsSchema
        override val permissions = emptySet<ToolPermission>()
        override val risk: ToolRisk = ToolRisk.READ
        override val streaming = false
        override val manifest = makeManifest(id)
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = ToolResult.Ok("ok", null, 0L)
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `two tools produce expected TOOLS block`() {
        val shellTool = makeTool(
            id = "shell",
            description = "run a shell command",
            argsSchema = JsonObject(mapOf("cmd" to JsonPrimitive("string"), "timeoutMs" to JsonPrimitive("number?"))),
        )
        val httpTool = makeTool(
            id = "http",
            description = "make an HTTP request",
            argsSchema = JsonObject(mapOf("url" to JsonPrimitive("string"), "method" to JsonPrimitive("string?"))),
        )

        val result = ToolPromptInjector.render(listOf(shellTool, httpTool))

        assertEquals(
            "[TOOLS: shell(cmd,timeoutMs)—run a shell command; http(url,method)—make an HTTP request; ]",
            result,
        )
    }

    @Test
    fun `empty list produces (none) block`() {
        val result = ToolPromptInjector.render(emptyList())
        assertEquals("[TOOLS: (none)]", result)
    }
}
