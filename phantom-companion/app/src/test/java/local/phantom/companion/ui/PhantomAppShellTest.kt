package local.phantom.companion.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Полотно під графом — це полотно, а не «рушій настрою».
 *
 * До 04.10 PhantomAppShell роздавав шість «живих» локалей (акцент стану, тон
 * емоції, рівень мікрофона, подих, аура голосу, читання сховищ для карток) і
 * анімував їх на кожен кадр, хоча читачів у них уже не лишилось: орб, картки
 * й лічильник PTT пішли разом зі старим образом. Повернути будь-яку з них —
 * означає повернути барви, що змінюються за «настроєм», а v1.2 тримає одне
 * полотно й бурштин лише на дії.
 *
 * GATE LAW: читає вихідники як файли, повз перевірку актуальності Gradle.
 */
class PhantomAppShellTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): List<String> =
        File(tree, rel).readLines()
            .map { it.substringBefore("//").trim() }
            .filterNot { it.startsWith("*") || it.startsWith("/*") }

    @Test
    fun `оболонка дає лише мережу, без локалей настрою`() {
        val shell = code("app/src/main/java/local/phantom/companion/ui/PhantomAppShell.kt")
        val mood = listOf(
            "LocalStateAccent", "LocalEmotionTone", "LocalAudioLevel",
            "LocalBreathPhase", "LocalVoiceAura", "LocalDataSystems",
        )
        val left = mood.filter { name -> shell.any { name in it } }
        assertTrue("оболонка знову роздає настрій: $left", left.isEmpty())
        val provided = shell.flatMap { Regex("""(\w+) provides """).findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(listOf("LocalOnline"), provided)
        assertTrue("полотно — з токенів", shell.any { "LocalPhantomColors.current.canvas" in it })
    }

    @Test
    fun `MainActivity не качає тон емоції в оболонку`() {
        val main = code("app/src/main/java/local/phantom/companion/MainActivity.kt").joinToString("\n")
        assertTrue("EmotionToneParser повернувся в MainActivity", "EmotionToneParser" !in main)
        assertTrue("оболонка знову бере стан чи тон", Regex("""PhantomAppShell\s*\{""").containsMatchIn(main))
    }
}
