package local.phantom.companion.ui.messenger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * КАРТКИ КОМПОЗЕРА: ВМІСТ АБО СЛОВА, ТРЕТЬОГО НЕМАЄ.
 *
 * Тринадцять родів приїжджали вебові родом «інше» з порожнім тілом — рамка з
 * написом «Опитування» і нічим усередині. Тут перевіряється не те, що розбирач
 * працює, а те, що ЖОДЕН рід не лишився без одного з двох чесних кінців.
 */
class WebMessengerCardsTest {

    @Test
    fun `вміст опитування доїжджає до самої картки`() {
        val read = WebMessengerCards.of("PH-POLL:куди їдемо;ліворуч;праворуч")
        val card = read as WebMessengerCards.Read.Card
        assertEquals(WebMessengerCards.POLL, card.kind)
        assertEquals("куди їдемо", card.payload["question"])
        val options = card.payload["options"] as List<*>
        assertEquals(listOf("ліворуч", "праворуч"), options.map { (it as Map<*, *>)["text"] })
        // Веб кличе `voters.includes(...)` без перевірки на null: відсутній
        // перелік — не порожня картка, а падіння всієї стрічки.
        assertTrue("варіант без voters завалить рендер веба", options.all { (it as Map<*, *>)["voters"] is List<*> })
    }

    @Test
    fun `вміст рахунку, таблиці, коду й чек-листа доїжджає`() {
        val bill = WebMessengerCards.of("PH-BILL:вечеря;650;Марта:325:1;Тарас:325:0") as WebMessengerCards.Read.Card
        assertEquals(650.0, bill.payload["totalAmount"])
        val people = bill.payload["participants"] as List<*>
        assertEquals(listOf("Марта", "Тарас"), people.map { (it as Map<*, *>)["name"] })
        assertEquals(listOf(true, false), people.map { (it as Map<*, *>)["paid"] })

        val table = WebMessengerCards.of("PH-TABLE:кошторис;річ,ціна;пальне,900") as WebMessengerCards.Read.Card
        assertEquals(1, (table.payload["rows"] as List<*>).size)
        assertEquals(listOf("річ", "ціна"), (table.payload["columns"] as List<*>).map { (it as Map<*, *>)["label"] })

        // Крапка з комою всередині коду законна — і мусить повернутись на місце.
        val code = WebMessengerCards.of("PH-CODE:патч;kotlin;val x = 1; val y = 2") as WebMessengerCards.Read.Card
        assertEquals("val x = 1; val y = 2", code.payload["code"])

        val tasks = WebMessengerCards.of("PH-TASKLIST:план;розвідка:1:Марта;відхід:0") as WebMessengerCards.Read.Card
        assertEquals(listOf(true, false), (tasks.payload["tasks"] as List<*>).map { (it as Map<*, *>)["completed"] })
    }

    @Test
    fun billCurrencyNotHardcoded() {
        val bill = WebMessengerCards.of("PH-BILL:Dinner;45.5;A:20") as WebMessengerCards.Read.Card
        assertEquals(45.5, bill.payload["totalAmount"])
        assertEquals("", bill.payload["currency"])
    }

    @Test
    fun billKeepsSentCode() {
        val bill = WebMessengerCards.of("PH-BILL:Dinner;45.5 CZK;A:20") as WebMessengerCards.Read.Card
        assertEquals(45.5, bill.payload["totalAmount"])
        assertEquals("CZK", bill.payload["currency"])
    }

    /**
     * Покалічена позначка не стає карткою з підставленими значеннями.
     *
     * Рівно ті самі відмови, що на рідному екрані: опитування без питання,
     * рахунок без суми, графік без чисел, подія без дати. «Опитування» замість
     * питання — завершена картка над порожнечею; підставлені «18:30» читаються
     * як домовленість, якої ніхто не робив.
     */
    @Test
    fun `картка без свого головного поля не збирається`() {
        val crippled = listOf(
            "PH-POLL:;ліворуч",
            "PH-POLL:куди",
            "PH-BILL:вечеря;не число;Марта:325",
            "PH-CHART:темп;раз,два;росте",
            "PH-TABLE:кошторис;річ,ціна",
            "PH-TASKLIST:план",
            "PH-CODE:патч;kotlin",
            "PH-MULTIQUOTE:нарада",
            "PH-EVENT:виїзд;12.09",
        )
        crippled.forEach { body ->
            val read = WebMessengerCards.of(body)
            assertTrue("покалічена позначка зібралась у картку: $body", read !is WebMessengerCards.Read.Card)
            // І каже саме те, що сталось: «показувати нічого», а не «не вміємо
            // показати». Збірка вміє — приїхало порожнє.
            assertEquals(
                "покалічену картку названо невідомим родом: $body",
                WebMessengerCards.SAY_EMPTY,
                (read as WebMessengerCards.Read.Words).code,
            )
        }
    }

    @Test
    fun `рід без спроможності отримує слова, а не картку`() {
        val voice = WebMessengerCards.of("PH-VOICE:75;voice_1") as WebMessengerCards.Read.Words
        assertEquals(WebMessengerCards.SAY_VOICE, voice.code)
        assertEquals(listOf("1:15"), voice.args)

        // Імʼя файлу тут склала знесена театральна гілка. Повторити його
        // означало б видати вигадку за надіслане.
        listOf("PH-IMAGE:recon_point_1.jpg", "PH-FILE:plan.pdf;148KB").forEach { body ->
            val said = WebMessengerCards.of(body) as WebMessengerCards.Read.Words
            assertEquals(WebMessengerCards.SAY_BODILESS, said.code)
            assertTrue("назва з дроту протекла в речення: $body", said.args.isEmpty())
        }

    }

