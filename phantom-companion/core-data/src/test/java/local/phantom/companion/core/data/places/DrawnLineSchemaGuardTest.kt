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
 * Намальовані лінії справді доїжджають до диска.
 *
 * Тут доводиться лише те, що Room МІГ БИ писати: метод доступу в базі й
 * таблиця у схемі, яку KSP експортує зі скомпільованих сутностей. Що хтось
 * справді пише — окреме питання, і його ставить [DaoReachabilityGuardTest]:
 * ця пара тверджень зеленіла й тоді, коли поруч лежала таблиця без писаря.
 */
class DrawnLineSchemaGuardTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-data").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-data/ not found") as Nothing
    }

    private val schemaDir: File by lazy {
        File(repoRoot(), "core-data/schemas/local.phantom.companion.core.data.PhantomDatabase")
    }

    private fun newestSchema(): Pair<Int, Set<String>> {
        val versions = schemaDir.listFiles()!!
            .mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
        val newest = versions.max()
        val tables = Json.parseToJsonElement(File(schemaDir, "$newest.json").readText())
            .jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray
            .map { it.jsonObject["tableName"]!!.jsonPrimitive.content }
            .toSet()
        return newest to tables
    }

    @Test
    fun `база оголошує доступ до ліній`() {
        val methods = PhantomDatabase::class.java.methods.map { it.name }.toSet()

        assertTrue(
            "drawnLineDao() зник із бази — намальовані лінії нікуди не пишуться",
            "drawnLineDao" in methods,
        )
    }

    @Test
    fun `остання експортована схема справді містить таблицю ліній`() {
        val (version, tables) = newestSchema()

        assertTrue(
            "у схемі v$version немає drawn_lines",
            "drawn_lines" in tables,
        )
    }

    @Test
    fun `сходинка міграцій доходить до версії, яку оголошує схема`() {
        val (version, _) = newestSchema()
        val top = PhantomDatabase.ALL_MIGRATIONS.maxOf { it.endVersion }

        // Прогалина тут означає «migration ... was required but not found» на
        // телефоні з попередньою збіркою — і руйнівний відкат бази.
        assertTrue(
            "схема на v$version, а сходинка міграцій закінчується на v$top",
            top >= version,
        )
    }
}
