package local.phantom.companion.feature.stream.ui.map

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ОДНА СЦЕНА, ОДИН ВЛАСНИК.
 *
 * NAV-0 виводив `MapScene` зі стану екрана; ATLAS-2 привів `SceneMode`, який
 * виводиться з ДІЯЛЬНОСТІ. Якийсь час обидві жили поруч, і це не було
 * подвоєнням заради сумісності — це були дві правди з перекритих сигналів:
 * густота питала «що людина робить», хром питав «що відкрито». Розходились
 * вони не в теорії, а рівно в сценарії цієї хвилі (пів хвилини без дотику з
 * відкритою панеллю: одна каже спокій, друга — огляд).
 *
 * Тому тут більше нема таблиці переходів — вона переїхала до
 * `atlas/SceneDeriverTest`. Тут лишився сторож проти повернення другої осі:
 * поведінку тепер боронить деривація, а цей файл боронить те, що деривація
 * ОДНА. Правило текстове свідомо — другу сцену не видно з типів, її видно з
 * того, що хтось знову почав рахувати сцену в іншому місці.
 */
class MapSceneStateTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun mapSources(): List<File> =
        File(repoRoot(), "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** Рядки коду — коментар має право згадувати мертву вісь як історію. */
    private fun codeLines(file: File): List<Pair<Int, String>> =
        file.readText().lineSequence().withIndex()
            .map { (i, line) -> i + 1 to line }
            .filterNot { (_, line) ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .toList()

    @Test
    fun `друга вісь сцени не повернулась`() {
        val offenders = mapSources().flatMap { file ->
            codeLines(file)
                .filter { (_, line) -> "MapScene" in line || "deriveMapScene" in line }
                .map { (n, line) -> "${file.name}:$n  ${line.trim().take(90)}" }
        }
        assertEquals(
            "друга сцена повернулась. Густоту й хром має живити ОДИН вивід — " +
                "`rememberSceneMode` у полотні, `ui.sceneMode` у хромі. Дві осі з " +
                "перекритих сигналів розходяться тихо й саме там, де це найдорожче.\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * Сторож мусить дивитись на живий екран, інакше він боронить порожнечу:
     * якщо хром перестане читати сцену взагалі, правило вище лишиться зеленим.
     */
    @Test
    fun `хром справді читає ту саму сцену, що й Лінза`() {
        val chrome = mapSources().single { it.name == "MapChrome.kt" }
        val reads = codeLines(chrome).count { (_, line) -> "ui.sceneMode" in line }
        assertTrue("MapChrome більше не читає сцену — сторож охороняє порожнечу", reads >= 3)

        val canvas = mapSources().single { it.name == "MapCanvas.kt" }
        val derives = codeLines(canvas).count { (_, line) -> "rememberSceneMode(" in line }
        assertEquals("сцену виводить рівно одне місце", 1, derives)
    }
}
