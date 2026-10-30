package local.phantom.companion.service

import androidx.compose.ui.graphics.toArgb
import java.io.File
import local.phantom.companion.core.design.tokens.NightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Шторка й рядок стану — в образі v1.2, з одного джерела.
 *
 * До 03.10 сповіщення фарбувались старою палітрою core-design (бурштин
 * #F4AF25, «корал», «зелень»), знаком був 108-dp шар адаптивної іконки (P на
 * 37,5 % висоти — удвічі менша за сусідів у рядку стану), а симбіот і
 * офлайн-мапа показували стокові значки Android. Цей тест тримає всі три
 * двері зачиненими.
 *
 * GATE LAW: читає вихідники як файли, повз перевірку актуальності Gradle.
 */
class NotificationLookTest {

    @Test
    fun `барви шторки — це токени v1_2, а не стара палітра`() {
        assertEquals(NightColors.accent.toArgb(), NotificationLook.accent)
        assertEquals(0xFFE2A048.toInt(), NotificationLook.accent)
        assertEquals(NightColors.danger.toArgb(), NotificationLook.danger)
        assertEquals(NightColors.success.toArgb(), NotificationLook.success)
    }

    @Test
    fun `тон людини в шторці той самий, що в чаті (toneIndex з formTokens_ts)`() {
        // Значення пораховано тією самою функцією у вебі (node, 04.10).
        val web = mapOf(
            "" to 1,
            "a" to 4,
            "9b10a1c2" to 4,
            "node-7f3e" to 1,
            "Марта" to 3,
            "02a4e1f0c9d84b77" to 0,
        )
        web.forEach { (key, tone) -> assertEquals("toneIndex(\"$key\")", tone, NotificationLook.toneIndex(key)) }
    }

    @Test
    fun `літера аватара — дзеркало avatarLetters`() {
        assertEquals("М", NotificationLook.letter("Марта Володимирівна"))
        assertEquals("Н", NotificationLook.letter("Ноутбук · 9b10·41af"))
        assertEquals("B", NotificationLook.letter("· bd6e"))
        assertEquals("Ґ", NotificationLook.letter("ґанок"))
        assertEquals("?", NotificationLook.letter("  ·· "))
    }

    @Test
    fun `у застосунку нема старої палітри, стокових значків і чужих знаків у рядку стану`() {
        val main = File(repoRoot(), "app/src/main")
        val sources = main.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.name == "AndroidManifest.xml") }
            .toList()
        assertTrue("вихідники app не знайдено", sources.size > 50)
        val offenders = mutableListOf<String>()
        val smallIcon = Regex("""setSmallIcon\(\s*([^)]*)\)""")
        for (file in sources) {
            val rel = file.relativeTo(main).path
            file.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                if ("PhantomPalette" in code) offenders += "$rel:${i + 1} стара палітра: $code"
                if ("android.R.drawable" in code || "@android:drawable" in code) {
                    offenders += "$rel:${i + 1} стоковий значок Android: $code"
                }
                smallIcon.findAll(code).forEach { m ->
                    if (m.groupValues[1].trim() != "NotificationLook.smallIcon") {
                        offenders += "$rel:${i + 1} знак у рядку стану не з NotificationLook: $code"
                    }
                }
            }
        }
        assertTrue(offenders.joinToString("\n"), offenders.isEmpty())
    }

    @Test
    fun `знак у рядку стану — Естафета Nightpost у мікрокрої, білий на прозорому`() {
        assertEquals(local.phantom.companion.R.drawable.ic_stat_nightpost, NotificationLook.smallIcon)
        val xml = File(repoRoot(), "app/src/main/res/drawable/ic_stat_nightpost.xml").readText()
        val brand = File(repoRoot().parentFile, "docs/brand/assets/android/res/drawable/ic_stat_nightpost.xml").readText()
        val path = Regex("""android:pathData="([^"]+)"""")
        assertEquals("розійшовся з бренд-китом", path.find(brand)?.groupValues?.get(1), path.find(xml)?.groupValues?.get(1))
        assertEquals("рядок стану бере лише альфу: заливка мусить бути білою", setOf("#FFFFFFFF"),
            Regex("""android:fillColor="([^"]+)"""").findAll(xml).map { it.groupValues[1] }.toSet())
        assertTrue("контур у значку рядка стану", "strokeColor" !in xml)
        assertEquals("24dp", Regex("""android:width="([^"]+)"""").find(xml)?.groupValues?.get(1))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева з feature-stream/ не знайдено") as Nothing
    }
}
