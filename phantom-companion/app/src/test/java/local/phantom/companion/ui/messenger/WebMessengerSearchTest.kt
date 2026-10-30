package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.solana.Cluster
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.PaymentFrame
import local.phantom.companion.core.solana.SolanaKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Пошук по всіх листах на вузлі (`GET /messenger/search`).
 *
 * Вектори згортання ДОСЛІВНО ті самі, що в `messageSearch.test.ts` вебу:
 * розійдуться — і телефон знайде за тим самим запитом інше, ніж веб.
 *
 * Червоніти вміє: злити ґ з г — падає вектор «ґанок»; прибрати ’ з
 * апострофів — падає «Сімʼя»; прибрати розгортання конверта групи — падає
 * тест про групу (конверт несе назву гілки в base64, і збіг у ньому не мусить
 * ставати знахідкою).
 */
class WebMessengerSearchTest {

    private val vectors = listOf(
        Triple("сімʼя", "Наша Сімʼя вдома", true),
        Triple("сімʼя", "сімʼя", true),
        Triple("СІМ`Я", "сімʼя", true),
        Triple("привіт", "ПРИВІТ усім", true),
        Triple("ппо", "пп" + "o", true),
        Triple("coк", "сок", true),
        Triple("kiev", "кіеv", true),
        Triple("ґанок", "ганок", false),
        Triple("ганок", "ґанок", false),
        Triple("иди", "іди", false),
        Triple("так", "tak", false),
        Triple("є", "е", false),
        Triple("кузні", "біля кузні о шостій", true),
        Triple("кузня", "біля кузні", false),
    )

    private fun line(id: String, body: String, at: Long = 1_000L, from: String = "p1", to: String = "me") =
        PeerMessage(id = id, fromId = from, toId = to, body = body, sentAtMs = at)

    private val file = "{\"blob_id\":\"" + "0".repeat(32) + "\",\"key_hex\":\"aa\"," +
        "\"sha256\":\"bb\",\"size\":10,\"mime\":\"application/pdf\",\"name\":\"Кузня-план.pdf\"," +
        "\"caption\":\"креслення \\\"ґанку\\\"\"}"

    @Test
    fun `згортання збігається з вебом`() {
        for ((q, text, hit) in vectors) {
            val found = WebMessengerSearch.indexOfFolded(text, WebMessengerSearch.foldQuery(q)) >= 0
            assertEquals("«$q» у «$text»", hit, found)
            assertEquals(text.length, WebMessengerSearch.fold(text).length)
        }
    }

    @Test
    fun `шукає текст, назву файлу й підпис — і лише в сказаному`() {
        val src = WebMessengerSearch.Source(
            "p1",
            listOf(
                line("m1", "збори біля кузні", at = 1),
                line("m2", file, at = 2),
                line("m3", "PH-SYS:кузня", at = 3), // службовий кадр — не слова людини
            ),
        )
        val r = WebMessengerSearch.search("КУЗН", listOf(src))
        assertEquals(listOf("m2", "m1"), r.hits.map { it.messageId })
        assertEquals(WebMessengerSearch.Field.FILE, r.hits[0].field)
        assertEquals(3, r.scanned)
        // Підпис з екранованими лапками знаходиться — кандидата не відсікає сирий JSON.
        val cap = WebMessengerSearch.search("\"ґанку\"", listOf(src))
        assertEquals(WebMessengerSearch.Field.CAPTION, cap.hits.single().field)
    }

    @Test
    fun `у групі шукається набране, а не конверт`() {
        val g = PeerGroup.Group(id = "g1", name = "Кузня", memberIds = listOf("me", "p1"))
        val lines = listOf(
            line("a", PeerGroup.encode(g, "привіт усім"), at = 1),
            line("b", PeerGroup.encode(g, "о шостій"), at = 2),
        )
        val src = WebMessengerSearch.Source("g1", lines) { WebMessengerGroups.said(it) }
        assertEquals(listOf("b"), WebMessengerSearch.search("шост", listOf(src)).hits.map { it.messageId })
        // «g1» стоїть у конверті кожного рядка, але людина його не писала.
        assertEquals(0, WebMessengerSearch.search("g1", listOf(src)).hits.size)
    }

    private fun card(what: PeerCard.Card, json: String) = PeerCard.encode(PeerCard.Body(what, json))!!

