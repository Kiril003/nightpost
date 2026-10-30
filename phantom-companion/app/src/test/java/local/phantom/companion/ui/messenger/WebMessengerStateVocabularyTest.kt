package local.phantom.companion.ui.messenger

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * СЛОВНИК СТАНУ ЛИСТА ЗАКРИТИЙ — точніше слово тут не деградує, а бреше.
 *
 * Розбирач бандла (`Ge` в `assets/webmsgr/assets/index-*.js`) знає рівно три
 * слова — `queued`, `failed`, `sending` — а ВСЕ інше зсипає в гілку «інакше»,
 * яка каже `sent`. Тобто невідоме слово стає не «не знаю», а «надіслано».
 *
 * 12.09 міст віддав `PeerMessage.State.HELD -> "held"` — спроба сказати
 * точніше, ніж грубе «у черзі». Наслідок на склі: лист, який лежить
 * ЗАШИФРОВАНИЙ У СХОВКУ і адресата не бачив, отримав галочку «надіслано», а в
 * аркуші доставки групи — голе латинське `held` замість слова людською
 * (`A1[state] ?? state`, MessengerLayout-*.js). Обидві новини гірші за
 * згортання, яке та правка лікувала.
 *
 * Тому стережеться не «кожен стан має своє слово», а єдине, що тут важить:
 * **жоден стан, крім DELIVERED, не сміє прочитатись на склі як «надіслано»**.
 *
 * Точність, коли вона знадобиться, їде ОКРЕМИМ полем (`relay_state`,
 * `held_at`), якого старий бандл не читає й не псує, і РАЗОМ із бандлом, що це
 * поле читає. Сьогодні в бандлі нуль згадок і `held`, і `relay_state` — тож
 * нове поле було б заготовкою, а не контрактом.
 *
 * GATE LAW: читає бандл і джерело як текст. Дзеркалить ДВА оголошення одного
 * контракту — слово моста й розбір скла, — бо зелене на одному боці тут нічого
 * не доводить.
 */
class WebMessengerStateVocabularyTest {

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: fail("корінь дерева не знайдено") as Nothing
    }

    /** Слова, які міст кладе в `delivery_state`, — з джерела, а не з памʼяті. */
    private fun bridgeWords(): Map<String, String> {
        val src = File(
            repoRoot(),
            "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt",
        )
        assertTrue("сторож осліп: немає WebMessengerBridge.kt", src.isFile)
        val block = Regex(
            """private fun state\(s: PeerMessage\.State\): String = when \(s\) \{(.*?)\n    \}""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(src.readText())?.groupValues?.get(1)
            ?: fail("не знайшов state(): сторож більше не читає того, що стереже") as Nothing
        return Regex("""PeerMessage\.State\.(\w+)\s*->\s*"([^"]+)"""")
            .findAll(block)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    /** Тіло розбирача стану з бандла, що ЇДЕ в телефон. */
    private fun glassChain(): String {
        val assets = File(repoRoot(), "app/src/main/assets/webmsgr/assets")
        val js = assets.listFiles { f: File -> f.name.startsWith("index-") && f.name.endsWith(".js") }
            ?.sortedBy { it.name }
            ?: emptyList()
        assertEquals("у бандлі має бути рівно один index-*.js: ${js.map { it.name }}", 1, js.size)
        val text = js.first().readText()
        // Якір — смислове `"stored"`, а не мініфіковане імʼя: імʼя міняється на
        // кожній перезбірці веба, літерал — ні.
        val anchor = text.indexOf("!==\"stored\"")
        if (anchor < 0) {
            fail("у бандлі нема розбирача стану (якір \"stored\") — перечитай бандл і це дзеркало")
        }
        val start = text.lastIndexOf("{return", anchor)
        val end = text.indexOf('}', anchor)
        assertTrue("розбирач стану не вичитується з бандла", start in 0 until end)
        return text.substring(start + 1, end)
    }

    /**
     * Спершу — чи дзеркало ще дзеркалить. Якщо веб переписав розбір, зелене
     * нижче стало б зеленим ПРО ВЧОРАШНІЙ бандл.
     */
    @Test
    fun `розбирач стану в бандлі має ту форму, яку це дзеркало повторює`() {
        val chain = glassChain()
        assertTrue(
            "гілка «інакше» в бандлі більше не «sent» — перечитай `Ge` і перепиши дзеркало:\n$chain",
            chain.endsWith(":\"sent\""),
        )
        listOf("queued", "failed", "sending").forEach { word ->
            assertTrue(
                "бандл більше не впізнає «$word» — перечитай `Ge`:\n$chain",
                chain.contains("\"$word\""),
            )
        }
    }

    /** Що скло покаже, побачивши це слово. Точна копія гілок `Ge` для листа без вкладення. */
    private fun glassReads(word: String): String =
        if (word in setOf("queued", "failed", "sending")) word else "sent"

    @Test
    fun `міст називає кожен стан, який узагалі буває`() {
        val outbox = File(
            repoRoot(),
            "core-net/src/main/java/local/phantom/companion/core/net/peer/PeerOutbox.kt",
        )
        assertTrue("сторож осліп: немає PeerOutbox.kt", outbox.isFile)
        val declared = Regex("""enum class State \{([^}]+)\}""")
            .find(outbox.readText())?.groupValues?.get(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: fail("не знайшов enum State") as Nothing
        assertEquals(
            "стан зʼявився або зник — міст мовчить про нього, і скло прочитає «надіслано»",
            declared,
            bridgeWords().keys,
        )
    }

    /**
     * Те саме, що зловив би 12.09: єдиний стан, якому вільно читатись як
     * «надіслано», — DELIVERED.
     */
    @Test
    fun `лише доставлений лист читається на склі як надісланий`() {
        val liars = bridgeWords()
            .filterKeys { it != "DELIVERED" }
            .filterValues { glassReads(it) == "sent" }
            .map { "${it.key} -> \"${it.value}\"" }
        assertEquals(
            "міст віддає слово, якого бандл не знає, і скло ставить «надіслано» на лист, " +
                "що адресата не бачив: $liars.\n" +
                "Закритий словник не деградує мʼяко — точність їде ОКРЕМИМ полем " +
                "і разом із бандлом, який його читає.",
            emptyList<String>(),
            liars,
        )
    }

    /**
     * І чи вміє сторож почервоніти. Без цього його зелене не відрізнити від
     * зеленого сторожа, який просто нічого не розбирає.
     */
    @Test
    fun `сторож червоніє на точнішому слові, якого бандл не знає`() {
        val tempting = mapOf("HELD" to "held", "DELIVERED" to "delivered")
        val caught = tempting
            .filterKeys { it != "DELIVERED" }
            .filterValues { glassReads(it) == "sent" }
        assertEquals(
            "сторож не бачить рівно тієї правки, заради якої написаний",
            setOf("HELD"),
            caught.keys,
        )
        assertEquals("«queued» — грубе, але чесне: скло не назве його надісланим", "queued", glassReads("queued"))
    }
}
