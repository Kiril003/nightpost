package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private val me = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
private val to = SolanaKey.of("4BxwEwYtKfjRoTfNXFdQbnrXfzQ23GeRxt1p9M5dwHQT")
private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
private val blockhash = Base58.encode(ByteArray(32) { 0x42 })
private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"

class PayNoteTest {

    private class Harness {
        val peerSent = mutableListOf<Pair<String, String>>()
        val chatSent = mutableListOf<Pair<String, String>>()
        val signedMessages = mutableListOf<ByteArray>()
        val host = WalletHost(null) { true }

        init {
            SolanaLayer.testReset(
                WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
                SolanaConfig(
                    licencePurchase = false,
                    licenceBaseUrl = "",
                    activateLicence = { false },
                    contacts = { emptyList() },
                    send = { id, body -> peerSent += id to body; true },
                    sendToChat = { id, body -> chatSent += id to body; true },
                ),
            )
            SolanaLayer.signForTest = { msg ->
                signedMessages += msg
                SolanaTx.unsigned(msg)
            }
            SolanaLayer.rpcTransport = RpcTransport { _, body ->
                when {
                    "getLatestBlockhash" in body ->
                        """{"jsonrpc":"2.0","id":1,"result":{"value":{"blockhash":"$blockhash"}}}"""
                    "sendTransaction" in body ->
                        """{"jsonrpc":"2.0","id":1,"result":"$sig"}"""
                    else ->
                        """{"jsonrpc":"2.0","id":1,"result":null}"""
                }
            }
        }
    }

    @Test
    fun payPeerWithNoteAndNoRouteRefMintsRefForMemoAndFrameAndDeliversToPeerOnly() = runTest {
        val h = Harness()
        val outcome = SolanaLayer.payPeer(
            host = h.host,
            peerId = "olena",
            to = to,
            coin = Coin.USDC,
            units = 5_000_000L,
            route = PayRoute(),
            note = "за каву; 50%",
        )
        val paid = (outcome as WalletOutcome.Done).value
        val frame = paid.frame
        assertNotNull("Expected minted ref when note is present", frame.ref)
        assertTrue(PaymentFrame.isRef(frame.ref))
        assertEquals("за каву; 50%", frame.note)

        assertTrue("Chat must not be used for 1:1 pay", h.chatSent.isEmpty())
        val (peerId, wire) = h.peerSent.single { it.second.startsWith(PaymentFrame.TAG) }
        assertEquals("olena", peerId)
        val decoded = PaymentFrame.decode(wire)
        assertNotNull(decoded)
        assertEquals(frame.ref, decoded!!.ref)
        assertEquals("за каву; 50%", decoded.note)

        val msgBytes = String(h.signedMessages.single(), Charsets.ISO_8859_1)
        assertTrue(
            "Expected transaction Memo to carry minted ref ${frame.ref}",
            msgBytes.contains(PaymentFrame.memo(frame.ref)!!),
        )
    }

    @Test
    fun payPeerWithRouteRefKeepsRouteRefAndAttachesNote() = runTest {
        val h = Harness()
        val outcome = SolanaLayer.payPeer(
            host = h.host,
            peerId = "olena",
            to = to,
            coin = Coin.USDC,
            units = 7_500_000L,
            route = PayRoute(chatId = "grp-cafe", ref = bill),
            note = "моя частка",
        )
        val paid = (outcome as WalletOutcome.Done).value
        assertEquals(bill, paid.frame.ref)
        assertEquals("моя частка", paid.frame.note)

        val (chatId, wire) = h.chatSent.single()
        assertEquals("grp-cafe", chatId)
        val decoded = PaymentFrame.decode(wire)!!
        assertEquals(bill, decoded.ref)
        assertEquals("моя частка", decoded.note)

        val msgBytes = String(h.signedMessages.single(), Charsets.ISO_8859_1)
        assertTrue(msgBytes.contains(PaymentFrame.memo(bill)!!))
    }

    @Test
    fun payPeerDropsInvalidNoteAndLeavesNoNoteFramesByteForByteUnchanged() = runTest {
        val h = Harness()

        val plain = (SolanaLayer.payPeer(h.host, "olena", to, Coin.USDC, 1_000_000L, PayRoute(), note = null) as WalletOutcome.Done).value.frame
        assertNull(plain.ref)
        assertNull(plain.note)
        assertEquals("PH-PAY:1;devnet;usdc;1000000;${to.base58};$sig", plain.encode())
        assertFalse(String(h.signedMessages.last(), Charsets.ISO_8859_1).contains(PaymentFrame.MEMO_PREFIX))

        val invalidNote = (SolanaLayer.payPeer(h.host, "olena", to, Coin.USDC, 1_000_000L, PayRoute(), note = "bad\nnote") as WalletOutcome.Done).value.frame
        assertNull(invalidNote.ref)
        assertNull(invalidNote.note)
        assertEquals("PH-PAY:1;devnet;usdc;1000000;${to.base58};$sig", invalidNote.encode())
        assertFalse(String(h.signedMessages.last(), Charsets.ISO_8859_1).contains(PaymentFrame.MEMO_PREFIX))

        val validNote = (SolanaLayer.payPeer(h.host, "olena", to, Coin.USDC, 1_000_000L, PayRoute(), note = "pizza") as WalletOutcome.Done).value.frame
        assertNotNull(validNote.ref)
        assertEquals("pizza", validNote.note)
        assertTrue(validNote.encode().endsWith(";${validNote.ref};pizza"))
    }
}
