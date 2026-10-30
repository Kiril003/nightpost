package local.phantom.companion.core.data.poi

import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сторож національного довідника місць — і насамперед сторож ОДНОГО речення:
 * «де вода» мусить знаходити джерело, яке в OSM не має назви.
 *
 * Саме на цьому пак ламався. Київський `poi.json` викидає все безіменне, а по
 * країні 30% рядків цього корпусу — безіменні за природою: криниця, колонка,
 * джерело, брід. Тому тут перевіряється не «рядок розібрався», а «слово, яке
 * друкує людина, доходить до рядка, у якого немає назви».
 */
class OfflinePlacePackTest {

    private fun reader(vararg lines: String): BufferedReader =
        BufferedReader(StringReader(lines.joinToString("\n")))

    private fun one(line: String) = OfflinePlacePack.parseLine(line, 0)

    /** Файл цілком, як його читає піч→телефон: номер рядка йде з позиції. */
    private fun parseAll(lines: List<String>): List<PoiGazetteerEntity> =
        lines.mapIndexedNotNull { i, line -> OfflinePlacePack.parseLine(line, i.toLong()) }

    @Test
    fun `the header carries the bake epoch, so a re-seed costs one line`() {
        assertEquals(1788693978L, OfflinePlacePack.parseGenerated("generated=1788693978 count=176484"))
        assertEquals(0L, OfflinePlacePack.parseGenerated("junk"))
    }

    @Test
    fun `a five column row becomes a gazetteer place`() {
        val e = one("49.988100\t36.232800\tamenity\tdrinking_water\tПитна вода")
        assertNotNull(e)
        assertEquals(49.9881, e!!.lat, 1e-9)
        assertEquals(36.2328, e.lon, 1e-9)
        assertEquals("amenity", e.category)
        assertEquals("drinking_water", e.subcategory)
        assertEquals("Питна вода", e.name)
    }

    @Test
    fun `a six column row carries the OSM object it came from`() {
        val e = one("49.988100\t36.232800\tamenity\tpharmacy\tn11428891559\tПодорожник")!!
        assertEquals("Подорожник", e.name)
        assertEquals("pharmacy", e.subcategory)
        assertEquals("n11428891559", e.tags[OfflinePlacePack.REF_TAG])
        // 8·10^14 + вид 0 · 10^13 + номер вузла.
        assertEquals(800_011_428_891_559L, e.id)
    }

    @Test
    fun `each kind of OSM object gets its own corridor`() {
        val node = one("50.0\t30.0\tamenity\tfuel\tn7\tОККО")!!
        val way = one("50.0\t30.0\tamenity\tfuel\tw7\tОККО")!!
        val rel = one("50.0\t30.0\tamenity\tfuel\tr7\tОККО")!!
        assertEquals(800_000_000_000_007L, node.id)
        assertEquals(810_000_000_000_007L, way.id)
        assertEquals(820_000_000_000_007L, rel.id)
        // Вузол №7 і лінія №7 — різні обʼєкти OSM. В одному коридорі вони
        // отримали б один id і тихо перезаписали одне одного.
        assertEquals(3, listOf(node.id, way.id, rel.id).distinct().size)
    }

    /**
     * ТА САМА ВАДА, ЗАРАДИ ЯКОЇ ШОСТА КОЛОНКА, — і перевірка, що ЧЕРВОНІЄ на
     * файлі без неї.
     *
     * Сценарій дослівно той, який поставив архітектор: до місця прикріплено
     * факт, довідник перепечено з іншим порядком рядків, питання — чи факт
     * лишився на своєму місці.
     *
     * Перепікання тут справжнє в тому єдиному сенсі, який має значення: ті
     * самі обʼєкти, інший порядок. Саме так поводиться піч, коли міняється
     * ключ сортування, зʼявляється новий обʼєкт на початку країни або витяг
     * OSM приходить свіжішим.
     */
    @Test
    fun `a rebake in a different order must not move a single place`() {
        val withRef = listOf(
            "49.100000\t30.000000\tamenity\tpharmacy\tn111\tАптека А",
            "49.200000\t30.000000\tamenity\tpharmacy\tw222\tАптека Б",
            "49.300000\t30.000000\tamenity\tpharmacy\tn333\tАптека В",
        )

        val before = parseAll(withRef)
        val after = parseAll(withRef.reversed())
        val factOn = before.first { it.name == "Аптека А" }.id
        assertEquals(
            "перепік з іншим порядком рядків переставив id — факт поїхав на чуже місце",
            "Аптека А",
            after.first { it.id == factOn }.name,
        )
        assertEquals(
            "жоден рядок не сміє змінити id від перепікання",
            before.associate { it.name to it.id },
            after.associate { it.name to it.id },
        )

        // А тепер той самий корпус БЕЗ `ref` — тобто рівно той файл, що
        // лежав у гілці до цієї правки. Перевірка мусить упасти саме тут, і
        // цей assert стверджує, що вада була справжня, а не уявна.
        val withoutRef = withRef.map { line ->
            val p = line.split('\t')
            listOf(p[0], p[1], p[2], p[3], p[5]).joinToString("\t")
        }
        val legacyBefore = parseAll(withoutRef)
        val legacyAfter = parseAll(withoutRef.reversed())
        val legacyFactOn = legacyBefore.first { it.name == "Аптека А" }.id
        assertEquals(
            "без `ref` факт мусив поїхати на чуже місце — інакше опис вади застарів",
            "Аптека В",
            legacyAfter.first { it.id == legacyFactOn }.name,
        )
    }

