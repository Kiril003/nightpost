package local.phantom.companion.ui.messenger

import java.io.File
import local.phantom.companion.core.net.peer.PeerCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * A56 10.10: група «Friday pho» з двома непрочитаними — другий рядок порожній.
 * Остання — картка «Витрати»: рід не «слово людини», тож `last_snippet` = null,
 * а назви й суми рядок не ніс зовсім. Тепер несе три пласкі поля.
 */
class WebMessengerCardLineTest {

    private val expense = PeerCard.encode(
        PeerCard.Body(
            PeerCard.Card.FAMILY_EXPENSE,
            """{"id":"fe_1","title":"Pho dinner","currency":"₴","items":[{"id":"a","title":"Pho and drinks","amountUAH":840,"category":"","paidBy":"Olena"}]}""",
        ),
    )!!

    @Test
    fun `рядок розмови з карткою витрат несе назву й суму`() {
        val row = WebMessengerKinds.rowFields(expense)
        assertEquals("widget:family-expense", row["last_kind"])
        assertEquals("Pho dinner", row["last_card_title"])
        assertEquals(840.0, row["last_card_total"] as Double, 0.001)
        assertEquals("₴", row["last_card_currency"])
        assertTrue("тіло картки протекло в рядок", row.values.none { it is String && it.contains("paidBy") })
    }

    @Test
    fun `опитування — питання, без суми`() {
        val poll = PeerCard.encode(PeerCard.Body(PeerCard.Card.VOTING, """{"id":"v","question":"Where to eat?","options":[]}"""))!!
        val row = WebMessengerKinds.rowFields(poll)
        assertEquals("Where to eat?", row["last_card_title"])
        assertNull(row["last_card_total"])
    }

    @Test
    fun `слово людини полів картки не має`() {
        val row = WebMessengerKinds.rowFields("Pho on Friday?")
        assertNull(row["last_card_title"])
    }

    @Test
    fun `рядок групи бере ті самі поля`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = File(dir ?: fail("корінь дерева не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val group = bridge.substringAfter("private fun groupConversation").substringBefore("private fun ")
        assertTrue("рядок групи не кладе назву й суму картки", group.contains("WebMessengerCardLine.row(fields)"))
    }

    private fun money(balances: String) = PeerCard.encode(
        PeerCard.Body(PeerCard.Card.MONEY, """{"v":1,"at":1,"wallet":true,"balances":[$balances],"week":[],"splits":[],"test":true}"""),
    )!!

    /** 29.10: рядок «Nightpost» після картки «Гроші» казав «Лист у форматі, якого ця збірка ще не знає». */
    @Test
    fun `рядок Nightpost з карткою — рід картки, а не сирий дріт`() {
        val (kind, snippet) = AssistantInput.listLine(money("""{"coin":"SOL","amount":"5"}"""))
        assertEquals("widget:money", kind)
        assertNull("сирий PH-CRD протік у рядок", snippet)
    }

    @Test
    fun `картка Гроші з одним балансом несе суму, з кількома — ні`() {
        val one = WebMessengerKinds.rowFields(money("""{"coin":"SOL","amount":"5"}"""))
        assertEquals(5.0, one["last_card_total"] as Double, 0.001)
        assertEquals("SOL", one["last_card_currency"])
        val two = WebMessengerKinds.rowFields(money("""{"coin":"SOL","amount":"5"},{"coin":"USDC","amount":"12"}"""))
        assertNull(two["last_card_total"])
    }

    @Test
    fun `рядок Nightpost бере поля картки`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = File(dir ?: fail("корінь дерева не знайдено") as Nothing, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        val phantom = bridge.substringAfter("private fun phantomConversation").substringBefore("private fun ")
        assertTrue("рядок Nightpost не кладе поля картки", phantom.contains("WebMessengerCardLine.row("))
    }

    private val gift = PeerCard.encode(
        PeerCard.Body(PeerCard.Card.GIFT, """{"v":1,"id":"g1","gift":"coffee","note":"За вчора"}"""),
    )!!

    @Test
    fun `подарунок — свій рід, а підпис не стає назвою рядка`() {
        val row = WebMessengerKinds.rowFields(gift)
        assertEquals("widget:gift", row["last_kind"])
        assertNull(row["last_card_title"])
        assertTrue("підпис подарунка протік у рядок", row.values.none { it is String && it.contains("За вчора") })
        assertTrue(WebMessengerCardLine.isGift(gift))
        assertFalse(WebMessengerCardLine.isGift(expense))
        assertFalse(WebMessengerCardLine.isGift("Подарунок"))
    }

    @Test
    fun `у шторці подарунок — «Подарунок», а не «Нова картка»`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        val app = File(root, "app/src/main/java/local/phantom/companion/data/AppContainer.kt").readText()
        val giftAt = app.indexOf("WebMessengerCardLine.isGift(notice.text)")
        assertTrue("шторка не питає, чи це подарунок", giftAt > 0)
        assertTrue("загальна «Нова картка» стоїть раніше за подарунок", giftAt < app.indexOf("PeerCard.isCard(notice.text)"))
        val word = Regex("""<string name="gift_notification">([^<]+)</string>""")
        assertEquals("Подарунок", word.find(File(root, "app/src/main/res/values-uk/strings.xml").readText())?.groupValues?.get(1))
        assertEquals("Gift", word.find(File(root, "app/src/main/res/values/strings.xml").readText())?.groupValues?.get(1))
        assertEquals("Dárek", word.find(File(root, "app/src/main/res/values-cs/strings.xml").readText())?.groupValues?.get(1))
    }
}
