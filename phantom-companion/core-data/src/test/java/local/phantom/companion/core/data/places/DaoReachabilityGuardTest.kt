package local.phantom.companion.core.data.places

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.data.PhantomDatabase
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сторож ДОСЯЖНОСТІ сховищ, а не їхньої наявності.
 *
 * `TripPointStore`/`TripPointEntity`/`TripPointDao` були написані, оголошені в
 * базі, отримали свою таблицю міграцією — і не мали ЖОДНОГО викликача поза
 * `core-data`. Усе зеленіло: код компілювався, схема експортувалась, сторож
 * стверджував «метод у базі є». Жодне з тих тверджень не вміло почервоніти від
 * того, що в таблицю ніхто не пише.
 *
 * Тому питання тут інше: чи веде з ПРОДУКТОВОГО коду хоч один шлях до кожного
 * DAO. Доказ — не рефлексія по класу бази (вона показує лише, що метод
 * оголошено), а скан вихідників поза `core-data`: DAO, який нікому не потрібен,
 * не має жодного рядка `xxxDao()` у жодному `src/main`.
 */
class DaoReachabilityGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-data").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-data/ not found") as Nothing
    }

    /** Продуктові вихідники: усі модулі, крім самого `core-data`, без тестів. */
    private fun productSources(): List<File> {
        val root = repoRoot()
        val coreData = File(root, "core-data").absolutePath
        return root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" }
            .filter { !it.absolutePath.startsWith(coreData) }
            .filter { it.absolutePath.contains("/src/main/") }
            .toList()
    }

    private fun daoAccessors(): List<String> =
        PhantomDatabase::class.java.methods
            .map { it.name }
            .filter { it.endsWith("Dao") }
            .distinct()
            .sorted()

    @Test
    fun `кожне сховище, оголошене в базі, має викликача в продукті`() {
        val sources = productSources()
        assertTrue("скан не знайшов жодного .kt поза core-data — шлях зламано", sources.size > 100)

        val texts = sources.map { it.readText() }
        val orphans = daoAccessors().filter { dao ->
            // Межа зліва, щоб `taskDao()` не зарахувався за рахунок `subtaskDao()`.
            val call = Regex("(?<![A-Za-z0-9_])" + Regex.escape(dao) + "\\(\\)")
            texts.none { call.containsMatchIn(it) }
        }

        assertTrue(
            "DAO без жодного викликача поза core-data: ${orphans.joinToString()}. " +
                "Таблиця без писаря — це обіцянка без діла: або підʼєднати до " +
                "продуктового шляху, або прибрати разом зі схемою.",
            orphans.isEmpty(),
        )
    }

    @Test
    fun `точки виїзду не повертаються мертвими`() {
        val symbols = listOf("TripPointDao", "TripPointEntity", "TripPointStore")
        val root = repoRoot()
        val resurrected = root.walkTopDown()
            .onEnter { it.name != "build" && it.name != ".git" && it.name != ".gradle" && !it.name.startsWith(".") }
            .filter { it.isFile && it.extension == "kt" && it.name != "DaoReachabilityGuardTest.kt" }
            .filter { file -> file.readText().let { text -> symbols.any { it in text } } }
            .map { it.relativeTo(root).path }
            .toList()

        assertTrue(
            "сховище точок виїзду повернулось у ${resurrected.joinToString()} — " +
                "воно було прибране саме тому, що робочі зупинки веде " +
                "RoutePlanner.waypoints; друга таблиця тих самих точок означає " +
                "два джерела правди",
            resurrected.isEmpty(),
        )
    }

    @Test
    fun `остання експортована схема більше не містить trip_points`() {
        val schemaDir = File(
            repoRoot(),
            "core-data/schemas/local.phantom.companion.core.data.PhantomDatabase",
        )
        val newest = schemaDir.listFiles()!!
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            .max()
        val tables = Json.parseToJsonElement(File(schemaDir, "$newest.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()

        assertTrue(
            "у схемі v$newest знову є trip_points — сутність повернулась у @Database",
            "trip_points" !in tables,
        )
        assertTrue("у схемі v$newest зникла drawn_lines", "drawn_lines" in tables)
    }
}
