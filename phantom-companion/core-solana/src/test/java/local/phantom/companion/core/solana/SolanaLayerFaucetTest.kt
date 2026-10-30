package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SolanaLayerFaucetTest {

    private val me = SolanaKey.of("BxsSMk2r7BPxLChLdAE7Jv7UuDbZbaBRis31PgWTDfJH")
    private val sig = "4WVSP661Y7sGVtg64ScknvfBFVX5rV2UqBaFRWmFwLxZJCGzdtEAnC1psHCiTYnxZgEg1KWMTDgP5o7YvJL6mZHT"

    private fun config(selfId: String? = "device_12345678") = SolanaConfig(
        licencePurchase = false,
        licenceBaseUrl = "",
        activateLicence = { false },
        contacts = { emptyList() },
        send = { _, _ -> true },
        sponsorBaseUrl = "https://phantom.test",
        selfId = { selfId },
    )

    @Test
    fun devnetSentRefreshesBalancesAndResetsToReady() = runTest {
        val observedStatuses = mutableListOf<FaucetStatus>()
        var faucetCalls = 0
        var balanceCalls = 0
        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = HttpCall { url, _ ->
            if (url.endsWith("/api/solana/faucet")) {
                faucetCalls++
                observedStatuses += SolanaLayer.state.value.faucet
                200 to """{"signature":"$sig","units":20000000,"cluster":"devnet"}"""
            } else {
                503 to "{}"
            }
        }
        SolanaLayer.rpcTransport = RpcTransport { _, body ->
            when {
                "getTokenAccountBalance" in body -> {
                    balanceCalls++
                    """{"jsonrpc":"2.0","id":1,"result":{"value":{"amount":"20000000"}}}"""
                }
                "getBalance" in body -> """{"jsonrpc":"2.0","id":1,"result":{"value":0}}"""
                "getTokenAccountsByOwner" in body -> """{"jsonrpc":"2.0","id":1,"result":{"value":[]}}"""
                else -> """{"jsonrpc":"2.0","id":1,"result":null}"""
            }
        }

        SolanaLayer.requestTestUsdc()

        assertEquals(1, faucetCalls)
        assertEquals(listOf(FaucetStatus.SENDING), observedStatuses)
        assertEquals(FaucetStatus.READY, SolanaLayer.state.value.faucet)
        assertTrue(balanceCalls > 0)
        assertEquals(20_000_000L, SolanaLayer.state.value.balances?.get(Coin.USDC))
    }

    @Test
    fun devnetOffSetsFaucetStatusOff() = runTest {
        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = HttpCall { _, _ -> 503 to """{"detail":"faucet_off"}""" }

        SolanaLayer.requestTestUsdc()

        assertEquals(FaucetStatus.OFF, SolanaLayer.state.value.faucet)
    }

    @Test
    fun devnetLimitedSetsFaucetStatusLimited() = runTest {
        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = HttpCall { _, _ -> 429 to """{"detail":"faucet_weekly"}""" }

        SolanaLayer.requestTestUsdc()

        assertEquals(FaucetStatus.LIMITED, SolanaLayer.state.value.faucet)
    }

    @Test
    fun unreachableServerLeavesRetryNotSilentReadyAndNextTapAsksAgain() = runTest {
        var calls = 0
        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = HttpCall { _, _ -> calls++; throw java.io.IOException("unreachable") }

        SolanaLayer.requestTestUsdc()
        assertEquals(1, calls)
        assertEquals(FaucetStatus.RETRY, SolanaLayer.state.value.faucet)

        SolanaLayer.http = HttpCall { _, _ -> calls++; 502 to "{}" }
        SolanaLayer.requestTestUsdc()
        assertEquals(2, calls)
        assertEquals(FaucetStatus.RETRY, SolanaLayer.state.value.faucet)
    }

    @Test
    fun blankServerAddressAlsoLeavesRetry() = runTest {
        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            SolanaConfig(
                licencePurchase = false,
                licenceBaseUrl = "",
                activateLicence = { false },
                contacts = { emptyList() },
                send = { _, _ -> true },
                sponsorBaseUrl = "",
                selfId = { "device_12345678" },
            ),
        )
        SolanaLayer.requestTestUsdc()
        assertEquals(FaucetStatus.RETRY, SolanaLayer.state.value.faucet)
    }

    @Test
    fun invalidSelfIdOnDevnetResetsStuckOffOrLimitedToReadyWithoutCall() = runTest {
        var calls = 0
        for (stuck in listOf(FaucetStatus.OFF, FaucetStatus.LIMITED)) {
            for (selfId in listOf("   ", "short", "bad id with spaces", null)) {
                SolanaLayer.testReset(
                    WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET, faucet = stuck),
                    config(selfId),
                )
                SolanaLayer.http = HttpCall { _, _ -> calls++; 503 to "{}" }

                SolanaLayer.requestTestUsdc()

                assertEquals("$stuck / $selfId", FaucetStatus.READY, SolanaLayer.state.value.faucet)
            }
        }
        assertEquals(0, calls)
    }

    @Test
    fun mainnetExternalOrBlankSelfIdNeverCallsFaucetWhileValidDevnetBuiltInDoes() = runTest {
        var calls = 0
        val counting = HttpCall { url, _ ->
            if (url.endsWith("/api/solana/faucet")) calls++
            503 to """{"detail":"faucet_off"}"""
        }

        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.MAINNET),
            config("device_12345678"),
        )
        SolanaLayer.http = counting
        SolanaLayer.requestTestUsdc()
        assertEquals(0, calls)
        assertEquals(FaucetStatus.READY, SolanaLayer.state.value.faucet)

        SolanaLayer.testReset(
            WalletState(kind = WalletKind.EXTERNAL, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = counting
        SolanaLayer.requestTestUsdc()
        assertEquals(0, calls)
        assertEquals(FaucetStatus.READY, SolanaLayer.state.value.faucet)

        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("   "),
        )
        SolanaLayer.http = counting
        SolanaLayer.requestTestUsdc()
        assertEquals(0, calls)
        assertEquals(FaucetStatus.READY, SolanaLayer.state.value.faucet)

        SolanaLayer.testReset(
            WalletState(kind = WalletKind.BUILT_IN, address = me, builtIn = me, cluster = Cluster.DEVNET),
            config("device_12345678"),
        )
        SolanaLayer.http = counting
        SolanaLayer.requestTestUsdc()
        assertEquals(1, calls)
        assertEquals(FaucetStatus.OFF, SolanaLayer.state.value.faucet)
    }
}