    /**
     * Старий пʼятиколонковий асет читається й далі — але видимо інакше: без
     * [OfflinePlacePack.REF_TAG] і в окремому, порядковому коридорі. «Цей
     * рядок не має особи» мусить бути помітно і в тегах, і в самому числі.
     */
    @Test
    fun `a five column row admits it has no identity`() {
        val e = OfflinePlacePack.parseLine("50.0\t30.0\tamenity\tfuel\tОККО", idx = 5)!!
        assertNull(e.tags[OfflinePlacePack.REF_TAG])
        assertTrue(e.tags.isEmpty())
        assertEquals(830_000_000_000_005L, e.id)
        // Порядковий №7 і вузол OSM №7 — різні місця, і числа теж різні.
        val node = one("50.0\t30.0\tamenity\tfuel\tn5\tОККО")!!
        assertTrue("порядковий id накрив вузол OSM", node.id != e.id)
    }

    /**
     * Посилання, якому не можна вірити, не сміє лишити по собі тег: інакше
     * зшивач дублікатів звіряв би місця за ключем, якого номер не підтверджує.
     */
    @Test
    fun `a ref that cannot be trusted leaves no tag behind`() {
        for (bad in listOf("x123", "n", "nabc", "n0", "n-4", "n99999999999999")) {
            val e = OfflinePlacePack.parseLine(
                "50.0\t30.0\tamenity\tfuel\t$bad\tОККО",
                idx = 1,
            )!!
            assertNull("«$bad» лишив тег", e.tags[OfflinePlacePack.REF_TAG])
            assertEquals("«$bad» не відкотився в порядковий коридор", 830_000_000_000_001L, e.id)
        }
    }

    @Test
    fun `the live path and the pack name the same object the same way`() {
        assertEquals("n123", OfflinePlacePack.osmRef("node", 123))
        assertEquals("w123", OfflinePlacePack.osmRef("way", 123))
        assertEquals("r123", OfflinePlacePack.osmRef("relation", 123))
        // Відповідь без `type` — джерело не сказало виду; вигадувати нічого.
        assertNull(OfflinePlacePack.osmRef(null, 123))
        assertNull(OfflinePlacePack.osmRef("node", 0))
    }

    /**
     * ЦЕ І Є ХВОРОБА, ЗАРАДИ ЯКОЇ ХВИЛЯ. Людина друкує «вода», а рядок зветься
     * «Джерело». Без [PoiVocabulary] у колонці `keywords` пошук відповів би
     * порожнечею — і виглядало б це як «тут води нема», а не як «ми не вміємо
     * шукати».
     */
    @Test
    fun `a nameless spring is findable by the word a person types`() {
        val e = one("48.918000\t24.710000\tnatural\tspring\tДжерело")!!
        assertTrue("keywords=${e.keywords}", e.keywords.contains("вода"))
        assertTrue("keywords=${e.keywords}", e.keywords.contains("джерело"))
    }

    @Test
    fun `a well and a tap answer the same word`() {
        for (line in listOf(
            "50.100000\t30.100000\tman_made\twater_well\tКриниця",
            "50.100000\t30.100000\tman_made\twater_tap\tВодорозбірна колонка",
        )) {
            val e = one(line)!!
            assertTrue("${e.name}: ${e.keywords}", e.keywords.contains("вода"))
        }
    }

    /**
     * БРІД — знайомий, але не обіцяний. Рядок несе, що переправа ПОЗНАЧЕНА;
     * рівня води OSM не несе, і жодне слово тут не сміє натякати на проїзд.
     */
    @Test
    fun `a ford is findable and says only that it is marked`() {
        val e = one("48.500000\t24.500000\tford\tyes\tБрід")!!
        assertEquals("Брід", e.name)
        assertTrue(e.keywords.contains("брід"))
        for (promise in listOf("проїзн", "прохідн", "безпечн", "глибина")) {
            assertTrue(
                "рядок броду обіцяє «$promise»: name=${e.name} keywords=${e.keywords}",
                !e.name.contains(promise) && !e.keywords.contains(promise),
            )
        }
    }

