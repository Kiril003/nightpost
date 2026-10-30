package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class RpcFailoverTest {

    private val ok = """{"jsonrpc":"2.0","id":1,"result":{"context":{"slot":1},"value":42}}"""

    @Test
    fun rateLimitedProviderFallsThroughToTheNextAndIsSkippedAfterwards() = runTest {
        val asked = mutableListOf<String>()
        val rpc = SolanaRpc(listOf("https://helius", "https://alchemy", "https://public")) { url, _ ->
            asked += url
            if (url == "https://helius") throw java.io.IOException("HTTP 429") else ok
        }
        assertEquals(42L, rpc.lamports(SolanaKey.of(ByteArray(32) { 1 })!!))
        assertEquals(42L, rpc.lamports(SolanaKey.of(ByteArray(32) { 1 })!!))
        assertEquals(listOf("https://helius", "https://alchemy", "https://alchemy"), asked)
    }

    @Test
    fun anAnswerFromTheChainIsNotRetriedElsewhere() = runTest {
        var n = 0
        val rpc = SolanaRpc(listOf("https://a", "https://b")) { _, _ ->
            n++
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32002,"message":"insufficient funds"}}"""
        }
        val e = runCatching { rpc.sendRaw(ByteArray(10)) }.exceptionOrNull()
        assertTrue(e is RpcError)
        assertEquals(1, n)
    }

    @Test
    fun aProviderThatRefusesInsideJsonRpcIsSkippedLikeAnHttpRefusal() = runTest {
        val asked = mutableListOf<String>()
        val rpc = SolanaRpc(listOf("https://ankr", "https://drpc", "https://public")) { url, _ ->
            asked += url
            when (url) {
                "https://ankr" -> """{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Unauthorized: You must authenticate your request with an API key."}}"""
                "https://drpc" -> """{"id":1,"jsonrpc":"2.0","error":{"message":"chain is not available on free plan","code":35}}"""
                else -> ok
            }
        }
        assertEquals(42L, rpc.lamports(SolanaKey.of(ByteArray(32) { 1 })!!))
        assertEquals(listOf("https://ankr", "https://drpc", "https://public"), asked)
    }

    @Test
    fun aLaggingNodeIsSkippedButAMissingAccountIsAnAnswer() = runTest {
        var n = 0
        val lagging = SolanaRpc(listOf("https://behind", "https://fresh")) { url, _ ->
            n++
            if (url == "https://behind") """{"jsonrpc":"2.0","id":1,"error":{"code":-32005,"message":"Node is behind by 120 slots"}}""" else ok
        }
        assertEquals(42L, lagging.lamports(SolanaKey.of(ByteArray(32) { 1 })!!))
        assertEquals(2, n)

        n = 0
        val missing = SolanaRpc(listOf("https://a", "https://b")) { _, _ ->
            n++
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Invalid param: could not find account"}}"""
        }
        assertEquals(0L, missing.tokenUnits(SolanaKey.of(ByteArray(32) { 1 })!!, Coin.USDC.mint(Cluster.DEVNET)!!))
        assertEquals(1, n)
    }

    @Test
    fun sendingIsPreflightedAgainstTheSameCommitmentAsTheBlockhash() = runTest {
        var sent = ""
        val rpc = SolanaRpc("https://a") { _, body ->
            sent = body
            """{"jsonrpc":"2.0","id":1,"result":"sig"}"""
        }
        rpc.sendRaw(ByteArray(10))
        val opts = SolanaRpc.JSON.parseToJsonElement(sent).jsonObject["params"]!!.jsonArray[1].jsonObject
        assertEquals("confirmed", opts["preflightCommitment"]!!.jsonPrimitive.content)
    }

    @Test
    fun ourServerGoesFirstThenKeyedNodesThenThePublicOne() {
        assertEquals(
            listOf("https://phantom-os.dev/api/solana/rpc/mainnet", "https://helius.example/?api-key=k", Cluster.MAINNET.publicRpc),
            SolanaRpc.ladder("https://phantom-os.dev/", " https://helius.example/?api-key=k , http://plain.example, ", Cluster.MAINNET),
        )
        assertEquals("https://phantom-os.dev/api/solana/rpc/devnet", SolanaRpc.ladder("https://phantom-os.dev", "", Cluster.DEVNET).first())
    }

    @Test
    fun aBuildWithoutOurServerTalksToNodesDirectly() {
        assertEquals(listOf(Cluster.DEVNET.publicRpc), SolanaRpc.ladder("", "", Cluster.DEVNET))
        assertEquals(listOf(Cluster.DEVNET.publicRpc), SolanaRpc.ladder("  ", "", Cluster.DEVNET))
    }

    @Test
    fun everyRefusalFromOurProxySendsThePhoneToTheNextNode() = runTest {
        val refusals = listOf(
            503 to "rpc proxy is not configured",
            403 to "method_not_allowed",
            429 to "rate_limited",
            502 to "upstream_unreachable",
            504 to "upstream_timeout",
        )
        for ((code, detail) in refusals) {
            TinyHttp { path -> if (path.startsWith("/api/solana/rpc/")) code to """{"detail":"$detail"}""" else 200 to ok }.use { http ->
                val proxy = SolanaRpc.ladder(http.base, "", Cluster.DEVNET).first()
                val rpc = SolanaRpc(listOf(proxy, "${http.base}/node"))
                assertEquals("after $code", 42L, rpc.lamports(SolanaKey.of(ByteArray(32) { 1 })!!))
                assertEquals("after $code", listOf("/api/solana/rpc/devnet", "/node"), http.asked)
            }
        }
    }

    /** Справжній HTTP на петлі: HttpRpcTransport бачить той самий код і тіло, що й від нашого сервера. */
    private class TinyHttp(private val reply: (String) -> Pair<Int, String>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:${server.localPort}"
        val asked = CopyOnWriteArrayList<String>()

        init {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    socket.use { serve(it) }
                }
            }
        }

        private fun serve(socket: java.net.Socket) {
            val input = socket.getInputStream().bufferedReader()
            val path = input.readLine()?.split(' ')?.getOrNull(1) ?: return
            var length = 0
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("content-length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
            }
            val body = CharArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            asked += path
            val (code, text) = reply(path)
            val bytes = text.toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 $code R\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }

        override fun close() = server.close()
    }
}