    private val meetAt = 1_793_000_000_000L
    private val cards = listOf(
        line("bill", card(PeerCard.Card.BILL, """{"v":1,"id":"b","venueId":"v","title":"Піца","token":"USDC","items":[{"id":"i1","name":"Маргарита","price":"12.5"},{"id":"i2","name":"Сік","price":"3"}],"payee":{"kind":"peer","id":"p1"},"people":[]}"""), at = 1),
        line("exp", card(PeerCard.Card.FAMILY_EXPENSE, """{"id":"fe","title":"Дача","currency":"₴","items":[{"id":"e1","title":"Дрова","amountUAH":420,"paidBy":"Оксана"},{"id":"e2","title":"Цвяхи","amountUAH":800}]}"""), at = 2),
        line("meet", card(PeerCard.Card.MEETUP, """{"v":1,"id":"m","venue":{"id":"v","name":"Кузня"},"at":$meetAt,"organizer":"p1","invitees":[],"title":"Вечеря"}"""), at = 3),
        line("pay", PaymentFrame(Cluster.DEVNET, Coin.USDC, 6_000_000, SolanaKey.of(ByteArray(32) { 1 })!!, "5" + "1".repeat(86)).encode(), at = 4),
    )

    @Test
    fun `картку знаходить за назвою, сумою з монетою й місцем — як веб, а не за сирим JSON`() {
        val src = WebMessengerSearch.Source("p1", cards)
        val find = { q: String -> WebMessengerSearch.search(q, listOf(src)).hits }
        assertEquals("Піца · 15,50\u00A0USDC", find("15,50").single().text)
        assertEquals(listOf("pay", "bill"), find("usdc").map { it.messageId })
        assertEquals("15.50\u00A0USDC", find("15.50").single().text.substringAfter(" · "))
        assertEquals("Дача · 1 220 ₴", find("1 220").single().text)
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(meetAt))
        assertEquals("Вечеря · Кузня · $time", find("кузня").single().text)
        assertEquals("6 USDC", find("6 usdc").single().text)
        assertEquals(WebMessengerSearch.Field.TEXT, find("вечеря").single().field)
        // Ключі JSON і назви полів людина не писала.
        assertEquals(0, find("amountUAH").size)
        assertEquals(0, find("organizer").size)
        assertEquals(0, find("Маргарита").size)
    }

    @Test
    fun `картка в групі знаходиться так само`() {
        val g = PeerGroup.Group(id = "g1", name = "Двір", memberIds = listOf("me", "p1"))
        val src = WebMessengerSearch.Source("g1", listOf(line("gb", PeerGroup.encode(g, cards[0].body)))) { WebMessengerGroups.said(it) }
        assertEquals("Піца · 15,50\u00A0USDC", WebMessengerSearch.search("піца", listOf(src)).hits.single().text)
    }

    @Test
    fun `межа видачі — найновіші, і сказано, що є ще`() {
        val src = WebMessengerSearch.Source("p1", (1..50).map { line("m$it", "слово $it", at = it.toLong()) })
        val r = WebMessengerSearch.search("слово", listOf(src), limit = 10)
        assertEquals(10, r.hits.size)
        assertEquals("m50", r.hits.first().messageId)
        assertTrue(r.truncated)
    }

    @Test
    fun `запит із мосту розбирається з кирилицею й пробілами`() {
        val (q, limit) = WebMessengerSearch.parse("q=%D1%81%D1%96%D0%BC%CA%BC%D1%8F%20%D0%B2%D0%B4%D0%BE%D0%BC%D0%B0&limit=7")
        assertEquals("сімʼя вдома", q)
        assertEquals(7, limit)
    }

    @Test
    fun `10 тисяч листів — швидше за кадр мосту`() {
        val words = listOf("збори", "кузня", "сімʼя", "Оксана", "привіт", "дорога", "ґанок", "шоста", "Kyiv", "хліб")
        val sources = (0 until 50).map { c ->
            WebMessengerSearch.Source(
                "c$c",
                (0 until 200).map { i ->
                    line("c${c}m$i", "${words[(i * 7 + c) % 10]} лист $i у розмові $c: ${words[(i + c * 3) % 10]} і ще трохи слів", at = i.toLong())
                },
            )
        }
        repeat(3) { WebMessengerSearch.search("кузн", sources) } // прогрів JIT
        val loadBefore = LoadGuard.load()
        var worst = 0L
        for (q in listOf("кузн", "сімʼя", "лист 19", "kyiv", "нема-такого")) {
            val t = System.nanoTime()
            WebMessengerSearch.search(q, sources)
            worst = maxOf(worst, System.nanoTime() - t)
        }
        println("[пошук вузла] 10k листів: найгірший запит ${worst / 1_000_000} мс")
        LoadGuard.assumeQuiet("пошук вузла", maxOf(loadBefore, LoadGuard.load()))
        assertTrue("найгірший запит ${worst / 1_000_000} мс", worst < 100_000_000L)
    }
}
