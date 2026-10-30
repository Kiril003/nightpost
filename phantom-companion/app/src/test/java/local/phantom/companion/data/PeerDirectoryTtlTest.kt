package local.phantom.companion.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * СТРОК ІМЕНІ В ДОВІДНИКУ — ЗВІРЕНИЙ, А НЕ НАМАЛЬОВАНИЙ.
 *
 * `untilSeconds` лежав у налаштуваннях і не звірявся з годинником ніколи: рядок
 * показував «@name · до 3 жовтня» і четвертого жовтня теж, дії «опублікувати
 * знову» не існувало, а сам рядок після спливу лишався некликабельним. Тобто
 * власник бачив опубліковане імʼя, якого в довіднику вже не було, і не мав чим
 * це полагодити.
 *
 * GATE LAW: половина цього сторожа читає ДЖЕРЕЛО файлом, повз up-to-date
 * Gradle — на валі запускати з `--rerun`.
 */
class PeerDirectoryTtlTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-net").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with core-net/ not found") as Nothing
    }

    private fun read(path: String): String {
        val f = File(repoRoot(), path)
        assertTrue("сторож осліп: немає $path", f.isFile)
        return f.readText()
    }

    private val entry =
        "app/src/main/java/local/phantom/companion/ui/settings/DirectoryHandleEntry.kt"

    @Test
    fun `нуль — це не «строк вийшов»`() {
        assertFalse(
            "неопублікованому імені приписали сплив",
            PeerDirectoryStore.expired(0L, 4_000_000_000_000L),
        )
    }

    @Test
    fun `строк у майбутньому живий, у минулому — ні`() {
        val nowMs = 1_800_000_000_000L
        assertFalse(PeerDirectoryStore.expired(nowMs / 1000 + 60, nowMs))
        assertTrue(
            "запис пережив власний строк і рядок про це мовчить",
            PeerDirectoryStore.expired(nowMs / 1000 - 1, nowMs),
        )
    }

    @Test
    fun `рядок справді звіряє строк, а не показує дату`() {
        val body = read(entry)
        assertTrue(
            "строк намальовано як напис і ніколи не звірено з годинником",
            body.contains("PeerDirectoryStore.expired("),
        )
    }

    @Test
    fun `після строку рядок знову стає дією`() {
        val body = read(entry)
        assertTrue("немає дії «опублікувати знову»", body.contains("R.string.dir_row_republish"))
        // Ключове: умова недоступності мусить робити виняток для спливу,
        // інакше слово є, а дії немає.
        assertTrue(
            "рядок лишився мертвим після строку — слово без дії",
            body.contains("if (busy || (name != null && !expired))"),
        )
        listOf(
            "app/src/main/res/values-uk/strings.xml",
            "app/src/main/res/values/strings.xml",
        ).forEach { path ->
            val strings = read(path)
            listOf("dir_row_republish").forEach {
                assertTrue("немає рядка $it у $path", strings.contains("\"$it\""))
            }
        }
    }

    @Test
    fun `слово про відмову не липне до наступного дотику`() {
        val body = read(entry)
        // Дві умови гасіння: зміна стану й власний строк слова. Досі не було
        // жодної — напис про відмову затуляв справжній стан рядка доти, доки
        // людина сама кудись не тицьне.
        assertTrue(
            "напис про відмову не гасне на зміні стану",
            body.contains("LaunchedEffect(handle, until) { notice = null }"),
        )
        assertTrue(
            "напис про відмову не має власного строку",
            body.contains("LaunchedEffect(notice)") && body.contains("NOTICE_LIFE_MS"),
        )
    }

    @Test
    fun `діалог публікації перевіряє normaliseForPublish і розсилає кадр профілю`() {
        val body = read(entry)
        assertTrue(
            "кнопка збереження має перевіряти normaliseForPublish (≥ 8 знаків)",
            body.contains("PeerDirectory.normaliseForPublish(draft) != null"),
        )
        assertTrue(
            "після зміни ніка глашатай мусить оновити кадр профілю",
            body.contains("container.profileHerald.bump()"),
        )
    }
}
