package local.phantom.companion.core.design.i18n

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Публічне імʼя продукту — **Nightpost** (docs/brand/BRAND.md §1). Слово
 * «PHANTOM» людина не бачить ніде: ні в списку програм, ні в шторці, ні в
 * каналах сповіщень у налаштуваннях телефона, ні в розмові з помічником.
 *
 * До збірки 16 воно стояло в 102 рядках ресурсів і в сотні літералів Kotlin —
 * у підказках «Налаштування → Застосунки → PHANTOM», яких після перейменування
 * `app_name` у телефоні вже не існувало б, у приписі «Ти — PHANTOM», від якого
 * помічник сам себе так називав, у назвах каналів «Фантом · стан».
 *
 * Код, пакети, ключі сховищ і теги журналу лишаються як є: їх людина не читає.
 * Не чіпаємо й рядки протоколу — домени HKDF («PHANTOM OS/peer-msg-v2»), мітку
 * блоку памʼяті, яку так само пише ПК (`service.py`): зміна будь-якого з них —
 * це новий протокол, а не перейменування. Вони перелічені поіменно в [wire],
 * щоб новий рядок із «PHANTOM» не прослизнув під їхнім прикриттям.
 *
 * GATE LAW (як в інших сторожів тут): читає чужі модулі сирим файловим I/O,
 * поза перевіркою актуальності Gradle. Ганяти з `--rerun-tasks`.
 */
class BrandNameFirewallTest {

    private val latin = Regex("""\b(PHANTOM|Phantom)\b""")
    private val cyrillic = Regex("""[Фф]антом|ФАНТОМ""")

    /** Рядки протоколу й спільні з ПК мітки — префікси літералів, які не змінюються ніколи. */
    private val wire = listOf(
        "PHANTOM OS/",
        "PHANTOM intent ",
        "PHANTOM answer ",
        "PHANTOM contact tag",
        "PHANTOM siren ",
        "PHANTOM/1",
        // Мітку блоку памʼяті обидва мізки (Ledger.kt і service.py ПК) пишуть
        // з ʼ від 10738e3a; ASCII-форми «[ПАМ'ЯТЬ» у коді більше немає.
        "[ПАМʼЯТЬ PHANTOM",
    )

    /**
     * Ресурси, яких людина не бачить. `people_ai_alias` — слово для пошуку
     * помічника (людина могла звикнути шукати «фантом»); на екран воно не
     * виходить.
     */
    private val unseenResources = setOf("people_ai_alias")

    private val body = Regex("""<(string|item)\b([^>]*)>(.*?)</\1>""", RegexOption.DOT_MATCHES_ALL)
    private val nameAttr = Regex("""name="([^"]+)"""")
    private val literal = Regex("\"\"\"(.*?)\"\"\"|\"((?:[^\"\\\\\\n]|\\\\.)*)\"", RegexOption.DOT_MATCHES_ALL)

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    /** Усі модулі з `src/`, крім `tools/` (збиральні утиліти, не застосунок). */
    private fun modules(root: File): List<File> {
        val found = root.listFiles().orEmpty().filter { File(it, "src/main").isDirectory }
        if (found.size < 20) fail("сторож бачить лише ${found.size} модулів — корінь не той")
        return found
    }

    @Test
    fun `no string resource shows the old product name`() {
        val root = repoRoot()
        val files = modules(root).flatMap { m ->
            File(m, "src").listFiles().orEmpty().map { File(it, "res") }.filter { it.isDirectory }
        }.flatMap { res ->
            res.listFiles().orEmpty()
                .filter { it.isDirectory && it.name.startsWith("values") }
                .flatMap { it.listFiles().orEmpty().filter { f -> f.extension == "xml" } }
        }
        if (files.size < 30) fail("сторож бачить лише ${files.size} файлів ресурсів — шлях не той")

        val offenders = files.flatMap { f ->
            body.findAll(f.readText()).mapNotNull { m ->
                val name = nameAttr.find(m.groupValues[2])?.groupValues?.get(1).orEmpty()
                val text = m.groupValues[3]
                val hit = latin.containsMatchIn(text) || cyrillic.containsMatchIn(text)
                if (!hit || name in unseenResources) null
                else "${f.relativeTo(root).path}  $name  «${text.trim().take(80)}»"
            }.toList()
        }

        assertEquals(
            "рядок ресурсу показує людині старе імʼя — продукт зветься Nightpost:\n" +
                offenders.joinToString("\n") { "    $it" },
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `no Kotlin literal speaks the old product name`() {
        val root = repoRoot()
        val offenders = modules(root).map { File(it, "src/main") }.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
                val text = f.readText()
                literal.findAll(text).mapNotNull { m ->
                    val s = m.groups[1]?.value ?: m.groups[2]?.value.orEmpty()
                    val lineStart = text.lastIndexOf('\n', m.range.first) + 1
                    val before = text.substring(lineStart, m.range.first).trimStart()
                    val inComment = before.startsWith("//") || before.startsWith("*") || before.startsWith("/*")
                    // Одиночне слово-псевдонім («фантом» у списку звертань) — не
                    // текст для людини; фраза з ним — текст.
                    val prose = latin.containsMatchIn(s) || (cyrillic.containsMatchIn(s) && ' ' in s.trim())
                    if (inComment || !prose || wire.any { s.startsWith(it) }) null
                    else "${f.relativeTo(root).path}:${text.substring(0, m.range.first).count { it == '\n' } + 1}  «${s.trim().take(80)}»"
                }
            }
        }

        assertEquals(
            "літерал Kotlin несе старе імʼя продукту — людина (чи помічник її вустами) його побачить:\n" +
                offenders.joinToString("\n") { "    $it" } +
                "\n\nПиши Nightpost. Якщо це справді рядок протоколу — додай його префікс у `wire` з причиною.",
            emptyList<String>(),
            offenders,
        )
    }
}
