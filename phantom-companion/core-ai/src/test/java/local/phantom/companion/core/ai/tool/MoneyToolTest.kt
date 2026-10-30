package local.phantom.companion.core.ai.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class MoneyToolTest {

    private data class Row(val phrase: String, val who: List<String>, val amount: String?, val coin: String?)

    private val table = listOf(
        Row("скинь Олені 5 USDC", listOf("олені"), "5", "usdc"),
        Row("передай Олені 5 USDC", listOf("олені"), "5", "usdc"),
        Row("переказати Кирилу 0.1 SOL", listOf("кирилу"), "0.1", "sol"),
        Row("send Olena 5 usdc", listOf("olena"), "5", "usdc"),
        Row("скинь Олені 5,5 usdc", listOf("олені"), "5.5", "usdc"),
        Row("скинь Олені п'ять USDC", listOf("олені"), "5", "usdc"),
        Row("скинь Олені пʼять USDC", listOf("олені"), "5", "usdc"),
        // Ключі числівників пишуться ʼ; людина набирає будь-який апостроф.
        Row("скинь Олені п’ять USDC", listOf("олені"), "5", "usdc"),
        Row("скинь Олені дев'ять USDC", listOf("олені"), "9", "usdc"),
        Row("скинь Олені пʼятсот пʼятдесят пʼять SKR", listOf("олені"), "555", "skr"),
        Row("надішли Олені Коваль двадцять п'ять доларів", listOf("олені", "коваль"), "25", "usdc"),
        Row("переказ Кирилу 0.000001 USDC", listOf("кирилу"), "0.000001", "usdc"),
        Row("переказ Кирилу 0.0000001 USDC", listOf("кирилу"), "0.0000001", "usdc"),
        Row("send 0.1 sol to Kyrylo", listOf("kyrylo"), "0.1", "sol"),
        Row("pay Olena five dollars", listOf("olena"), "5", "usdc"),
        Row("send Olena \$5", listOf("olena"), "5", "usdc"),
        Row("заплати Олені 3", listOf("олені"), "3", null),
        Row("Фантом, будь ласка, скинь Олені 2 USDC за каву", listOf("олені"), "2", "usdc"),
        Row("tip Olena 10 skr", listOf("olena"), "10", "skr"),
        Row("скинь 5 usdc", emptyList(), "5", "usdc"),
        Row("скинь Олені 5 EURC", listOf("олені"), "5", "eurc"),
        Row("скинь Олені 5 євро", listOf("олені"), "5", "eurc"),
        Row("скинь Олені п'ять євро", listOf("олені"), "5", "eurc"),
        Row("переказати Олені €5", listOf("олені"), "5", "eurc"),
        Row("переказати Олені 5 €", listOf("олені"), "5", "eurc"),
        Row("переказати Олені 2,5€", listOf("олені"), "2.5", "eurc"),
        Row("send Olena 5 euros", listOf("olena"), "5", "eurc"),
        Row("pay Olena five euro", listOf("olena"), "5", "eurc"),
        Row("pay Olena €2.5 for the dinner", listOf("olena"), "2.5", "eurc"),
        Row("Send Daniel 5 USDC for pho", listOf("daniel"), "5", "usdc"),
        Row("send 5 usdc to Mia for lunch", listOf("mia"), "5", "usdc"),
        Row("send Olena 3 EUR", listOf("olena"), "3", "eurc"),
    )

    @Test
    fun `phrases become a pay ask with the person, amount and coin as said`() {
        for (r in table) {
            val ask = MoneyTool.read(r.phrase) as? MoneyAsk.Pay ?: error("not read: ${r.phrase}")
            assertEquals(r.phrase, r.who, ask.who)
            assertEquals(r.phrase, r.amount?.let(::BigDecimal), ask.amount)
            assertEquals(r.phrase, r.coin, ask.coin)
        }
    }

    @Test
    fun `the reason after for or za is kept as the note, in three languages`() {
        mapOf(
            "Send Daniel 5 USDC for pho" to "pho",
            "send 5 usdc to Mia for lunch" to "lunch",
            "скинь Даніелю 5 USDC за фо" to "фо",
            "pošli Danielovi 5 USDC za pho" to "pho",
        ).forEach { (phrase, note) ->
            val ask = MoneyTool.read(phrase) as? MoneyAsk.Pay
            assertNotNull(phrase, ask)
            assertEquals(phrase, note, ask!!.note)
        }
        assertNull((MoneyTool.read("send Olena 5 usdc") as MoneyAsk.Pay).note)
    }

    @Test
    fun `Czech phrases read as a pay ask`() {
        val rows = listOf(
            Row("pošli Danielovi 5 USDC", listOf("danielovi"), "5", "usdc"),
            Row("zaplať Olaně 3 dolary", listOf("olaně"), "3", "usdc"),
            Row("převeď Olaně pět eur", listOf("olaně"), "5", "eurc"),
            Row("prosím pošli Olaně 2,5 USDC", listOf("olaně"), "2.5", "usdc"),
        )
        for (r in rows) {
            val ask = MoneyTool.read(r.phrase) as? MoneyAsk.Pay
            assertNotNull(r.phrase, ask)
            assertEquals(r.phrase, r.who, ask!!.who)
            assertEquals(r.phrase, r.amount?.let(::BigDecimal), ask.amount)
            assertEquals(r.phrase, r.coin, ask.coin)
        }
        assertNull(MoneyTool.read("pošli mi odkaz"))
        assertNull(MoneyTool.read("pošli Olaně 100 korun"))
    }

    @Test
    fun `amount keeps every digit`() {
        val ask = MoneyTool.read("переказ Кирилу 0.123456789 SOL") as MoneyAsk.Pay
        assertEquals("0.123456789", ask.amount!!.toPlainString())
    }

    @Test
    fun `typed address is lifted out of the phrase`() {
        val addr = "B1rE3irXU6uJ1F2tF7iGmCRssRLUcG9GgmU7ULGtcxyE"
        val ask = MoneyTool.read("скинь 1 USDC на $addr") as MoneyAsk.Pay
        assertEquals(addr, ask.address)
        assertTrue(ask.who.isEmpty())
    }

    @Test
    fun `talk that is not a transfer is left to the brain`() {
        listOf(
            "скинь мені посилання",
            "скинь Олені 5",
            "send me the file",
            "скинь Олені 100 гривень",
            "яка погода завтра",
            "переказ 5 USDC і 3 SOL Олені",
            "переказ 5 USDC і 3 євро Олені",
            "скинь Олені 5 євро usdc",
            "як засвідчити фото?",
        ).forEach { assertNull(it, MoneyTool.read(it)) }
    }

    @Test
    fun `seal phrases ask for a photo`() {
        listOf("засвідчи це фото", "засвідчи останнє фото", "seal this photo", "seal the last photo").forEach {
            assertEquals(it, MoneyAsk.Seal, MoneyTool.read(it))
        }
    }

    @Test
    fun `spoken names land on contacts in any case`() {
        assertTrue(MoneyTool.nameMatches(listOf("олені"), "Олена"))
        assertTrue(MoneyTool.nameMatches(listOf("кирилу"), "Кирило Петренко"))
        assertTrue(MoneyTool.nameMatches(listOf("андрію"), "Андрій"))
        assertTrue(MoneyTool.nameMatches(listOf("марії"), "Марія"))
        assertTrue(MoneyTool.nameMatches(listOf("ользі"), "Ольга"))
        assertTrue(MoneyTool.nameMatches(listOf("олегові"), "Олег"))
        assertTrue(MoneyTool.nameMatches(listOf("olena"), "Olena K."))
        assertFalse(MoneyTool.nameMatches(listOf("олені"), "Оля"))
        assertFalse(MoneyTool.nameMatches(listOf("олені"), "Олег"))
        assertFalse(MoneyTool.nameMatches(listOf("олені", "коваль"), "Олена Шевчук"))
    }

    private data class P(val id: String, val name: String, val wallet: Boolean)

    private fun pick(said: String, people: List<P>) =
        MoneyTool.pick((MoneyTool.read(said) as MoneyAsk.Pay).who, people, P::name, P::wallet)

    @Test
    fun `two people with the same name is a question, not a guess`() {
        val people = listOf(P("1", "Олена Коваль", true), P("2", "Олена Шевчук", true), P("3", "Кирило", true))
        val r = pick("скинь Олені 5 USDC", people)
        assertTrue(r is Pick.Several)
        assertEquals(listOf("1", "2"), (r as Pick.Several).who.map { it.id })
        assertEquals(Pick.One(people[1]), pick("скинь Олені Шевчук 5 USDC", people))
    }

    @Test
    fun `a name without a wallet is said honestly`() {
        val people = listOf(P("1", "Олена", false), P("3", "Кирило", true))
        assertEquals(Pick.NoWallet(listOf(people[0])), pick("скинь Олені 5 USDC", people))
        assertEquals(Pick.Nobody, pick("скинь Тарасу 5 USDC", people))
        assertEquals(Pick.One(people[1]), pick("переказати Кирилу 0.1 SOL", people))
    }
}
