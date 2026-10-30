package local.phantom.companion.core.solana

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Картки — лише справжні активи; історія — справжні рухи з мережі; датчик живе лише на виду. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WalletCardsTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val olena = SolanaKey.of(ByteArray(32) { (it * 11 + 3).toByte() })!!
    private val state = WalletState(
        address = me,
        builtIn = me,
        cluster = Cluster.DEVNET,
        balances = mapOf(Coin.USDC to 12_500_000L, Coin.EURC to 0L, Coin.SOL to 0L),
    )

    private fun coins(cards: List<WalletCard>) = cards.filter { it != WalletCard.Add }.map { (it as? WalletCard.Money)?.coin?.symbol ?: "points" }

    @Test fun solAndUsdcAlwaysEvenAtZeroOtherCoinsOnlyWhenHeld() {
        assertEquals(listOf("USDC", "SOL"), coins(WalletCards.of(state, null)))
        val withEurc = state.copy(balances = mapOf(Coin.USDC to 0L, Coin.EURC to 3_000_000L, Coin.SOL to 0L))
        assertEquals(listOf("USDC", "SOL", "EURC"), coins(WalletCards.of(withEurc, null)))
        assertEquals(0L, (WalletCards.of(withEurc, null)[1] as WalletCard.Money).units)
        assertEquals("«+» — завжди остання", WalletCard.Add, WalletCards.of(withEurc, null).last())
    }

    @Test fun dollarsReadAsMoney() {
        val us = java.util.Locale.US
        assertEquals("$12.50", WalletLook.cash(12_500_000, Coin.USDC, us))
        assertEquals("$1,234.56", WalletLook.cash(1_234_567_890, Coin.USDC, us))
        assertEquals("$0.00", WalletLook.cash(0, Coin.USDC, us))
        assertEquals("€3.00", WalletLook.cash(3_000_000, Coin.EURC, us))
        assertNull(WalletLook.cash(1, Coin.SOL, us))
        assertEquals("1.5 SOL", WalletLook.money(1_500_000_000, Coin.SOL, us))
    }

    @Test fun unknownBalanceIsUnknownNotZero() {
        val cards = WalletCards.of(state.copy(balances = null), null)
        assertEquals(listOf("USDC", "SOL"), coins(cards))
        assertTrue(cards.filterIsInstance<WalletCard.Money>().all { it.units == null })
    }

    @Test fun noAddressNoCards() = assertTrue(WalletCards.of(state.copy(address = null), WalletPoints.Shown(5, emptyList())).isEmpty())

    @Test fun pointsCardOnlyWhenServerExists() {
        assertEquals(listOf("USDC", "SOL"), coins(WalletCards.of(state, WalletPoints.Hidden)))
        assertEquals(WalletCard.Points(40), WalletCards.of(state, WalletPoints.Shown(40, emptyList())).dropLast(1).last())
        assertEquals(WalletCard.Points(null), WalletCards.of(state, WalletPoints.Unreachable).dropLast(1).last())
    }

    @Test fun mainnetHasNoDevnetOnlyCoins() {
        val main = state.copy(cluster = Cluster.MAINNET, balances = mapOf(Coin.SKR to 0L, Coin.SOL to 1L, Coin.USDC to 0L))
        assertEquals(listOf("USDC", "SOL"), coins(WalletCards.of(main, null)))
        assertTrue(WalletCards.of(main, null).filterIsInstance<WalletCard.Money>().all { it.cluster == Cluster.MAINNET })
    }

    private fun tx(body: String) = Json.parseToJsonElement(body)

    private fun solTransfer(from: SolanaKey, to: SolanaKey, lamports: Long, err: String = "null") = tx(
        """{"blockTime":1760000000,"meta":{"err":$err,"innerInstructions":[]},
           "transaction":{"message":{"instructions":[{"program":"system","parsed":{"type":"transfer",
           "info":{"source":"${from.base58}","destination":"${to.base58}","lamports":$lamports}}}]}}}""",
    )

    @Test fun solActivityReadsSystemTransfersBothWays() {
        val incoming = WalletFeed.lineOf(solTransfer(olena, me, 1_000_000_000), "s1", me, Coin.SOL, Cluster.DEVNET)!!
        assertEquals(1_000_000_000L, incoming.units)
        assertEquals(olena, incoming.counterparty)
        assertEquals(1_760_000_000L, incoming.atSec)
        val out = WalletFeed.lineOf(solTransfer(me, olena, 5), "s2", me, Coin.SOL, Cluster.DEVNET)!!
        assertEquals(-5L, out.units)
        val failed = WalletFeed.lineOf(solTransfer(me, olena, 5, err = "{\"x\":1}"), "s3", me, Coin.SOL, Cluster.DEVNET)!!
        assertTrue("власна спроба, що не пройшла, лишається в історії", failed.failed && failed.units == -5L && failed.counterparty == olena)
        assertNull("чужа спроба надіслати мені, що не пройшла, — не рух", WalletFeed.lineOf(solTransfer(olena, me, 5, err = "{\"x\":1}"), "s4", me, Coin.SOL, Cluster.DEVNET))
    }

    @Test fun failedTokenSendKeepsTheAmountThatWasMeantToGo() {
        val mint = Coin.USDC.mint(Cluster.DEVNET)!!.base58
        val mine = SolanaKey.parse("So11111111111111111111111111111111111111112")!!.base58
        val hers = SolanaKey.parse("SysvarRent111111111111111111111111111111111")!!.base58
        fun bal(i: Int, owner: SolanaKey, amount: Long) = """{"accountIndex":$i,"mint":"$mint","owner":"${owner.base58}","uiTokenAmount":{"amount":"$amount"}}"""
        val t = tx(
            """{"blockTime":2,"meta":{"err":{"InstructionError":[0,"Custom"]},
               "preTokenBalances":[${bal(1, me, 1_000_000)},${bal(2, olena, 0)}],
               "postTokenBalances":[${bal(1, me, 1_000_000)},${bal(2, olena, 0)}]},
               "transaction":{"message":{"accountKeys":[{"pubkey":"${me.base58}"},{"pubkey":"$mine"},{"pubkey":"$hers"}],
               "instructions":[{"program":"spl-token","parsed":{"type":"transferChecked","info":{"source":"$mine","destination":"$hers",
               "authority":"${me.base58}","mint":"$mint","tokenAmount":{"amount":"6500000"}}}}]}}}""",
        )
        val line = WalletFeed.lineOf(t, "f1", me, Coin.USDC, Cluster.DEVNET)!!
        assertTrue(line.toString(), line.failed && line.units == -6_500_000L && line.counterparty == olena)
    }

    @Test fun tokenActivityIsOwnersBalanceDelta() {
        val mint = Coin.USDC.mint(Cluster.DEVNET)!!.base58
        fun bal(owner: SolanaKey, amount: Long) = """{"mint":"$mint","owner":"${owner.base58}","uiTokenAmount":{"amount":"$amount"}}"""
        val t = tx(
            """{"blockTime":1,"meta":{"err":null,
               "preTokenBalances":[${bal(me, 12_500_000)},${bal(olena, 0)}],
               "postTokenBalances":[${bal(me, 7_500_000)},${bal(olena, 5_000_000)}]},
               "transaction":{"message":{"instructions":[]}}}""",
        )
        val line = WalletFeed.lineOf(t, "t1", me, Coin.USDC, Cluster.DEVNET)!!
        assertEquals(-5_000_000L, line.units)
        assertEquals(olena, line.counterparty)
        assertNull("той самий переказ не рухав SOL", WalletFeed.lineOf(t, "t1", me, Coin.SOL, Cluster.DEVNET))
    }
}
