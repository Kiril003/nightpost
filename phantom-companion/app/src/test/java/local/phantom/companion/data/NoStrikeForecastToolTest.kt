package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Межа безпеки: у реєстрі інструментів немає прогнозу ударів («Кассандра» з
 * `risk.assess` видавала «ймовірна загроза протягом 10–25 хв» із таблиці
 * сусідства областей). Сторож читає джерела, бо інструмент повертається не
 * тестом, а рядком у реєстрі.
 */
class NoStrikeForecastToolTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val forecast = listOf(
        Regex("""(?i)прогноз\w*\s+(удар|атак|обстріл|приліт|загроз)"""),
        Regex("""(?i)ймовірн\w*\s+(удар|атак|обстріл|приліт|загроз)"""),
        Regex("""(?i)вікн\w*\s+удар"""),
        Regex("""(?i)(predict|forecast)\w*\s*(strike|attack|threat|shelling)"""),
        Regex("""(?i)кассандр|cassandra"""),
        Regex(""""risk\.assess""""),
    )

    @Test
    fun `no tool or engine forecasts strikes`() {
        val root = repoRoot()
        val hits = listOf("app", "core-symbiote", "core-nav", "core-ai", "feature-stream")
            .flatMap { File(root, "$it/src/main").walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
            .flatMap { f ->
                f.readLines().mapIndexedNotNull { i, line ->
                    forecast.firstOrNull { it.containsMatchIn(line) }?.let { "${f.relativeTo(root)}:${i + 1}: ${line.trim()}" }
                }
            }
        assertEquals("прогноз ударів повернувся", emptyList<String>(), hits)
    }

    @Test
    fun `the removed theatre tools are not registered`() {
        val registry = File(repoRoot(), "app/src/main/java/local/phantom/companion/data/ToolRegistryFactory.kt").readText()
        assertTrue("реєстр переїхав", registry.contains("TrackExportTool("))
        listOf("PointRiskTool", "PatrolStartTool", "PatrolStatusTool", "SceneDirectTool", "MapTimelineTool").forEach {
            assertTrue("$it знову в реєстрі", !registry.contains("$it("))
        }
    }
}
