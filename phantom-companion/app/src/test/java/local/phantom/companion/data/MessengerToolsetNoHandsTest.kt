package local.phantom.companion.data

import java.io.File
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import local.phantom.companion.core.tool.impl.AccessibilitySequenceOutcome
import local.phantom.companion.core.tool.impl.AccessibilityTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProductToolset (A1) ще немає: сторож стоїть на функції, що сьогодні вибирає набір помічника месенджера. */
class MessengerToolsetNoHandsTest {

    private val hands = Regex("""(^|[._-])(tap|click|swipe|long_?press|gesture|app[._-]?action|accessibility|godmode|shell)([._-]|$)|^pc\.""")

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайдено корінь дерева з feature-stream/")

    private fun registeredIds(): Set<String> {
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        val classes = Regex("""core\.(?:tool\.impl|geo\.metro)\.(\w+Tool)\(""").findAll(factory).map { it.groupValues[1] }.toSet()
        assertTrue("реєстр без інструментів — фабрика переїхала?", classes.size > 20)
        val sources = listOf("core-symbiote/src/main", "core-geo/src/main").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        }
        return classes.mapNotNull { cls ->
            sources.firstNotNullOfOrNull { text ->
                val at = text.indexOf("class $cls(").takeIf { it >= 0 } ?: return@firstNotNullOfOrNull null
                Regex("""override val id\s*(?::\s*String)?\s*=\s*"([^"]+)"""").find(text, at)?.groupValues?.get(1)
            }
        }.toSet()
    }

    private fun stub(toolId: String): Tool = object : Tool {
        override val id = toolId
        override val description = toolId
        override val argsSchema = JsonObject(emptyMap())
        override val permissions = emptySet<ToolPermission>()
        override val risk = ToolRisk.READ
        override val streaming = false
        override val manifest = FeatureManifest(id = "tool.$toolId", tier = 1, displayName = toolId)
        override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult =
            ToolResult.Ok("ok", null, 0L)
    }

    @Test
    fun `the messenger toolset carries no taps, gestures, shell or pc ids`() {
        val real = devOnlyTools(debug = true) { false } + AccessibilityTool(
            parseActions = { emptyList() },
            dispatchSequence = { _, _ -> AccessibilitySequenceOutcome(0, 0) },
        )
        val all = real + (registeredIds() - real.map { it.id }.toSet()).map(::stub) + stub("pc.shell") + stub("screen.tap")
        val handIds = all.map { it.id }.filter { hands.containsMatchIn(it) }
        assertTrue("у реєстрі немає рук — сторож нічого не перевіряє: $handIds", "accessibility" in handIds && "shell" in handIds)

        val offered = messengerToolset(all).map { it.id }
        val leaked = offered.filter { hands.containsMatchIn(it) }
        assertTrue("руки в наборі помічника месенджера: $leaked", leaked.isEmpty())
        assertEquals("набір месенджера загубив звичайні інструменти", all.map { it.id }.toSet() - handIds.toSet(), offered.toSet())
    }

    @Test
    fun `the messenger offers and runs only that toolset`() {
        val dir = File(root, "app/src/main/java/local/phantom/companion/ui/messenger")
        val sources = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.associate { it.name to it.readText() }
        val raw = sources.mapValues { (_, s) -> Regex("""toolRegistry\.(tools|get\(|list\()""").findAll(s).count() }
        val wrapped = sources.mapValues { (_, s) -> Regex("""messengerToolset\(container\.toolRegistry\.tools\.value\)""").findAll(s).count() }
        assertTrue("месенджер більше не бере інструменти з реєстру — сторож застарів", raw.values.sum() > 0)
        assertEquals("месенджер бере інструменти повз messengerToolset", raw.filterValues { it > 0 }, wrapped.filterValues { it > 0 })
    }
}
