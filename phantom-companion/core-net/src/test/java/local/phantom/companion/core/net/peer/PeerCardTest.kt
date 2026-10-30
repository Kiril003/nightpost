package local.phantom.companion.core.net.peer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Картка композера доїжджає — і рівно тим родом, яким виїхала.
 *
 * Сторожі дивляться на РЕЗУЛЬТАТ кодувальника, а не на текст джерела: рід, який
 * «оголошено» в переліку, але якого дріт не впізнає, — це рівно та мовчазна
 * поразка, заради якої весь цей рід і зʼявився.
 */
class PeerCardTest {

    /** Тіло, схоже на справжнє: JSON із двокрапками, комами й кирилицею. */
    private val payload =
        "{\"id\":\"kanban_1\",\"title\":\"Вихід\",\"columns\":[{\"id\":\"todo\",\"cards\":[]}]}"

    @Test
    fun `кожен рід доїжджає тим самим родом і тим самим тілом`() {
        PeerCard.Card.entries.forEach { what ->
            val frame = PeerCard.encode(PeerCard.Body(what, payload))
            assertNotNull("рід ${what.name} не склався в кадр", frame)
            assertEquals(
                "рід ${what.name} доїхав іншим",
                PeerCard.Body(what, payload),
                PeerCard.decode(frame!!),
            )
        }
    }

    /**
     * Позначки мусять бути різні, і це не косметика: дві однакові означали б, що
     * дві картки з тринадцяти доїжджають одна замість одної — тобто Gantt
     * малюється дошкою, і ніхто не бачить, що сталось.
     */
    @Test
    fun `позначка й поле в кожного роду свої`() {
        assertEquals(
            "дві картки їдуть однією позначкою",
            PeerCard.Card.entries.size,
            PeerCard.Card.entries.map { it.mark }.distinct().size,
        )
        assertEquals(
            "дві картки читаються з одного поля веба",
            PeerCard.Card.entries.size,
            PeerCard.Card.entries.map { it.webField }.distinct().size,
        )
    }

    /**
     * Словник ЗАКРИТИЙ з обох боків.
     *
     * Рід, якого приймач не вміє намалювати, не має їхати «чимось схожим»: на
     * тому кінці це рамка з написом і порожнечею всередині — вигляд спроможності
     * без спроможності.
     */
    @Test
    fun `невідомий рід не їде й не читається`() {
        assertNull("невідоме слово веба отримало рід", PeerCard.Card.ofWeb("widget:gantt"))
        assertNull("невідоме слово веба отримало рід", PeerCard.Card.ofWeb("text"))
        assertNull("невідома позначка отримала рід", PeerCard.Card.of("zz"))
        // Кадр із чужою позначкою — це НІЩО, а не «картка невідомого роду»:
        // лист стає видимо зламаним, а не тихо порожнім.
        assertNull("чужа позначка розібралась", PeerCard.decode("PH-CRD:zz;aGk"))
    }

    @Test
    fun `порожнє й покалічене не стає карткою`() {
        assertNull("порожнє тіло поїхало", PeerCard.encode(PeerCard.Body(PeerCard.Card.SVG, "")))
        assertNull("кадр без тіла розібрався", PeerCard.decode("PH-CRD:sv;"))
        assertNull("кадр без розділювача розібрався", PeerCard.decode("PH-CRD:sv"))
        assertNull("не base64 розібрався", PeerCard.decode("PH-CRD:sv;!!!!"))
        assertNull("чужий кадр розібрався", PeerCard.decode("PH-RTE:1788;337"))
    }

    /**
     * Відмова стоїть ДО сокета. Кадр, зрізаний на дроті, не відмовляється — він
     * мовчки не доїжджає, і картка «просто не зʼявляється».
     */
    @Test
    fun `завелика картка відмовляє тут, а не на дроті`() {
        // base64 росте на третину, тож сире тіло рівно під стелею й трохи над нею.
        val fits = "x".repeat(PeerCard.MAX_CARD_CHARS / 4 * 3)
        val over = "x".repeat(PeerCard.MAX_CARD_CHARS / 4 * 3 + 4)
        assertNotNull(
            "картка під стелею не поїхала",
            PeerCard.encode(PeerCard.Body(PeerCard.Card.CANVAS, fits)),
        )
        assertNull(
            "картка над стелею поїхала — зріжеться на дроті й зникне мовчки",
            PeerCard.encode(PeerCard.Body(PeerCard.Card.CANVAS, over)),
        )
    }

