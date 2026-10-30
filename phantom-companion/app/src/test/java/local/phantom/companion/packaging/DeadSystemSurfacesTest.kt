package local.phantom.companion.packaging

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Поверхні поза вікном, що жили ще старим образом, — прибрані, а не залишені.
 *
 * До 04.10 застосунок оголошував три системні поверхні з до-v1.2 світу:
 * віджет «PHANTOM Vitals» (орб на градієнті, стоковий мікрофон Android),
 * віджет замкненого екрана «Familiar Status» і живі шпалери «PHANTOM Ambient»
 * (радіальне сяйво стану). Жодна не мала входу з нового інтерфейсу, але кожна
 * лежала в списку віджетів і шпалер телефона — тобто людина бачила саме їх
 * поруч із новим знаком. Замість переробки — видалено; новий віджет, якщо
 * буде, народиться з токенів.
 *
 * GATE LAW: читає вихідники як файли, повз перевірку актуальності Gradle.
 */
class DeadSystemSurfacesTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private val gone = listOf("VitalsWidgetProvider", "LockScreenWidgetProvider", "PhantomWallpaperService")

    private fun manifests(): List<File> =
        listOf(File(tree, "app/src/main/AndroidManifest.xml"), File(tree, "app/src/store/AndroidManifest.xml")) +
            tree.listFiles().orEmpty()
                .filter { it.isDirectory && (it.name.startsWith("core-") || it.name.startsWith("feature-")) }
                .map { File(it, "src/main/AndroidManifest.xml") }
                .filter { it.isFile }

    @Test
    fun `жоден маніфест не оголошує старі віджети й шпалери`() {
        val ms = manifests()
        assertTrue("маніфестів замало: ${ms.size}", ms.size >= 3)
        val left = ms.flatMap { f -> gone.filter { it in f.readText() }.map { "${f.relativeTo(tree)}: $it" } }
        assertTrue("лишились: $left", left.isEmpty())
    }

    @Test
    fun `нема ні класів, ні описувачів віджетів і шпалер`() {
        val left = tree.listFiles().orEmpty()
            .filter { it.isDirectory && (it.name == "app" || it.name.startsWith("core-") || it.name.startsWith("feature-")) }
            .flatMap { module ->
                File(module, "src/main").walkTopDown()
                    .filter { it.isFile }
                    .filter { f ->
                        f.nameWithoutExtension in gone ||
                            (f.extension == "xml" && f.parentFile.name == "xml" &&
                                Regex("<(appwidget-provider|wallpaper)\\b").containsMatchIn(f.readText()))
                    }
                    .map { it.relativeTo(tree).path }
                    .toList()
            }
        assertTrue("лишились: $left", left.isEmpty())
    }
}
