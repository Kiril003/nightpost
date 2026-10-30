package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * This is a Ukrainian product for Ukrainian operators, several of whose
 * features exist because of the war. A Russian word in its interface is not a
 * typo the way a missing comma is a typo.
 *
 * On 2026-07-27 the council card read «Перевизначено вручную оператором» —
 * Russian «вручную» where Ukrainian is «вручну». One letter, and it had been
 * shipping.
 *
 * Ukrainian and Russian share most of the alphabet, so a general detector
 * would be guesswork. Four letters do not: ы, ъ, э, ё exist in Russian and
 * not in Ukrainian. That makes this cheap and free of false positives — it
 * cannot catch «вручную», but it cannot cry wolf either, and every hit it
 * does report is certain.
 *
 * The tree is clean as this lands. The test exists so it stays that way,
 * including through anything a model generates into a string.
 */
class RussianLeakFirewallTest {

    private val roots = listOf(
        "app/src/main",
        "core-ai/src/main",
        "core-symbiote/src/main",
        "core-voice/src/main",
        "core-data/src/main",
        "feature-stream/src/main",
        "feature-godmode/src/main",
        "feature-ambient/src/main",
        "feature-onboarding/src/main",
    )

    /** A double-quoted literal carrying a letter Ukrainian does not have. */
    private val russianOnly = Regex("\"[^\"\\n]*[ыъэё][^\"\\n]*\"", RegexOption.IGNORE_CASE)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    private fun kotlinFilesIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        if (!dir.isDirectory) {
            // ПЕРЕДУМОВА, а не дрібниця: якщо теки немає, обхід поверне порожньо,
            // «порушників» не буде — і сторож стане ЗЕЛЕНИМ ні про що саме тоді,
            // коли пакет перейменували чи перенесли, тобто в мить найімовірнішого
            // дрейфу. Мовчазна порожнеча тут гірша за відсутність сторожа.
            fail("сторож дивиться в порожнє: не існує $relPath")
        }
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // Друга передумова: тека може існувати й СПОРОЖНІТИ — вміст переїхав,
        // каталог лишився. Обхід тоді дає нуль файлів, порушників немає, і
        // сторож знову зелений ні про що. Найтихіший випадок із трьох.
        if (files.isEmpty()) fail("тека є, файлів немає: $relPath")
        return files
    }

    @Test
    fun `no string carries a letter Ukrainian does not have`() {
        val offenders = roots.flatMap { kotlinFilesIn(it) }.flatMap { f ->
            f.readText().lineSequence().withIndex()
                .filter { (_, line) ->
                    val t = line.trimStart()
                    !t.startsWith("//") && !t.startsWith("*") &&
                        russianOnly.containsMatchIn(line)
                }
                .map { (i, line) -> "${f.name}:${i + 1}  ${line.trim().take(100)}" }
        }

        assertTrue(
            "a string contains ы/ъ/э/ё — letters Ukrainian does not have:\n" +
                offenders.joinToString("\n") { "    $it" },
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the xml string resources are clean too`() {
        val offenders = roots.map { it.removeSuffix("/main") + "/main/res" }
            .map { File(repoRoot(), it) }
            .filter { it.isDirectory }
            .flatMap { dir ->
                dir.walkTopDown().filter { it.isFile && it.name == "strings.xml" }
                    .filter { it.parentFile.name != "values" }
                    .flatMap { f ->
                        f.readText().lineSequence().withIndex()
                            .filter { (_, line) -> Regex("[ыъэё]").containsMatchIn(line) }
                            .map { (i, line) -> "${f.parentFile.name}/${f.name}:${i + 1}  ${line.trim().take(100)}" }
                    }
            }

        assertTrue(
            "a string resource contains ы/ъ/э/ё:\n" + offenders.joinToString("\n") { "    $it" },
            offenders.isEmpty(),
        )
    }

    /**
     * ДВА ТЕСТИ ВИЩЕ БУЛИ ЗЕЛЕНІ Й СЛІПІ ВОДНОЧАС.
     *
     * 19.09 на Galaxy A52 TalkBack читав українцеві чотири російські рядки:
     * «Отображает карту, созданную при помощи MapLibre…», «Закрыть лист»,
     * «Маркер перемещения», «Компас. Активируйте, чтобы развернуть карту на
     * север». Жодного з них немає в нашому дереві — вони приїхали з `values-ru`
     * усередині aar MapLibre і Material3 і потрапили в APK на злитті ресурсів.
     * Сторож, який дивиться лише у свої файли, про чужі локалі не дізнається
     * ніколи.
     *
     * Читається `app/build.gradle.kts`, бо фільтр локалей — властивість
     * ПАКУНКА, а не файлів: `resourceConfigurations` вирізає всі локалі, крім
     * названих, з УСІХ залежностей одразу. Це єдине місце, де російську можна
     * зупинити, не полюючи на кожну нову бібліотеку окремо.
     */
    @Test
    fun `the package admits no locale but Ukrainian, English and Czech`() {
        val gradle = File(repoRoot(), "app/build.gradle.kts")
        assertTrue("немає app/build.gradle.kts", gradle.isFile)
        // Читає КОД, не коментарі. Перша редакція цього тесту лишалась зеленою
        // на закоментованому рядку — тобто саме на тому русі, який вона
        // існує, щоб зупинити.
        val code = gradle.readText().lineSequence()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        val declared = Regex("""resourceConfigurations\s*\+?=\s*listOf\(([^)]*)\)""")
            .find(code)
            ?.groupValues?.get(1)
            ?: fail(
                "у :app немає resourceConfigurations. Без нього кожна залежність " +
                    "везе власні локалі, і `values-ru` з MapLibre та Material3 " +
                    "читає TalkBack українцеві — саме це знайшли на апараті 19.09.",
            ) as Nothing
        val locales = Regex("\"([^\"]+)\"").findAll(declared).map { it.groupValues[1] }.toSet()
        assertTrue("фільтр локалей порожній", locales.isNotEmpty())
        assertTrue(
            "пакунок приймає локалі $locales. Дозволені лише uk, en та cs: будь-яка " +
                "третя відчиняє двері чужим `values-*` із aar.",
            locales == setOf("uk", "en", "cs"),
        )
    }

    /** Другий замок: наші власні `values-ru*` не мають існувати взагалі. */
    @Test
    fun `no russian resource folder exists in our own tree`() {
        val base = repoRoot().path + File.separator
        val found = roots.map { File(repoRoot(), "$it/res") }
            .filter { it.isDirectory }
            .flatMap { dir -> dir.listFiles().orEmpty().filter { it.name.startsWith("values-ru") } }
            .map { it.path.removePrefix(base) }

        assertTrue("наші власні російські ресурси: $found", found.isEmpty())
    }
}
