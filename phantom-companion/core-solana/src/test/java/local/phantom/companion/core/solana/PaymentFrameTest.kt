package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PaymentFrameTest {

    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"
    private val to = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")

    private fun tx(name: String) = SolanaRpc.JSON.parseToJsonElement(javaClass.getResource("/solana/$name")!!.readText())

    @Test
    fun frameRoundTrips() {
        val f = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig)
        val wire = f.encode()
        assertTrue(wire.startsWith("PH-PAY:1;devnet;usdc;2500000;"))
        assertEquals(f, PaymentFrame.decode(wire))
        assertEquals(f, PaymentFrame.decode("  $wire "))
        assertEquals("2.5", f.amount)
    }

    @Test
    fun eurcFrameTravelsOnBothClusters() {
        for (cluster in Cluster.entries) {
            val f = PaymentFrame(cluster, Coin.EURC, 2_500_000, to, sig)
            assertTrue(f.encode().startsWith("PH-PAY:1;${cluster.wire};eurc;2500000;"))
            assertEquals(f, PaymentFrame.decode(f.encode()))
        }
    }

    @Test
    fun usdcArrivingIsNotCountedAsEurc() {
        val t = tx("tx_spl.json")
        val recipient = SolanaKey.of("4BxwEwYtKfjRoTfNXFdQbnrXfzQ23GeRxt1p9M5dwHQT")
        assertEquals(PaymentStatus.CONFIRMED, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.USDC, 10_000, recipient, sig), t))
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.EURC, 10_000, recipient, sig), t))
    }

    @Test
    fun malformedFramesAreRejected() {
        val good = PaymentFrame(Cluster.DEVNET, Coin.SOL, 1, to, sig).encode()
        assertNull(PaymentFrame.decode(good.replace("PH-PAY:1", "PH-PAY:2")))
        assertNull(PaymentFrame.decode(good.replace(";1;", ";0;")))
        assertNull(PaymentFrame.decode(good.replace(sig, "short")))
        assertNull(PaymentFrame.decode(good.replace("devnet", "testnet")))
        assertNull(PaymentFrame.decode(good.replace(";sol;", ";skr;")))
        assertNull(PaymentFrame.decode("PH-PAY:"))
        assertNull(PaymentFrame.decode("hello"))
    }

    @Test
    fun addressFrameRoundTrips() {
        val wire = WalletAddressFrame.encode(to)
        assertEquals("PH-SOLKEY:1;BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH", wire)
        assertEquals(to, WalletAddressFrame.decode(wire))
        assertNull(WalletAddressFrame.decode("PH-SOLKEY:1;nope"))
    }

    @Test
    fun realDevnetSolTransferConfirmsTheExactAmountToTheRightPerson() {
        val t = tx("tx_sol.json")
        assertEquals(PaymentStatus.CONFIRMED, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.SOL, 2_824_480, to, sig), t))
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.SOL, 2_824_481, to, sig), t))
        val stranger = SolanaKey.of(ByteArray(32) { 5 })!!
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.SOL, 1, stranger, sig), t))
    }

    @Test
    fun realDevnetUsdcTransferIsCountedFromBalancesOfTheRecipient() {
        val t = tx("tx_spl.json")
        val recipient = SolanaKey.of("4BxwEwYtKfjRoTfNXFdQbnrXfzQ23GeRxt1p9M5dwHQT")
        assertEquals(PaymentStatus.CONFIRMED, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.USDC, 10_000, recipient, sig), t))
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.USDC, 20_000, recipient, sig), t))
        val sender = SolanaKey.of("BMMXLRyafgqRJj1Z3AF332ekFbWHegT6qKXBjffvkUQ9")
        assertEquals(PaymentStatus.MISMATCH, PaymentCheck.judge(PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, sender, sig), t))
    }

    @Test
    fun failedAndUnseenTransactionsAreSaidSo() {
        val failed = SolanaRpc.JSON.parseToJsonElement("""{"meta":{"err":{"InstructionError":[0,"Custom"]}},"transaction":{}}""")
        val f = PaymentFrame(Cluster.DEVNET, Coin.SOL, 1, to, sig)
        assertEquals(PaymentStatus.FAILED, PaymentCheck.judge(f, failed))
        assertEquals(PaymentStatus.PENDING, PaymentCheck.judge(f, kotlinx.serialization.json.JsonNull))
    }

    @Test
    fun offlineCheckIsUnknownNotFailed() = runTest {
        val rpc = SolanaRpc("x") { _, _ -> throw java.io.IOException("offline") }
        assertEquals(PaymentStatus.UNKNOWN, PaymentCheck.check(PaymentFrame(Cluster.DEVNET, Coin.SOL, 1, to, sig), rpc))
        assertFalse(PaymentStatus.UNKNOWN.settled)
    }

    @Test
    fun refAndNoteEncodeUnderTag2AndRoundTrip() {
        val ref = "n0123456789a"
        val withNote = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig, ref = ref, note = "pizza")
        val wire = withNote.encode()
        assertEquals("PH-PAY:2;devnet;usdc;2500000;${to.base58};$sig;$ref;pizza", wire)
        assertEquals(withNote, PaymentFrame.decode(wire))
        assertEquals("pizza", PaymentFrame.decode(wire)?.note)

        val v1 = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig)
        assertEquals("PH-PAY:1;devnet;usdc;2500000;${to.base58};$sig", v1.encode())
        assertNull(PaymentFrame.decode(v1.encode())?.note)

        val v2NoNote = PaymentFrame(Cluster.DEVNET, Coin.USDC, 2_500_000, to, sig, ref = ref)
        assertEquals("PH-PAY:2;devnet;usdc;2500000;${to.base58};$sig;$ref", v2NoNote.encode())
        val decodedV2 = PaymentFrame.decode(v2NoNote.encode())
        assertEquals(v2NoNote, decodedV2)
        assertNull(decodedV2?.note)

        val badNoteWire = "PH-PAY:2;devnet;usdc;2500000;${to.base58};$sig;$ref;%zz"
        val decodedBadNote = PaymentFrame.decode(badNoteWire)
        assertEquals(ref, decodedBadNote?.ref)
        assertNull(decodedBadNote?.note)

        assertNull(PaymentFrame.decode("PH-PAY:3;devnet;usdc;2500000;${to.base58};$sig;$ref;pizza"))
    }

    @Test
    fun noteWithoutRefOrWithInvalidNoteThrows() {
        try {
            PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, to, sig, ref = null, note = "pizza")
            fail("note without ref must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, to, sig, ref = "bill_1", note = "")
            fail("empty note must throw")
        } catch (_: IllegalArgumentException) {
        }
        try {
            PaymentFrame(Cluster.DEVNET, Coin.USDC, 1, to, sig, ref = "bill_1", note = "line1\nline2")
            fail("control char in note must throw")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun newRefPassesIsRefAndDiffersBetweenCalls() {
        val r1 = PaymentFrame.newRef()
        val r2 = PaymentFrame.newRef()
        assertTrue("newRef must pass isRef: $r1", PaymentFrame.isRef(r1))
        assertTrue("newRef must pass isRef: $r2", PaymentFrame.isRef(r2))
        assertTrue(r1.startsWith("n") && r1.length == 12)
        assertNotEquals(r1, r2)
    }
}
