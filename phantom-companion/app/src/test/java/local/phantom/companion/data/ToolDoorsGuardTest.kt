package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Одні ворота на всі двері до інструментів.
 *
 * Виміряно 12.09: дверей було ТРОЄ. `StreamToolRunner` питав ворота,
 * `AgencySessionManager` кликав `tool.execute` напряму — без воріт, сторожового
 * таймера, запобіжника й сліду, ще й сам собі видавав `approvedPermissions` —
 * а `AppContainer` мав третій шлях для меню світових шарів. Тобто розмова у
 * веб-месенджері виконувала що завгодно, поки Стрічка питала.
 *
 * Цей сторож ловить саме появу нових дверей. Він читає ДЖЕРЕЛА, бо перевіряє
 * властивість дерева, а не поведінку одного прогону.
 */
class ToolDoorsGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    /**
     * Єдині місця, де `tool.execute` дозволено кликати повз [ToolExecutor], і
     * чому. Додавання рядка сюди має бути свідомим рішенням, а не наслідком
     * зеленого прогону.
     */
    private val allowedDirectExecute = mapOf(
        "core-symbiote/src/main/java/local/phantom/companion/core/tool/ToolExecutor.kt"
            to "сам сторожовий шар — тут виклик і живе",
        "app/src/main/java/local/phantom/companion/data/AppContainer.kt"
            to "меню світових шарів: дотик оператора, і лише READ-інструменти (див. нижній тест)",
    )

    private fun mainSources(): List<File> = repoRoot().walkTopDown()
        .onEnter { it.name != "build" && it.name != ".git" && it.name != "test" }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.path.contains("/src/main/") }
        .toList()

    private fun rel(f: File): String = f.relativeTo(repoRoot()).path

    @Test
    fun `no new door calls tool execute directly`() {
        val offenders = mainSources().filter { f ->
            f.readText().lines().any { line ->
                Regex("""\btool\.execute\(|\bTool\.execute\(""").containsMatchIn(line)
            }
        }.map { rel(it) }

        val unexpected = offenders.filterNot { it in allowedDirectExecute }
        assertTrue(
            "нові двері повз ToolExecutor: $unexpected — проведи виклик через " +
                "ворота + ToolExecutor, або внеси сюди з причиною",
            unexpected.isEmpty(),
        )
    }

    @Test
    fun `the world-layer door summons only READ tools`() {
        val src = File(repoRoot(), "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        val block = src.substringAfter("WorldLayerSummoner").substringBefore("res is local.phantom")
        val summoned = Regex("""impl\.(\w+Tool)\(""").findAll(block).map { it.groupValues[1] }.toSet()
        assertTrue("меню не викликає жодного інструмента — блок переїхав?", summoned.isNotEmpty())

        val impl = File(repoRoot(), "core-symbiote/src/main/java/local/phantom/companion/core/tool/impl")
        summoned.forEach { cls ->
            val file = impl.walkTopDown().firstOrNull { it.isFile && it.readText().contains("class $cls") }
                ?: error("не знайшов клас $cls")
            val text = file.readText()
            val at = text.indexOf("class $cls")
            val tail = text.substring(at)
            val risk = Regex("""ToolRisk\.(READ|DEVICE|OUTWARD)""").find(tail)?.groupValues?.get(1)
            assertEquals(
                "$cls призначено меню світових шарів, а воно не питає згоди — " +
                    "туди можна лише READ",
                "READ", risk,
            )
        }
    }

    @Test
    fun `every runner of the executor also consults the gate`() {
        val runners = mainSources().filter { it.readText().contains("toolExecutor.run(") }
        assertTrue("жоден файл не кличе ToolExecutor — сторож осліп", runners.isNotEmpty())
        runners.forEach { f ->
            val text = f.readText()
            assertTrue(
                "${rel(f)} виконує інструменти, але не питає ворота",
                text.contains("GateOutcome") &&
                    (text.contains("toolGate") || text.contains("toolPermissionGate")),
            )
        }
    }

    /** 02.10 рідна стрічка пішла; модель говорить лише в розмові PHANTOM у месенджері. */
    @Test
    fun `the model sees exactly the registry`() {
        val agency = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/messenger/AgencySessionManager.kt",
        ).readText()
        val turns = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/messenger/DialogueTurns.kt",
        ).readText()
        assertTrue(
            "каталог для моделі мусить складатись із реєстру, а не з власного списку",
            agency.contains("messengerToolset(container.toolRegistry.tools.value)"),
        )
        assertTrue("каталог із реєстру складено, але в запит моделі не покладено", agency.contains("messengerTools,"))
        assertTrue("messengerIntent кладе в запит не той список", turns.contains("tools = all.map { ToolDefinition(it.id, it.description, it.argsSchema) },"))
    }

    // ── Негативні контролі ────────────────────────────────────────────────

    @Test
    fun `the direct-execute detector actually matches`() {
        val sample = "            val res = runCatching { tool.execute(args, ctx) {} }.getOrNull()"
        assertTrue(Regex("""\btool\.execute\(""").containsMatchIn(sample))
    }

    @Test
    fun `the detector does not match unrelated execute calls`() {
        val sample = "                runCatching { controller.execute(action) }.getOrElse { false }"
        assertTrue(!Regex("""\btool\.execute\(|\bTool\.execute\(""").containsMatchIn(sample))
    }

    @Test
    fun `the allowlist is not empty and each entry carries a reason`() {
        assertTrue(allowedDirectExecute.isNotEmpty())
        allowedDirectExecute.forEach { (path, why) ->
            assertTrue("$path без причини", why.length > 20)
            assertTrue("$path не існує — сторож застарів", File(repoRoot(), path).isFile)
        }
    }
}
