package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Мертве поле: чесне речення, вимкнене літералом.
 *
 * `CarCopy.emptyMessage(gazetteerSize, hadFix)` уміє сказати три різні речі, і
 * одна з них — «я ще не бачив цих місць» — існує саме для випадку, коли
 * довідник порожній. Донедавна обидва місця виклику подавали `gazetteerSize = 1`
 * **літералом**, тож ця гілка була недосяжна за побудовою: водій із порожнім
 * довідником читав «поруч такого немає», тобто продукт звинувачував місцевість
 * у тому, чого сам ніколи не завантажив. Вирок власника — «пошук місць не
 * спроможний ні на що» — накривав саме це.
 *
 * Сторож стереже КЛАС, а не рядок: він забороняє годувати це поле сталою.
 *
 * ЧОГО ВІН НЕ БАЧИТЬ: лише цей один параметр і лише в цьому модулі. Літерал,
 * захований у змінну поверхом вище, він не помітить — тут потрібне око, не
 * регулярний вираз. І він падає, якщо не знайшов жодного виклику: сторож, який
 * нічого не прочитав, мовчить так само, як сторож, у якого все гаразд.
 */
class EmptyMessageIsMeasuredTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-car").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева")
    }

    @Test
    fun `розмір довідника питають, а не вигадують`() {
        val src = File(repoRoot(), "feature-car/src/main/java/local/phantom/companion/feature/car")
        val calls = src.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { f ->
                Regex("""gazetteerSize\s*=\s*([^,\n)]+)""").findAll(f.readText())
                    .map { f.name to it.groupValues[1].trim() }
            }.toList()

        assertTrue(
            "жодного виклику emptyMessage не знайдено — сторож осліп, а не задоволений",
            calls.isNotEmpty(),
        )
        val literals = calls.filter { (_, arg) -> arg.toIntOrNull() != null }
        assertTrue(
            "розмір довідника поданий сталою — чесне речення про порожній довідник " +
                "стає недосяжним: ${literals.joinToString { "${it.first}: ${it.second}" }}",
            literals.isEmpty(),
        )
    }

    @Test
    fun `три стани порожнечі — три різні речення`() {
        val noFix = CarCopy.emptyMessage(gazetteerSize = 0, hadFix = false)
        val emptyCorpus = CarCopy.emptyMessage(gazetteerSize = 0, hadFix = true)
        val nothingNear = CarCopy.emptyMessage(gazetteerSize = 42_000, hadFix = true)
        assertEquals(3, setOf(noFix, emptyCorpus, nothingNear).size)
        assertTrue("порожній довідник мусить визнавати себе", emptyCorpus.contains("не бачив"))
        assertTrue("«нема поруч» не сміє звучати як «нема довідника»", nothingNear.contains("поруч"))
    }
}
