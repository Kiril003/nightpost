package local.phantom.companion.core.net.peer

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Межа приймання імені — ОДНА на весь продукт, і ріже її ніхто, крім показу.
 *
 * Меж було три (32/40/40), кожна в своєму файлі, і жодна не знала про решту.
 * Саме тому імʼя на 45 знаків пройшло одну й загинуло на другій. Числа, які
 * мають збігатись, але лежать нарізно, рано чи пізно розходяться — тож тут
 * стоїть сканер джерела, а не домовленість.
 *
 * ЗАКОН ВОРІТ (спільний з firewall-тестами core-design): сканування читає
 * ЧУЖІ модулі сирим файловим вводом, невидимим для перевірки актуальності
 * Gradle. Запускати з `--rerun` на кожних воротах хвилі.
 */
class PeerNameOneLimitTest {

    private val scanned = listOf("core-net/src/main", "feature-stream/src/main", "app/src/main")

    @Test
    fun `жодної другої межі імені в джерелі`() {
        val found = scanned.flatMap { kotlinIn(it) }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> DECLARATION.containsMatchIn(line) }
                    .map { (i, line) -> "${rel(file)}:${i + 1}  ${line.trim()}" }
            }
        if (found.isNotEmpty()) {
            fail(
                buildString {
                    appendLine("Друга межа імені живе окремо від PeerName.MAX (=${PeerName.MAX}):")
                    found.forEach { appendLine("  $it") }
                    appendLine()
                    appendLine("Полагодити: прибрати константу й брати PeerName.MAX;")
                    appendLine("прийняте імʼя — через PeerName.accept, набране — через PeerName.cap.")
                },
            )
        }
    }

    @Test
    fun `поверхні не рахують довжину імені самі`() {
        // Показ ріже під СВОЮ ширину штатним TextOverflow.Ellipsis з maxLines.
        // Власна арифметика на екрані — той самий мовчазний обріз, тільки без
        // диска: рядок обривається посеред слова й ніхто про це не каже.
        val ui = listOf(
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/people",
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map",
        )
        val found = ui.flatMap { kotlinIn(it) }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> ARITHMETIC.containsMatchIn(line) }
                    .map { (i, line) -> "${rel(file)}:${i + 1}  ${line.trim()}" }
            }
        assertEquals("арифметика над іменем на поверхні: $found", emptyList<String>(), found)
    }

    /**
     * ДВА ЧИСЛА, І ВОНИ ПРО РІЗНЕ. Наступний, хто побачить 64 і 32, спробує їх
     * «уніфікувати» — і зробить гірше, хай куди зведе.
     *
     * 64 — МЕЖА ПРИЙМАННЯ [PeerName.MAX]: скільки ми беремо від ЧУЖОГО
     * пристрою. Питання безпекове, число одне на весь продукт, і опускати його
     * до 32 означало б різати чужі імена там, де вони вміщаються.
     *
     * 32 — БЮДЖЕТ ВИВОДУ `CAP_QR_NAME` у ProfileTab: скільки ми друкуємо
     * САМІ. Питання фізичне — щільність QR, який зчитує камера сусіда (заміряно
     * в PeerInviteQrTest: 32 → версія 12, 64 → 14). Піднімати його до 64
     * означало б зробити власний код гіршим для всіх заради числа.
     */
    @Test
    fun `бюджет виводу названий, виміряний і вужчий за межу приймання`() {
        val screen = File(repoRoot(), PROFILE).readText()
        assertTrue("CAP_QR_NAME зник із Профілю", "CAP_QR_NAME = 32" in screen)
        assertTrue("бюджет більший за межу приймання", 32 < PeerName.MAX)
        // Вимір мусить лишитись ПОРУЧ із числом: без нього це просто константа,
        // яку наступний підніме, бо «а чому не 64». Фрази цілими, а не по «12»
        // і «14» — ті збігаються з `12.dp` і `14.dp` на цьому ж екрані, і
        // сторож лишався б зеленим на викинутому вимірі.
        listOf("32 кириличні літери — версію 12", "64 — версію 14").forEach {
            assertTrue("біля CAP_QR_NAME зник вимір «$it»", it in screen)
        }
    }

    @Test
    fun `власне імʼя теж не ріжеться мовчки`() {
        val screen = File(repoRoot(), PROFILE).readText()
        // Людина бачить межу ДО того, як упреться, і чує причину, коли вперлась.
        assertTrue("зник лічильник бюджету", "profile_name_budget" in screen)
        assertTrue("зникло слово про причину", "profile_name_too_long" in screen)
        // І не мовчазний зріз на збереженні.
        assertTrue("повернулась мовчазна арифметика", "it.take(field.cap)" !in screen)
    }

    @Test
    fun `бюджет відмовляє, а не ріже посеред знака`() {
        val family = String(Character.toChars(0x1F468)) + String(Character.toChars(0x200D)) +
            String(Character.toChars(0x1F469))
        val under = PeerName.fit(family.repeat(30), 32)
        assertEquals(30, under.used)
        assertTrue(!under.refused)

        val over = PeerName.fit(family.repeat(40), 32)
        assertTrue("відмову не названо", over.refused)
        assertEquals(32, over.used)
        assertEquals(family.repeat(32), over.text)
        assertEquals(32, PeerName.length(over.text))
    }

    @Test
    fun `межа названа в графемах, а не в байтах`() {
        // Межа в байтах ріже кирилицю вдвічі раніше за латиницю — тобто
        // українське імʼя коротше за англійське просто тому, що воно українське.
        val cyrillic = "я".repeat(PeerName.MAX)
        val latin = "a".repeat(PeerName.MAX)
        assertEquals(PeerName.accept(cyrillic).shortened, PeerName.accept(latin).shortened)
        assertTrue(PeerName.accept(cyrillic).name.length < cyrillic.toByteArray().size)
    }

    private fun kotlinIn(relPath: String): List<File> {
        val dir = File(repoRoot(), relPath)
        assertTrue("немає теки $relPath", dir.isDirectory)
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private fun rel(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath + "/")

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }

    private companion object {
        const val PROFILE =
            "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt"

        val DECLARATION = Regex("""\bMAX_NAME\s*=""")

        val ARITHMETIC = Regex(
            """[Nn]ame\.take\(|[Nn]ame\.substring\(|[Nn]ame\.dropLast\(|""" +
                """[Nn]ame\.length\s*[<>]""",
        )
    }
}
