package local.phantom.companion.data

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.ai.AIChunk
import local.phantom.companion.core.ai.AIIntent
import local.phantom.companion.core.ai.ToolDefinition
import local.phantom.companion.core.ai.route.BrainChain
import local.phantom.companion.core.ai.route.OnDeviceToolRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Офлайн «Де найближче укриття?» не кликало жодного інструмента (збірка 16).
 *
 * Правила на телефоні кликали `map.nearest_shelter` і `map.route_live` — імена
 * КЛАСІВ, а не `Tool.id`. Реєстр їх не знав, `toolCall` мовчки відкидав виклик,
 * і людина отримувала «розуму немає». Сторож бере ідентифікатори з САМОГО
 * реєстру (`buildToolRegistry` + `override val id` у класах), а не з
 * переписаного вручну списку, — інакше тест і правила помилялись би разом.
 */
class OfflineToolRulesReachTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    /** `Tool.id` кожного класу, який `buildToolRegistry` справді кладе в реєстр. */
    private fun registeredIds(): Set<String> {
        val root = repoRoot()
        val factory = File(root, "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        val classes = Regex("""core\.tool\.impl\.(\w+Tool)\(""").findAll(factory).map { it.groupValues[1] }.toSet()
        assertTrue("реєстр без жодного інструмента — фабрика переїхала?", classes.size > 20)
        val sources = listOf("core-symbiote/src/main", "app/src/main").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.readText() }.toList()
        }
        return classes.mapNotNull { cls ->
            sources.firstNotNullOfOrNull { text ->
                val at = text.indexOf("class $cls(").takeIf { it >= 0 } ?: return@firstNotNullOfOrNull null
                Regex("""override val id\s*=\s*"([^"]+)"""").find(text, at)?.groupValues?.get(1)
            }
        }.toSet()
    }

    private fun offered(ids: Set<String>) = ids.map { ToolDefinition(it, "", JsonObject(emptyMap())) }

    private fun callFor(prompt: String, ids: Set<String>): AIChunk.ToolCall? = runBlocking {
        BrainChain(emptyList()).complete(AIIntent.Text(prompt, tools = offered(ids))).toList()
            .filterIsInstance<AIChunk.ToolCall>().singleOrNull()
    }

    @Test
    fun `offline the shelter question calls the registered shelter tool`() {
        val ids = registeredIds()
        val call = callFor("Де найближче укриття?", ids)
        assertTrue("без мережі й моделі питання про укриття не кликало інструмента", call != null)
        assertTrue("правила кликали «${call!!.name}», якого реєстр не знає", call.name in ids)
        assertEquals("shelter.nearest", call.name)
    }

    @Test
    fun `offline a route ask calls the registered route tool with a destination`() {
        val ids = registeredIds()
        val call = callFor("Як дістатися до Золотих воріт", ids)
        assertTrue("без мережі й моделі маршрут не кликав інструмента", call != null)
        assertTrue("правила кликали «${call!!.name}», якого реєстр не знає", call.name in ids)
        assertTrue(call.arguments, call.arguments.contains("Золотих воріт"))
    }

    @Test
    fun `every tool the rules can call is in the registry`() {
        val missing = OnDeviceToolRules.TOOL_IDS - registeredIds()
        assertTrue("правила кличуть інструменти, яких у реєстрі немає: $missing", missing.isEmpty())
    }

    /**
     * Памʼять PHANTOM відповідає ДО ланцюга. Без моделі вона казала «розуму
     * немає» на будь-що, тож до правил «де укриття» не доходило взагалі.
     * Перевірка на джерелі — бо будує її Android-контекст, якого тут немає.
     */
    @Test
    fun `the self answers through the rules, not instead of them`() {
        val src = File(repoRoot(), "app/src/main/java/local/phantom/companion/self/PhantomSelfAndroid.kt").readText()
        assertTrue(
            "PhantomSelfAndroid.build не передає правила інструментів у PhantomSelf (toolAsk)",
            Regex("""toolAsk\s*=\s*\w+::recognizes""").containsMatchIn(src) && src.contains("OnDeviceToolRules()"),
        )
    }
}