    /** Подія тепер картка: `EventCard` у вебі читає `eventData` поруч із `type==="event"`. */
    @Test
    fun `подія доїжджає карткою з назвою, днем, часом і місцем`() {
        val event = WebMessengerCards.of("PH-EVENT:виїзд;2026-11-01;07:00;міст") as WebMessengerCards.Read.Card
        assertEquals(WebMessengerCards.EVENT, event.kind)
        assertEquals("виїзд", event.payload["title"])
        assertEquals("2026-11-01", event.payload["date"])
        assertEquals("07:00", event.payload["time"])
        assertEquals("міст", event.payload["location"])
        assertEquals(setOf("title", "date", "time", "location"), event.payload.keys)
        val nowhere = WebMessengerCards.of("PH-EVENT:виїзд;2026-11-01;07:00") as WebMessengerCards.Read.Card
        assertEquals("", nowhere.payload["location"])
    }

    /** Подія, створена в групі, приходить до іншого учасника карткою, а не реченням. */
    @Test
    fun `подія з групи доїжджає до співрозмовника карткою`() {
        val group = local.phantom.companion.core.net.peer.PeerGroup.Group("g1", "Штаб", listOf("me", "marta"))
        val wire = local.phantom.companion.core.net.peer.PeerGroup.encode(group, "PH-EVENT:виїзд;2026-11-01;07:00;міст")
        val said = WebMessengerGroups.said(wire) ?: error("група не віддала сказане")
        val f = WebMessengerKinds.fields(said)
        assertEquals(WebMessengerCards.EVENT, f[WebMessengerKinds.KIND])
        assertEquals("виїзд", (f[WebMessengerKinds.CARD] as Map<*, *>)["title"])
        assertTrue("подія не сміє їхати реченням", f[WebMessengerKinds.NOTICE] == null)
        val bridge = File(WebMessengerBridgeSource.path()).readText()
        assertTrue("груповий рядок не бере роди з WebMessengerKinds", bridge.contains("?: return WebMessengerKinds.fields(said)"))
        assertTrue("тіло картки не їде JSON-ом", bridge.contains("(fields[WebMessengerKinds.CARD] as? Map<*, *>)?.let { return jsonOf(it).toString() }"))
    }

    /**
     * ДЗЕРКАЛО КОНТРАКТУ З БАНДЛОМ, ЩО ЇДЕ В ТЕЛЕФОН.
     *
     * Рід, якого веб не малює, — це знову порожня бульбашка, тільки під іншою
     * назвою. Тому перелік малювальних родів звіряється не з наміром, а з
     * САМИМ артефактом: у бандлі мусить бути гілка `u.type==="<рід>"`.
     *
     * «event» тут теж: `EventCard` малює подію, тож без гілки в бандлі вона
     * знову стала б порожньою бульбашкою.
     */
    @Test
    fun `кожен рід-картка має гілку в бандлі, що їде в телефон`() {
        val js = bundleText()
        val drawn = listOf(
            WebMessengerCards.POLL, WebMessengerCards.TABLE, WebMessengerCards.TASKS,
            WebMessengerCards.BILL, WebMessengerCards.CHART, WebMessengerCards.QUOTES,
            WebMessengerCards.CODE, WebMessengerCards.EVENT,
        )
        // Блок-подія сторінки закладу — не картка розмови, тож її з тексту знято.
        val chat = chatBundleText().replace(Regex("""blocks\.filter\(\(?([\w$]+)\)?=>\1\.type==="event"\)"""), "")
        val missing = drawn.filterNot { chat.contains("type===\"$it\"") }
        assertEquals(
            "міст віддає рід карткою, а бандл такої гілки не має — це знову порожня бульбашка: $missing",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Імена полів картки — теж контракт: `question` замість `text` дає ту саму
     * порожню картку, тільки повз усі попередні заслони.
     */
    @Test
    fun `поля кожної картки названі так, як їх читає бандл`() {
        val js = bundleText()
        val samples = listOf(
            "PH-POLL:куди;ліворуч",
            "PH-TABLE:кошторис;річ,ціна;пальне,900",
            "PH-TASKLIST:план;розвідка:1",
            "PH-BILL:вечеря;650;Марта:325:1",
            "PH-CHART:темп;січень:3",
            "PH-MULTIQUOTE:нарада;Марта:йдемо;Висновок:йдемо",
            "PH-CODE:патч;kotlin;val x = 1",
            "PH-EVENT:виїзд;2026-11-01;07:00;міст",
        )
        val unread = samples.flatMap { body ->
            val card = WebMessengerCards.of(body) as WebMessengerCards.Read.Card
            card.payload.keys.filterNot { js.contains(".$it") }.map { "${card.kind}.$it" }
        }
        assertEquals(
            "міст кладе в картку поле, якого бандл не читає — воно нічого не намалює: $unread",
            emptyList<String>(),
            unread,
        )
    }

    private fun bundleText(skip: String? = null): String {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val assets = File(dir ?: error("не знайшов корінь дерева"), "app/src/main/assets/webmsgr/assets")
        val js = assets.listFiles { f -> f.name.endsWith(".js") && (skip == null || !f.name.startsWith(skip)) }.orEmpty()
        // Нуль файлів означав би зелене без бандла — тобто заслін без предмета.
        check(js.isNotEmpty()) { "бандл веба відсутній у ${assets.path}" }
        return js.joinToString("\n") { it.readText() }
    }

    private object WebMessengerBridgeSource {
        fun path(): String {
            var dir: File? = File("").absoluteFile
            while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
            return File(dir ?: error("не знайшов корінь дерева"), "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").path
        }
    }

    /** Бандл без шматка конструктора сторінок: його блок «подія» — блок сторінки, не картка розмови. */
    private fun chatBundleText(): String = bundleText(skip = "BuilderPages-")
}