    /**
     * Найгірший бік цього роду: він ДОХОДИТЬ до розмови. Рядок, набраний
     * пальцями, що починається з `PH-CRD:`, став би карткою, яку ніхто не
     * складав, — підробка походження рівно того ґатунку, проти якого стоїть
     * [PeerFrames.refuseTyped].
     */
    @Test
    fun `картку не можна набрати пальцями`() {
        val real = PeerCard.encode(PeerCard.Body(PeerCard.Card.VOTING, payload))!!
        assertEquals(PeerFrames.Kind.CARD, PeerFrames.refuseTyped(real))
        assertEquals(PeerFrames.Kind.CARD, PeerFrames.refuseTyped("  \n$real "))
        // Навіть позначка без придатного тіла не має проходити: інакше досить
        // набрати «PH-CRD:» з будь-яким хвостом, щоб лист поїхав кадром.
        assertEquals(PeerFrames.Kind.CARD, PeerFrames.refuseTyped("PH-CRD:zz;тут"))
    }

    /**
     * Картка — це сказане, а не вантаж: вона мусить дійти до розмови, інакше
     * поїде й не зʼявиться в жодній стрічці.
     */
    @Test
    fun `картка доходить до розмови`() {
        val frame = PeerCard.encode(PeerCard.Body(PeerCard.Card.CANVAS, payload))!!
        val letter = PeerMessage(
            id = "m", fromId = "them", toId = "me", body = frame, sentAtMs = 1_788_517_702_898L,
        )
        assertTrue("картка не дійшла до розмови", PeerOutbox.isSpoken(letter))
        assertFalse("картку сплутали з вкладенням", PeerFile.isAttachment(frame))
    }

    /**
     * Таблиця з «Що надіслати» їхала в нікуди: рід `table` дріт не знав, і
     * картка жила лише в стрічці автора. Тепер вона доїжджає цілою — з
     * кирилицею, гривнею й числами — і доходить до розмови.
     */
    @Test
    fun `таблиця доїжджає тим самим тілом і доходить до розмови`() {
        val table = "{\"id\":\"tb_1\",\"title\":\"Поїздка\",\"columns\":[{\"key\":\"c1\",\"label\":\"Сума (₴)\"," +
            "\"type\":\"number\"}],\"rows\":[{\"id\":\"r1\",\"c1\":3400}]}"
        val what = PeerCard.Card.ofWeb("table")
        assertEquals("рід таблиці дріт не впізнав", PeerCard.Card.TABLE, what)
        assertEquals("tableData", what!!.webField)
        val frame = PeerCard.encode(PeerCard.Body(what, table))!!
        assertEquals(PeerCard.Body(PeerCard.Card.TABLE, table), PeerCard.decode(frame))
        val letter = PeerMessage(id = "t", fromId = "them", toId = "me", body = frame, sentAtMs = 1_788_517_702_898L)
        assertTrue("таблиця не дійшла до розмови", PeerOutbox.isSpoken(letter))
    }

