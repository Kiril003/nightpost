package local.phantom.companion.core.solana

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Головна гаманця під крипто: живе поле адреси, око над сумами, стос карток, комісія й рента. */
@RunWith(RobolectricTestRunner::class)
class WalletHomeTest {

    private val valid = listOf(
        "2noC5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ",
        "Cq67VS5SQhvh6ScrRzZp23Fu9BDd436sA6nfr8v51m4n",
        "89WZqT4oqAQx23QuGGpHn8bSYqhhykdz2nqugdqofs5s",
        "11111111111111111111111111111111",
        "So11111111111111111111111111111111111111112",
    )

    @Test fun addressGoldenTable() {
        valid.forEach { a ->
            val c = AddressCheck.of(a)
            assertTrue("$a → $c", c is AddressCheck.Ok && c.key.base58 == a)
        }
        assertTrue("пробіли довкола — не помилка", AddressCheck.of("  ${valid[0]}\n") is AddressCheck.Ok)
        assertEquals(AddressCheck.Empty, AddressCheck.of(""))
        assertEquals(AddressCheck.Empty, AddressCheck.of("   "))
        assertEquals(AddressCheck.BadChar('0', 8), AddressCheck.of("2noC5Ztz0OstghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.BadChar('O', 0), AddressCheck.of("OnoC5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.BadChar('I', 1), AddressCheck.of("2IoC5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.BadChar('l', 2), AddressCheck.of("2nlC5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.BadChar(' ', 4), AddressCheck.of("2noC 5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.BadChar('і', 3), AddressCheck.of("2noі5ZtzV1stghvK9P8vrqqkQN2CcXvQd27tnJLC91RZ"))
        assertEquals(AddressCheck.TooShort, AddressCheck.of("2noC5Ztz"))
        assertEquals(AddressCheck.TooShort, AddressCheck.of(valid[0].dropLast(13)))
        assertEquals(AddressCheck.TooLong, AddressCheck.of(valid[0] + "abc"))
        assertEquals("44 знаки, але не 32 байти", AddressCheck.NotKey, AddressCheck.of("z".repeat(44)))
        assertEquals(AddressCheck.NotKey, AddressCheck.of(valid[0].dropLast(1)))
    }

    @Test fun veilPersistsAcrossReopen() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val prefs = { ctx.getSharedPreferences("phantom_wallet_look", Context.MODE_PRIVATE) }
        assertFalse("за замовчуванням суми видно", WalletVeil(prefs()).on)
        WalletVeil(prefs()).on = true
        assertTrue("око пам'ятає вибір після перезапуску", WalletVeil(prefs()).on)
        assertEquals("••••", WalletVeil.mask("18,50", true))
        assertEquals("18,50", WalletVeil.mask("18,50", false))
        WalletVeil(prefs()).on = false
        assertFalse(WalletVeil(prefs()).on)
    }

    @Test fun frontCardStaysInFrontAfterReload() {
        val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
        val keys = listOf("coin:usdc", "coin:sol", "points")
        val shelf = Shelf().front("points", keys)
        assertEquals(listOf("points", "coin:usdc", "coin:sol"), shelf.order)
        assertEquals("уже спереду — без змін", shelf, shelf.front("points", shelf.order))
        assertEquals("невідома картка — без змін", shelf, shelf.front("mint:x", shelf.order))
        val reloaded = Json.decodeFromString(Shelf.serializer(), Json.encodeToString(Shelf.serializer(), shelf))
        val state = WalletState(address = me, builtIn = me, cluster = Cluster.DEVNET, balances = mapOf(Coin.USDC to 1L, Coin.SOL to 1L), shelf = reloaded)
        val cards = WalletCards.of(state, WalletPoints.Shown(240, emptyList()))
        assertEquals("points", cards.first().key)
    }

    @Test fun stackShowsHonestEmptyWhenThereAreNoCards() {
        assertEquals(WalletStack.Empty, WalletStack.of(emptyList()))
        assertEquals(WalletStack.Empty, WalletStack.of(listOf(WalletCard.Add)))
        val me = SolanaKey.of(ByteArray(32) { 3 })!!
        val usdc = WalletCard.Money(Coin.USDC, 0L, Cluster.DEVNET, me)
        val sol = WalletCard.Money(Coin.SOL, null, Cluster.DEVNET, me)
        val pts = WalletCard.Points(240)
        val s = WalletStack.of(listOf(usdc, sol, pts, WalletCard.Add)) as WalletStack.Cards
        assertEquals(usdc, s.front)
        assertEquals("дві картки виглядають ззаду, «+» — не картка", listOf(sol, pts), s.peeks)
    }

    @Test fun feeLabelOnlyOnTheSponsorRoute() {
        val payer = SolanaKey.of(ByteArray(32) { 9 })!!
        assertEquals(FeeLine.Zero, FeeLine.of(FeeRoute.Route.Sponsored(payer), 5_000L))
        assertEquals(FeeLine.Zero, FeeLine.of(FeeRoute.Route.Sponsored(payer), null))
        assertEquals(FeeLine.Paid(5_000L), FeeLine.of(FeeRoute.Route.Self, 5_000L))
        assertEquals("комісію не прочитано — рядка немає, а не «0»", FeeLine.Unknown, FeeLine.of(FeeRoute.Route.Self, null))
        val sponsor = SponsorInfo(Cluster.DEVNET, payer)
        assertEquals(FeeLine.Zero, FeeLine.of(FeeRoute.choose(sponsor, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.USDC), 5_000L))
        assertEquals(FeeLine.Paid(5_000L), FeeLine.of(FeeRoute.choose(sponsor, WalletKind.BUILT_IN, Cluster.DEVNET, Coin.SOL), 5_000L))
        assertEquals(FeeLine.Paid(5_000L), FeeLine.of(FeeRoute.choose(sponsor, WalletKind.BUILT_IN, Cluster.MAINNET, Coin.USDC), 5_000L))
    }

    private fun acct(lamports: Long, amount: String, program: String = "spl-token") =
        """{"pubkey":"k$lamports$amount","account":{"lamports":$lamports,"data":{"program":"$program","parsed":{"info":{"mint":"m","tokenAmount":{"amount":"$amount","decimals":6}}}}}}"""

    @Test fun reclaimIsComputedFromEmptyTokenAccountsOnly() {
        val resp = Json.parseToJsonElement("""{"value":[${acct(2_039_280, "0")},${acct(2_039_280, "15")},${acct(2_074_080, "0", "spl-token-2022")},{"account":{}}]}""")
        assertEquals(Reclaim(lamports = 4_113_360L, accounts = 2), Reclaim.of(listOf(resp)))
        assertNull("нічого порожнього — рядка немає", Reclaim.of(listOf(Json.parseToJsonElement("""{"value":[${acct(2_039_280, "1")}]}"""))))
        assertNull(Reclaim.of(emptyList()))
    }

    @Test fun failedReadKeepsLastKnownBalancesWithTheirTime() {
        val first = LastBalances.next(null, mapOf(Coin.USDC to 18_500_000L), 1_000L)
        assertEquals(LastBalances(mapOf(Coin.USDC to 18_500_000L), 1_000L), first)
        assertEquals("мережа впала — лишається старе з його часом", first, LastBalances.next(first, null, 9_000L))
        assertEquals(LastBalances(mapOf(Coin.USDC to 0L), 9_000L), LastBalances.next(first, mapOf(Coin.USDC to 0L), 9_000L))
        assertNull("ще жодного читання — нічого не вигадуємо", LastBalances.next(null, null, 9_000L))
    }

    @Test fun lastBalancesSurviveRestartPerClusterAndOwner() {
        val saved = LastBalances(mapOf(Coin.USDC to 18_500_000L, Coin.SOL to 0L), 1_700_000_000_000L)
        assertEquals(saved, LastBalances.decode(saved.encode()))
        assertNull(LastBalances.decode(null))
        assertNull("зіпсований запис — не нуль, а нічого", LastBalances.decode("x|usdc=1"))
        assertNull(LastBalances.decode("5|usdc=abc"))
        val a = SolanaKey.of(ByteArray(32) { 5 })!!
        val b = SolanaKey.of(ByteArray(32) { 6 })!!
        assertNotEquals(LastBalances.key(Cluster.DEVNET, a), LastBalances.key(Cluster.MAINNET, a))
        assertNotEquals(LastBalances.key(Cluster.DEVNET, a), LastBalances.key(Cluster.DEVNET, b))
    }

    @Test fun activityMergesCoinsNewestFirstWithPeople() {
        val olena = SolanaKey.of(ByteArray(32) { 5 })!!
        val stranger = SolanaKey.of(ByteArray(32) { 6 })!!
        val usdc = listOf(ActivityLine("s1", 15_000_000L, 300L, olena), ActivityLine("s2", -12_000_000L, 100L, stranger))
        val sol = listOf(ActivityLine("s3", 840_000_000L, 200L, olena), ActivityLine("s4", -1L, null, null))
        val contacts = listOf(SolanaContact("p-olena", "Олена", olena), SolanaContact("p-x", "Без гаманця", null))
        val lines = WalletMe.lines(mapOf(Coin.USDC to usdc, Coin.SOL to sol), contacts)!!
        assertEquals(listOf("s1", "s3", "s2", "s4"), lines.map { it.line.signature })
        assertEquals(listOf("p-olena", "p-olena", null, null), lines.map { it.peerId })
        assertEquals("Олена", lines.first().name)
        assertEquals(Coin.SOL, lines[1].coin)
        assertEquals(2, WalletMe.lines(mapOf(Coin.USDC to usdc, Coin.SOL to sol), contacts, limit = 2)!!.size)
        assertEquals("одна монета не прочиталась — показуємо іншу", listOf("s1", "s2"), WalletMe.lines(mapOf(Coin.USDC to usdc, Coin.SOL to null), contacts)!!.map { it.line.signature })
        assertNull("не прочиталось нічого — це не «порожньо»", WalletMe.lines(mapOf(Coin.USDC to null, Coin.SOL to null), contacts))
        assertEquals("прочитано, рухів немає — чесно порожньо", emptyList<MeLine>(), WalletMe.lines(mapOf(Coin.USDC to emptyList()), contacts))
    }
}
