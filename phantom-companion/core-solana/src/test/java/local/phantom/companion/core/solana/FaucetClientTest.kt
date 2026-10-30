package local.phantom.companion.core.solana

import java.io.IOException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaucetClientTest {

    private val wallet = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"

    @Test
    fun drip200ParsesSentAndPostsWalletAndDevice() = runTest {
        var seenUrl: String? = null
        var seenBody: String? = null
        val client = FaucetClient("https://phantom.test/") { url, body ->
            seenUrl = url
            seenBody = body
            200 to """{"signature":"$sig","units":20000000,"cluster":"devnet","next_at":"2026-04-21T00:00:00Z"}"""
        }
        val res = client.drip(wallet, "device_12345678")
        assertEquals(FaucetResult.Sent(sig, 20_000_000L), res)
        assertEquals("https://phantom.test/api/solana/faucet", seenUrl)
        val json = SolanaRpc.JSON.parseToJsonElement(seenBody!!).jsonObject
        assertEquals(wallet.base58, json["wallet"]?.jsonPrimitive?.content)
        assertEquals("device_12345678", json["device"]?.jsonPrimitive?.content)
    }

    @Test
    fun drip503ReturnsOff() = runTest {
        val client = FaucetClient("https://phantom.test") { _, _ -> 503 to """{"detail":"faucet_off"}""" }
        assertEquals(FaucetResult.Off, client.drip(wallet, "device_12345678"))
    }

    @Test
    fun drip429ReturnsLimited() = runTest {
        val client = FaucetClient("https://phantom.test") { _, _ -> 429 to """{"detail":"faucet_weekly"}""" }
        assertEquals(FaucetResult.Limited, client.drip(wallet, "device_12345678"))
    }

    @Test
    fun drip500OrNetworkExceptionOrBlankBaseUrlReturnsUnavailable() = runTest {
        var calls = 0
        val http500 = FaucetClient("https://phantom.test") { _, _ ->
            calls++
            500 to """{"detail":"rpc_unreachable"}"""
        }
        assertEquals(FaucetResult.Unavailable, http500.drip(wallet, "device_12345678"))
        assertEquals(1, calls)

        val throwing = FaucetClient("https://phantom.test") { _, _ -> throw IOException("offline") }
        assertEquals(FaucetResult.Unavailable, throwing.drip(wallet, "device_12345678"))

        var blankCalls = 0
        val blank = FaucetClient("   ") { _, _ ->
            blankCalls++
            200 to "{}"
        }
        assertEquals(FaucetResult.Unavailable, blank.drip(wallet, "device_12345678"))
        assertEquals(0, blankCalls)
        assertTrue(calls == 1)
    }
}
