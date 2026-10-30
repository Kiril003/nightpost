package local.phantom.companion.core.solana

import java.io.File
import java.security.SecureRandom
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Картки поза відомими монетами: токени з рахунку, додані за адресою, «лише дивитись», сховані й порядок. */
class WalletShelfTest {

    private val me = SolanaKey.of(ByteArray(32) { (it * 7 + 1).toByte() })!!
    private val bonk = SolanaKey.of(ByteArray(32) { (it * 5 + 2).toByte() })!!
    private val jup = SolanaKey.of(ByteArray(32) { (it * 3 + 9).toByte() })!!
    private val friend = SolanaKey.of(ByteArray(32) { (it * 11 + 3).toByte() })!!
    private val usdc = Coin.USDC.mint(Cluster.DEVNET)!!
    private val state = WalletState(
        address = me,
        builtIn = me,
        cluster = Cluster.DEVNET,
        balances = mapOf(Coin.USDC to 12_500_000L, Coin.EURC to 0L, Coin.SOL to 0L),
    )

    private fun row(mint: SolanaKey, amount: Long, decimals: Int) =
        """{"pubkey":"x","account":{"data":{"parsed":{"info":{"mint":"${mint.base58}","tokenAmount":{"amount":"$amount","decimals":$decimals}}}}}}"""

    @Test fun tokenAccountsSumPerMintAndSkipBrokenRows() {
        val resp = Json.parseToJsonElement(
            """{"context":{"slot":1},"value":[${row(bonk, 300, 5)},${row(bonk, 200, 5)},${row(usdc, 7, 6)},{"account":{}}]}""",
        )
        val held = TokenHoldings.parse(resp)
        assertEquals(listOf(HeldToken(bonk, 500, 5), HeldToken(usdc, 7, 6)), held)
        assertEquals("USDC має власну картку", listOf(HeldToken(bonk, 500, 5)), TokenHoldings.foreign(held, Cluster.DEVNET))
        assertTrue(TokenHoldings.parse(Json.parseToJsonElement("""{"value":[]}""")).isEmpty())
    }

    @Test fun everyHeldTokenGetsItsOwnCardAfterTheCoins() {
        val cards = WalletCards.of(state.copy(tokens = listOf(HeldToken(bonk, 500, 5))), null)
        assertEquals(listOf("coin:usdc", "coin:sol", "mint:${bonk.base58}", "add"), cards.map { it.key })
        assertEquals(WalletCard.Token(bonk, 500, 5, Cluster.DEVNET, me), cards[2])
    }

    @Test fun addedMintIsAnHonestZeroOnceTheAccountIsRead() {
        val shelf = Shelf().withMint(jup).withMint(jup)
        assertEquals(listOf(jup.base58), shelf.mints)
        val unread = WalletCards.of(state.copy(shelf = shelf), null).filterIsInstance<WalletCard.Token>().single()
        assertNull("рахунок ще не читали — не нуль, а невідомо", unread.units)
        val read = WalletCards.of(state.copy(shelf = shelf, tokens = emptyList()), null).filterIsInstance<WalletCard.Token>().single()
        assertEquals(0L, read.units)
    }

    @Test fun watchedAddressIsACardWithItsBalance() {
        val s = state.copy(shelf = Shelf().withWatched(friend), watchedLamports = mapOf(friend.base58 to 2_000_000_000L))
        assertEquals(WalletCard.Watch(friend, 2_000_000_000L, Cluster.DEVNET), WalletCards.of(s, null).filterIsInstance<WalletCard.Watch>().single())
        assertNull(WalletCards.of(state.copy(shelf = Shelf().withWatched(friend)), null).filterIsInstance<WalletCard.Watch>().single().lamports)
    }

    @Test fun hiddenCardsLeaveAndComeBack() {
        val hidden = Shelf().hide("coin:sol")
        assertEquals(listOf("coin:usdc", "add"), WalletCards.of(state.copy(shelf = hidden), null).map { it.key })
        assertEquals(listOf("coin:usdc", "coin:sol", "add"), WalletCards.of(state.copy(shelf = hidden.show("coin:sol")), null).map { it.key })
    }

    @Test fun moveReordersAndAddStaysLast() {
        val keys = listOf("coin:usdc", "coin:sol", "points")
        val moved = Shelf().move("points", -1, keys)
        assertEquals(listOf("coin:usdc", "points", "coin:sol"), moved.order)
        assertEquals(moved, moved.move("coin:usdc", -1, moved.order))
        val cards = WalletCards.of(state.copy(shelf = moved.move("coin:usdc", 1, moved.order)), WalletPoints.Shown(4, emptyList()))
        assertEquals(listOf("points", "coin:usdc", "coin:sol", "add"), cards.map { it.key })
    }

    @Test fun tokenSumsReadPlain() {
        assertEquals("0", tokenAmount(0, 6))
        assertEquals("0.005", tokenAmount(500, 5))
        assertEquals("12.5", tokenAmount(12_500_000, 6))
        assertEquals("42", tokenAmount(42, 0))
    }

    @Test fun twentyFourWordsRestoreLikeTwelve() {
        val bip39 = Bip39(File("src/main/assets/bip39_english.txt").readLines().filter { it.isNotBlank() })
        val long = bip39.fromEntropy(ByteArray(32) { (it * 31 + 7).toByte() })
        assertEquals(24, long.size)
        assertTrue(bip39.isValid(long))
        assertTrue(bip39.isValid(bip39.generate(SecureRandom())))
        assertFalse("переставлені слова — не гаманець", bip39.isValid(long.reversed()))
        assertFalse(bip39.isValid(long.take(18)))
    }
}