    /**
     * Зустріч і рахунок (збірка 18, `docs/scene/MEETUP_CONTRACT.md` §1).
     *
     * Поки дріт не знав цих родів, міст відмовляв `kind_not_carried`, і веб
     * чесно вимикав «Запросити». Тепер обидві картки їдуть — тим самим словом
     * веба, з тим самим полем і з тими позначками, що в договорі, — і тіло,
     * схоже на справжнє (кирилиця, десяткові рядки цін, uuid), доїжджає байт у
     * байт. Kotlin його не розбирає: форма належить вебу.
     */
    @Test
    fun `зустріч і рахунок їдуть словами договору й доходять до розмови`() {
        val meetup = "{\"v\":1,\"id\":\"8c1d6f0e-1a2b-4c3d-9e8f-0a1b2c3d4e5f\",\"venue\":{\"id\":\"v_cafe_x\"," +
            "\"name\":\"Кафе Ікс\",\"address\":\"Хрещатик, 1\",\"lat\":50.4501,\"lon\":30.5234,\"rating\":null," +
            "\"menuVersion\":3},\"at\":1791910800000,\"organizer\":\"kyrylo-fp\"," +
            "\"invitees\":[{\"id\":\"olena-fp\",\"name\":\"Олена\"}],\"expiresAt\":1791997200000}"
        val bill = "{\"v\":1,\"id\":\"b7e2a9c4-5d6e-4f70-8a9b-0c1d2e3f4a5b\",\"meetupId\":null,\"venueId\":\"v_cafe_x\"," +
            "\"title\":\"Кафе Ікс\",\"token\":\"USDC\",\"items\":[{\"id\":\"borshch\",\"name\":\"Борщ\",\"price\":\"7.5\"}]," +
            "\"payee\":{\"kind\":\"peer\",\"id\":\"kyrylo-fp\"},\"people\":[{\"id\":\"olena-fp\",\"name\":\"Олена\"}]}"
        listOf(
            Triple("widget:meetup", "mu", "meetupData") to meetup,
            Triple("widget:bill", "bl", "billData") to bill,
        ).forEach { (words, body) ->
            val (webType, mark, field) = words
            val what = PeerCard.Card.ofWeb(webType)
            assertNotNull("дріт не знає роду $webType — міст відмовить kind_not_carried", what)
            assertEquals("позначка $webType не та, що в договорі", mark, what!!.mark)
            assertEquals("поле $webType не те, з якого читає стрічка", field, what.webField)
            assertEquals(what, PeerCard.Card.of(mark))
            val frame = PeerCard.encode(PeerCard.Body(what, body))!!
            assertTrue("кадр $webType їде не своєю позначкою", frame.startsWith("PH-CRD:$mark;"))
            assertEquals("$webType доїхав іншим", PeerCard.Body(what, body), PeerCard.decode(frame))
            val letter = PeerMessage(id = "s", fromId = "them", toId = "me", body = frame, sentAtMs = 1_788_517_702_898L)
            assertTrue("$webType не дійшов до розмови", PeerOutbox.isSpoken(letter))
            assertEquals("$webType можна набрати пальцями", PeerFrames.Kind.CARD, PeerFrames.refuseTyped(frame))
        }
    }

    /** Картку Дня, погляду на людину чи грошей від людини приймач не кладе в журнал: це підробка фактів про нього. */
    @Test
    fun `day, glance and money cards are assistant-only, also inside a group letter`() {
        val day = PeerCard.encode(PeerCard.Body(PeerCard.Card.DAY, "{\"v\":1}"))!!
        val glance = PeerCard.encode(PeerCard.Body(PeerCard.Card.GLANCE, "{\"v\":1}"))!!
        val money = PeerCard.encode(PeerCard.Body(PeerCard.Card.MONEY, "{\"v\":1}"))!!
        val kanban = PeerCard.encode(PeerCard.Body(PeerCard.Card.KANBAN, payload))!!
        val group = PeerGroup.Group(id = "abc123abc123", name = "Двір", memberIds = listOf("a", "b"))
        assertTrue(PeerCard.isAssistantOnly(day))
        assertTrue(PeerCard.isAssistantOnly(glance))
        assertTrue(PeerCard.isAssistantOnly(money))
        assertTrue(PeerCard.isAssistantOnly(PeerGroup.encode(group, glance)))
        assertTrue(PeerCard.isAssistantOnly(PeerGroup.encode(group, money)))
        assertFalse(PeerCard.isAssistantOnly(kanban))
        assertFalse(PeerCard.isAssistantOnly(PeerGroup.encode(group, kanban)))
        assertFalse(PeerCard.isAssistantOnly("Що там з Олею?"))
    }
}
