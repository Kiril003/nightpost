package local.phantom.companion.core.solana

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PaySignalTest {

    private val bill = "3f2b8c1e-6d4a-4b7e-9a10-2c5d7e8f9a0b"
    private val owner = SolanaKey.of(ByteArray(32) { 0x21 })!!
    private val sponsor = SolanaKey.of(ByteArray(32) { ((it + 201) % 256).toByte() })!!
    private val to = SolanaKey.of(ByteArray(32) { (it + 101).toByte() })!!
    private val blockhash = ByteArray(32) { 0xAB.toByte() }

    private class FakeHttp(
        var onWatch: (String?) -> Pair<Int, String> = { 200 to "{}" },
        var onSeen: (String) -> Pair<Int, String> = { 200 to """{"seen":[]}""" },
    ) {
        val calls = mutableListOf<Pair<String, String?>>()
        val http = HttpCall { url, body ->
            calls.add(url to body)
            when {
                url.contains("/api/solana/watch") -> onWatch(body)
                url.contains("/api/solana/seen") -> onSeen(url)
                else -> 404 to "{}"
            }
        }
    }

    @Test
    fun refKeyMatchesSha256DomainAndRejectsCrookedRef() {
        val expected = SolanaKey.of(
            MessageDigest.getInstance("SHA-256")
                .digest(("nightpost-ref:v1:$bill").toByteArray(Charsets.UTF_8))
        )!!
        assertEquals(expected, PaymentFrame.refKey(bill))
        assertNotEquals(PaymentFrame.refKey(bill), PaymentFrame.refKey("other-bill-id"))
        for (bad in listOf("", "a;b", "bill 1", "x".repeat(37))) {
            try {
                PaymentFrame.refKey(bad)
                fail("accepted crooked ref: $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun transferAttachesReferenceKeyWhenRefPresentAndStaysIdenticalWithoutRef() {
        val refKey = PaymentFrame.refKey(bill)
        val usdcMsg = PaymentPost.transfer(owner, sponsor, to, Coin.USDC, Cluster.DEVNET, 7_500_000, blockhash, bill)
        val usdcParsed = SolanaTx.parse(usdcMsg)!!
        assertTrue(usdcParsed.keys.contains(refKey))
        assertTrue(usdcParsed.instructions.any { (_, accounts, _) -> accounts.lastOrNull() == refKey })

        val solMsg = PaymentPost.transfer(owner, owner, to, Coin.SOL, Cluster.DEVNET, 7_500_000, blockhash, bill)
        val solParsed = SolanaTx.parse(solMsg)!!
        assertTrue(solParsed.keys.contains(refKey))
        assertTrue(solParsed.instructions.any { (_, accounts, _) -> accounts.lastOrNull() == refKey })

        for (coin in listOf(Coin.USDC, Coin.SOL)) {
            val plain = Transfers.build(owner, to, coin, Cluster.DEVNET, 7_500_000, blockhash, feePayer = owner)
            val withoutRef = PaymentPost.transfer(owner, owner, to, coin, Cluster.DEVNET, 7_500_000, blockhash, null)
            assertArrayEquals(plain, withoutRef)
        }
    }

    @Test
    fun watchRegistersKeyPollsSeenFiresOnSeenOnceAndStops() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp(
            onSeen = { 200 to """{"seen":[{"key":"${k.base58}","sig":"s"}]}""" }
        )
        var seenCount = 0
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B/" },
            now = { testScheduler.currentTime },
        ) { seenCount++ }

        sig.watch(Cluster.DEVNET, k)
        runCurrent()

        assertEquals(2, net.calls.size)
        assertEquals("https://B/api/solana/watch", net.calls[0].first)
        val watchJson = SolanaRpc.JSON.parseToJsonElement(net.calls[0].second!!).jsonObject
        assertEquals("devnet", watchJson["cluster"]?.jsonPrimitive?.content)
        assertEquals(
            listOf(k.base58),
            watchJson["keys"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals("https://B/api/solana/seen?keys=${k.base58}&wait=25", net.calls[1].first)
        assertNull(net.calls[1].second)
        assertEquals(1, seenCount)

        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertEquals(2, net.calls.size)
        assertEquals(1, seenCount)
    }

    @Test
    fun watchArrivingWhileLoopStopsStartsNewLoop() = runTest {
        val a = PaymentFrame.refKey(bill)
        val b = PaymentFrame.refKey("other-bill-id")
        val net = FakeHttp(
            onSeen = { url ->
                if (url.contains(a.base58)) 200 to """{"seen":[{"key":"${a.base58}","sig":"s"}]}"""
                else 200 to """{"seen":[]}"""
            }
        )
        lateinit var sig: PaySignal
        var late = true
        sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B/" },
            now = { testScheduler.currentTime },
            beforeExit = { if (late) { late = false; sig.watch(Cluster.DEVNET, b) } },
        ) {}

        sig.watch(Cluster.DEVNET, a)
        runCurrent()

        assertTrue(net.calls.any { it.first.contains("/api/solana/seen") && it.first.contains(b.base58) })
    }

    @Test
    fun sameKeyWatchedThreeTimesPostsWatchOnce() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp()
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B" },
            now = { testScheduler.currentTime },
        ) {}

        sig.watch(Cluster.DEVNET, k)
        sig.watch(Cluster.DEVNET, k)
        sig.watch(Cluster.DEVNET, k)
        runCurrent()

        val watchCalls = net.calls.count { it.first.endsWith("/api/solana/watch") }
        assertEquals(1, watchCalls)
    }

    @Test
    fun blankBaseMakesZeroCalls() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp()
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "" },
            now = { testScheduler.currentTime },
        ) {}

        sig.watch(Cluster.DEVNET, k)
        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertEquals(0, net.calls.size)
    }

    @Test
    fun seen500WaitsRetryMsBeforeSecondSeenCall() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp(onSeen = { 500 to """{"detail":"err"}""" })
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B" },
            now = { testScheduler.currentTime },
        ) {}

        sig.watch(Cluster.DEVNET, k)
        runCurrent()
        assertEquals(1, net.calls.count { it.first.contains("/api/solana/seen") })

        advanceTimeBy(59_000L)
        runCurrent()
        assertEquals(1, net.calls.count { it.first.contains("/api/solana/seen") })

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, net.calls.count { it.first.contains("/api/solana/seen") })
    }

    @Test
    fun watch503PausesTenMinutes() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp(onWatch = { 503 to """{"detail":"off"}""" })
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B" },
            now = { testScheduler.currentTime },
        ) {}

        sig.watch(Cluster.DEVNET, k)
        runCurrent()
        assertEquals(1, net.calls.size)

        advanceTimeBy(10 * 60_000L - 1_000L)
        runCurrent()
        assertEquals(1, net.calls.size)

        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, net.calls.size)
    }

    @Test
    fun emptySeenStopsAfterTtlAndStaysUnderTwoHundredCalls() = runTest {
        val k = PaymentFrame.refKey(bill)
        val net = FakeHttp()
        val sig = PaySignal(
            backgroundScope,
            http = { net.http },
            base = { "https://B" },
            now = { testScheduler.currentTime },
        ) {}

        sig.watch(Cluster.DEVNET, k)
        advanceTimeBy(16 * 60_000L)
        runCurrent()
        val at16Min = net.calls.size
        assertTrue("expected 1..199 calls, got $at16Min", at16Min in 1 until 200)

        advanceTimeBy(20 * 60_000L)
        runCurrent()
        assertEquals(at16Min, net.calls.size)
    }

    @Test
    fun sourceGuardMountsPaySignalInSolanaLayerAndPaymentFrame() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "core-solana/src/main/java").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("root not found") as Nothing
        val solDir = File(root, "core-solana/src/main/java/local/phantom/companion/core/solana")
        val layer = File(solDir, "SolanaLayer.kt").readText()
        val frame = File(solDir, "PaymentFrame.kt").readText()
        assertTrue(layer.contains("PaySignal(scope"))
        assertTrue(layer.contains("signal.watch(frame.cluster, PaymentFrame.refKey("))
        assertTrue(frame.contains("reference = ref?.let(PaymentFrame::refKey)"))
    }
}
