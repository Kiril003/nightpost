package local.phantom.companion.feature.stream.ui.map.indoor

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import local.phantom.companion.feature.stream.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element

/**
 * Безіменна кімната внутрішнього плану не підписується сирим тегом.
 * Червоне на справжньому зразку: шість «parking» з кадру власника 01.09.
 */
class IndoorRoomLabelTest {

    private companion object {
        const val INDOOR_XML = "strings_indoor.xml"
    }

    @Test
    fun `a nameless parking is a shape, not a word`() {
        assertNull(indoorRoomLabel(name = null, category = "parking"))
        assertNull(indoorRoomLabel(name = "  ", category = "parking"))
    }

    @Test
    fun `no raw OSM value ever becomes a badge`() {
        listOf("shop", "clothes", "corridor", "room", "area", "yes", "retail", "storage").forEach {
            assertNull("сирий тег «$it» став підписом", indoorRoomLabel(null, it))
        }
    }

    @Test
    fun `a name always wins, trimmed`() {
        assertEquals(IndoorRoomLabel.Named("Сільпо"), indoorRoomLabel(" Сільпо ", "supermarket"))
    }

    @Test
    fun `landmarks people look for by kind get a word from resources`() {
        assertEquals(IndoorRoomLabel.Landmark(R.string.indoor_landmark_toilets), indoorRoomLabel(null, "toilets"))
        assertEquals(IndoorRoomLabel.Landmark(R.string.indoor_landmark_atm), indoorRoomLabel(null, "ATM"))
        assertEquals(IndoorRoomLabel.Landmark(R.string.indoor_landmark_pharmacy), indoorRoomLabel(null, " pharmacy "))
    }

    @Test
    fun `the dictionary speaks no English`() {
        // Слово для людини, не для мапера: кожен ресурс словника існує в
        // uk-наборі й написаний українською — жодного значення латиницею, крім WC.
        val uk = strings("values-uk")
        val words = dictionaryResourceNames()
        assertTrue("словник порожній — сторож нічого не боронить", words.isNotEmpty())
        words.forEach { name ->
            assertTrue("«$name» — ресурс не з цього словника", name.startsWith("indoor_landmark_"))
            val word = uk[name] ?: fail("values-uk/$INDOOR_XML не має $name") as Nothing
            if (word == "WC") return@forEach
            assertTrue(
                "«$word» ($name) — не українською",
                word.any { it in 'А'..'я' || it == 'і' || it == 'ї' || it == 'є' || it == 'ґ' },
            )
        }
    }

    // ── Ресурси: словник живе в окремому файлі, який модульні сторожі парності
    //    (StringResourceParityTest, TranslationParityFirewallTest) не читають —
    //    тож парність uk/en для нього доводиться тут. ──

    @Test
    fun `every landmark word exists in both languages and nowhere else`() {
        val uk = strings("values-uk")
        val en = strings("values")
        assertEquals("ключі uk і en розійшлись — англійський телефон читав би план українською", uk.keys, en.keys)
        assertEquals("ресурс без слова в словнику або слово без ресурсу", dictionaryResourceNames().toSet(), uk.keys)
        (uk.values + en.values).forEach { assertTrue("порожній ресурс малює ніщо", it.isNotBlank()) }
    }

    /** Імена ресурсів, на які вказує словник — через `R.string` навпаки, без Android Resources. */
    private fun dictionaryResourceNames(): List<String> {
        val names = R.string::class.java.fields.associate { it.getInt(null) to it.name }
        return LANDMARK_WORDS.values.toSet().map { id -> names[id] ?: fail("ресурс $id відсутній у R.string") as Nothing }
    }

    /** `res/<dir>/strings_indoor.xml` як імʼя → текст, прочитаний із самого XML. */
    private fun strings(dir: String): Map<String, String> {
        val file = File(repoRoot(), "feature-stream/src/main/res/$dir/$INDOOR_XML")
        assertTrue("$dir/$INDOOR_XML не знайдено", file.isFile)
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.documentElement.getElementsByTagName("string")
        return (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("repo root with feature-stream/ not found") as Nothing
    }

    // ── Досяжність: накладка читає саме це правило, а не `name ?: category` ──

    @Test
    fun `the overlay badge goes through the rule`() {
        val file = File(
            repoRoot(),
            "feature-stream/src/main/java/local/phantom/companion/feature/stream/ui/map/indoor/IndoorFloorOverlay.kt",
        )
        assertTrue("IndoorFloorOverlay.kt не знайдено — сторож нічого не боронить", file.isFile)
        val src = file.readText()
        assertTrue("накладка не кличе indoorRoomLabel", src.contains("indoorRoomLabel(room.name, room.category)"))
        assertTrue("старий шлях `room.name ?: room.category` повернувся", !src.contains("room.name ?: room.category"))
        assertTrue(
            "слово орієнтира не читається з ресурсів через stringResource",
            src.contains("is IndoorRoomLabel.Landmark -> stringResource("),
        )
    }
}
