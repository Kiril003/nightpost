package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.solana.Coin
import local.phantom.companion.core.solana.SolanaKey
import local.phantom.companion.core.solana.WalletActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Контракт `POST /messenger/solana/pay` між вебом і мостом.
 *
 * Веб (`solanaActions.ts`, `PayAsk`) шле рівно `{peer_id, token, amount}`:
 * монета й сума з аркуша в розмові заповнюють рідний діалог переказу, а
 * перевірку суми й підтвердження відбитком робить телефон. Те, чого телефон
 * не прийняв би сам (`Amounts.toUnits`), сюди не проходить — діалог лишається порожнім.
 */
class WebMessengerSolanaPayAskTest {

    @Test
    fun `поля тіла — ті самі, що шле веб`() {
        assertEquals(
            listOf("peer_id", "token", "amount"),
            listOf(WebMessengerSolana.PAY_PEER, WebMessengerSolana.PAY_TOKEN, WebMessengerSolana.PAY_AMOUNT),
        )
    }

    @Test
    fun `монета й сума з розмови доїжджають до діалогу`() {
        assertEquals(WebMessengerSolana.PayAsk("oksana", Coin.USDC, "2.5"), WebMessengerSolana.payAsk("oksana", "USDC", "2.5"))
        assertEquals(WebMessengerSolana.PayAsk("oksana", Coin.SOL, "0.001"), WebMessengerSolana.payAsk("oksana", "SOL", "0.001"))
        assertEquals(Coin.SKR, WebMessengerSolana.payAsk("oksana", "SKR", "1")?.coin)
        assertEquals(WebMessengerSolana.PayAsk("oksana", Coin.EURC, "5"), WebMessengerSolana.payAsk("oksana", "EURC", "5"))
    }

    @Test
    fun `без людини — відмова, а крива сума чи монета — просто переказ без заповнення`() {
        assertNull(WebMessengerSolana.payAsk("", "USDC", "1"))
        assertNull(WebMessengerSolana.payAsk(null, "USDC", "1"))
        // Старий веб шле лише peer_id — і далі відкриває переказ.
        assertEquals(WebMessengerSolana.PayAsk("oksana", null, null), WebMessengerSolana.payAsk("oksana", "", ""))
        assertEquals(WebMessengerSolana.PayAsk("oksana", null, null), WebMessengerSolana.payAsk("oksana", "DOGE", "1"))
        assertEquals(WebMessengerSolana.PayAsk("oksana", null, null), WebMessengerSolana.payAsk("oksana", "USDC", "0"))
        assertEquals(WebMessengerSolana.PayAsk("oksana", null, null), WebMessengerSolana.payAsk("oksana", "USDC", "-1"))
        // USDC має 6 знаків: сьомий — відмова, а не округлення.
        assertEquals(WebMessengerSolana.PayAsk("oksana", null, null), WebMessengerSolana.payAsk("oksana", "USDC", "0.0000001"))
    }

    // ── збірка 18: частка рахунку в групу ────────────────────────────────

    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"
    private val venue = SolanaKey.of(ByteArray(32) { 7 })!!

    @Test
    fun `нові поля тіла — chat_id, ref і to закладу`() {
        assertEquals(listOf("chat_id", "ref", "to"), listOf(WebMessengerSolana.PAY_CHAT, WebMessengerSolana.PAY_REF, WebMessengerSolana.PAY_TO))
    }

    @Test
    fun `чат і id рахунку доїжджають до діалогу`() {
        val ask = WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "grp-cafe", ref = bill)
        assertEquals(WebMessengerSolana.PayAsk("olena", Coin.USDC, "7.5", chat = "grp-cafe", ref = bill), ask)
        // Чат, що й є розмовою з отримувачем, — звичайна 1:1, без групи.
        assertNull(WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "olena", ref = bill)?.chat)
        assertEquals(bill, WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "olena", ref = bill)?.ref)
    }

    @Test
    fun `кривий ref — відмова, а не переказ без привʼязки`() {
        assertNull(WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "grp-cafe", ref = "a;b"))
        assertNull(WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "grp-cafe", ref = "x".repeat(37)))
    }

    @Test
    fun `закладу платять лише з групи і лише на справжню адресу`() {
        val ask = WebMessengerSolana.payAsk(null, "USDC", "7.5", to = venue.base58, chat = "grp-cafe", ref = bill)
        assertEquals(WebMessengerSolana.PayAsk(null, Coin.USDC, "7.5", to = venue, chat = "grp-cafe", ref = bill), ask)
        assertNull("без групи картку закладу нікому показати", WebMessengerSolana.payAsk(null, "USDC", "7.5", to = venue.base58, ref = bill))
        assertNull("крива адреса", WebMessengerSolana.payAsk(null, "USDC", "7.5", to = "nope", chat = "grp-cafe", ref = bill))
        assertNull("і людина, і заклад — незрозуміло кому", WebMessengerSolana.payAsk("olena", "USDC", "7.5", to = venue.base58, chat = "grp-cafe"))
    }

    /** Знахідка 3 рев'ю: гаманець закладу їде своїм ключем, а не як «вписана вручну» адреса. */
    @Test
    fun `адреса закладу їде в гаманець як заклад, а не як вписана вручну`() {
        val toVenue = WebMessengerSolana.payAsk(null, "USDC", "7.5", to = venue.base58, chat = "grp-cafe", ref = bill)!!
        val extras = WebMessengerSolana.walletExtras(toVenue).toMap()
        assertEquals(venue.base58, extras[WalletActivity.EXTRA_PAY_VENUE])
        assertFalse("draft_to — це «вписано вручну»", extras.containsKey(WalletActivity.EXTRA_TO))
        assertEquals("grp-cafe", extras[WalletActivity.EXTRA_PAY_CHAT])
        assertEquals(bill, extras[WalletActivity.EXTRA_PAY_REF])
        assertEquals("7.5", extras[WalletActivity.EXTRA_PAY_AMOUNT])
        // Що поїхало, те екран і читає: заклад, а не руками.
        val opened = WalletActivity.Opened.read({ k -> extras[k] })
        assertEquals(venue, opened.typedTo)
        assertTrue(opened.venue)
        val olena = WebMessengerSolana.walletExtras(WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "grp-cafe", ref = bill)!!).toMap()
        assertFalse(olena.containsKey(WalletActivity.EXTRA_PAY_VENUE) || olena.containsKey(WalletActivity.EXTRA_TO))
    }

    private fun group(vararg members: String) = PeerGroup.Group(id = "grp-cafe", name = "Cafe X · 19:00", memberIds = members.toList())

    @Test
    fun `група мусить бути моєю і з отримувачем у складі`() {
        val ask = WebMessengerSolana.payAsk("olena", "USDC", "7.5", chat = "grp-cafe", ref = bill)!!
        assertNull(WebMessengerSolana.chatRefusal(ask, group("me", "olena", "petro"), PeerGroup.Standing.IN))
        assertEquals("not_in_group", WebMessengerSolana.chatRefusal(ask, group("olena", "petro"), PeerGroup.Standing.LEFT))
        assertEquals("payee_not_in_group", WebMessengerSolana.chatRefusal(ask, group("me", "petro"), PeerGroup.Standing.IN))
        val toVenue = WebMessengerSolana.payAsk(null, "USDC", "7.5", to = venue.base58, chat = "grp-cafe", ref = bill)!!
        assertNull(WebMessengerSolana.chatRefusal(toVenue, group("me", "petro"), PeerGroup.Standing.IN))
    }
}