    @Test
    fun `a malformed row is skipped, never a reason to drop the rest`() {
        assertNull(one("не рядок"))
        assertNull(one("50.0\t30.0\tamenity\tfuel"))          // чотири колонки
        assertNull(one("широта\t30.0\tamenity\tfuel\tАЗС"))   // не число
        assertNull(one("50.0\t30.0\t\tfuel\tАЗС"))            // без категорії
        assertNull(one("50.0\t30.0\tamenity\tfuel\t   "))     // порожня назва
        val chunks = OfflinePlacePack.chunkLines(
            reader(
                "50.0\t30.0\tamenity\tfuel\tОККО",
                "сміття",
                "50.1\t30.1\tnatural\tspring\tДжерело",
            ),
        ).toList()
        assertEquals(1, chunks.size)
        assertEquals(2, chunks.single().size)
    }

    /**
     * Синтетичні id не сміють накрити ані вулицю, ані місце, яке газетир бачив
     * живим: перезапис був би тихий і незворотний.
     */
    @Test
    fun `synthetic ids collide with nothing`() {
        val places = listOf(
            one("50.0\t30.0\tamenity\tfuel\tОККО")!!,                       // порядковий коридор
            one("50.0\t30.0\tamenity\tfuel\tn9999999999999\tОККО")!!,       // вузол під саму стелю виду
            one("50.0\t30.0\tamenity\tfuel\tr9999999999999\tОККО")!!,       // найдальший коридор із трьох
        )
        for (place in places) {
            assertTrue("id=${place.id}", place.id >= 800_000_000_000_000L)
            // Вулиці починаються на порядок вище: чотири коридори по 10^13 —
            // це 4·10^13 із 10^14 доступних, тобто зіткнення неможливе за
            // побудовою навіть коли всі чотири заповнені вщент.
            assertTrue("id=${place.id}", place.id < 900_000_000_000_000L)
        }
    }

    /**
     * ПЕРЕВІРКА НА СПРАВЖНЬОМУ АСЕТІ — та, що червоніє на файлі без `ref`.
     *
     * Усі попередні перевірки працюють на рядках, вигаданих тут-таки, тож
     * жодна з них не помітила б, що в APK поїхав старий, пʼятиколонковий
     * довідник. Ця читає той самий байт у байт файл, який ляже в застосунок
     * (робочий каталог модульного тесту — тека модуля, той самий прийом, що
     * й у `KyivMetroTest`), і питає в нього рівно одне: чи має тут кожне
     * місце свою особу.
     *
     * Якщо асет колись перепечуть старою піччю — тут стане червоно, а не
     * тихо.
     */
    @Test
    fun `every place in the shipped country pack has an identity of its own`() {
        val f = File("src/main/assets/country/ua/places.tsv")
        assertTrue("довідник місць мусить лежати за ${f.absolutePath}", f.exists())

        var rows = 0
        val refs = HashSet<String>()
        val ids = HashSet<Long>()
        f.bufferedReader(Charsets.UTF_8).use { reader ->
            reader.readLine() // заголовок `generated=… count=…`
            var idx = 0L
            while (true) {
                val line = reader.readLine() ?: break
                val e = OfflinePlacePack.parseLine(line, idx++) ?: continue
                rows++
                val ref = e.tags[OfflinePlacePack.REF_TAG]
                assertNotNull("рядок ${e.name} (${e.lat}, ${e.lon}) без `ref`", ref)
                assertTrue("«$ref» повторюється — два місця під одним ключем", refs.add(ref!!))
                assertTrue("id ${e.id} повторюється", ids.add(e.id))
            }
        }
        assertTrue("довідник підозріло малий: $rows рядків", rows > 100_000)
        // Жоден id не сміє дотягтись до коридору вулиць.
        assertTrue(ids.all { it >= 800_000_000_000_000L && it < 900_000_000_000_000L })
    }

    @Test
    fun `chunking never holds more than a chunk`() {
        val lines = (0 until 1201).map { "50.%04d\t30.0\tamenity\ttoilets\tТуалет".format(it % 9999) }
        val chunks = OfflinePlacePack.chunkLines(reader(*lines.toTypedArray()), chunkSize = 500).toList()
        assertEquals(listOf(500, 500, 201), chunks.map { it.size })
        assertEquals(1201, chunks.flatten().map { it.id }.distinct().size)
    }
}
