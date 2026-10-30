package local.phantom.companion.feature.stream.ui.map.indoor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Бейджі внутрішнього плану — слово, не піктограма.
 *
 * Кадр стенда 04.09 01:13 (Samsung A52, Острава): на вуличному шлагбаумі бейдж
 * «двері-емодзі Прохід». Мова форми: правда — словом, ніколи піктограмою; emoji —
 * лише операторський вміст. Слова живуть у `strings_indoor_badges.xml` (uk + en),
 * і цей сторож доводить їхню парність сам, бо модульні сторожі парності цей
 * файл не читають.
 */
class IndoorBadgeWordsTest {

    private companion object {
        const val XML = "strings_indoor_badges.xml"
        const val OVERLAY = "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/indoor/IndoorFloorOverlay.kt"
        /**
         * Символи-піктограми: емодзі (сурогатні пари), значки й dingbats, інші
         * символи — крім «№» (U+2116): це знак тексту («вхід №3»), не малюнок.
         */
        val PICTOGRAPH = Regex("[\\p{Cs}\\u2600-\\u27BF]|[\\p{So}&&[^\\u2116]]")
    }

    @Test
    fun `every badge word exists in both languages`() {
        val uk = strings("values-uk")
        val en = strings("values")
        assertTrue("словник порожній — сторож нічого не боронить", uk.isNotEmpty())
        assertEquals("ключі uk і en розійшлись — англійський телефон читав би план українською", uk.keys, en.keys)
        (uk.values + en.values).forEach { assertTrue("порожній ресурс малює ніщо", it.isNotBlank()) }
    }

    @Test
    fun `no badge word carries a pictograph`() {
        (strings("values-uk").values + strings("values").values).forEach {
            assertFalse("«$it» несе піктограму — це операторський вміст, не мова форми", PICTOGRAPH.containsMatchIn(it))
        }
    }

    @Test
    fun `ukrainian words are ukrainian`() {
        strings("values-uk").values.forEach { word ->
            assertTrue("«$word» — не українською", word.any { it in 'А'..'я' || it in "іїєґІЇЄҐ" })
        }
    }

    // ── Досяжність: накладка бере слово з ресурсу, і в її коді піктограм немає ──

    @Test
    fun `the overlay draws resource words and no pictographs`() {
        val f = File(repoRoot(), OVERLAY)
        assertTrue("IndoorFloorOverlay.kt не знайдено — сторож нічого не боронить", f.isFile)
        val src = f.readText()
        assertTrue("прохід не бере слово з ресурсу", src.contains("stringResource(badgeWord)"))
        assertTrue("вхід не бере слово з ресурсу", src.contains("stringResource(entranceWord)"))
        val hit = PICTOGRAPH.find(src)
        assertTrue("у накладці знову піктограма: «${hit?.value}»", hit == null)
    }

    private fun strings(dir: String): Map<String, String> {
        val file = File(repoRoot(), "feature-stream/src/main/res/$dir/$XML")
        assertTrue("$dir/$XML не знайдено", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.associate { it.getAttribute("name") to it.textContent }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }
}
