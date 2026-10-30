package local.phantom.companion.core.net.pair

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.discovery.MdnsDiscovery
import local.phantom.companion.core.net.discovery.MdnsResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [PairDiscoveryClient] LAN-first / relay-fallback logic.
 *
 * NsdManager is an Android framework class — not available on the JVM test
 * runtime. We avoid it by using a fake [MdnsDiscovery] (null context, no
 * NsdManager calls) via a subclass that overrides [discoverFirst] with a
 * controllable coroutine result. MdnsDiscovery is `open` for exactly this.
 *
 * The Ktor surface is covered via [MockEngine] (same pattern as
 * [PairFlowClientTest]) — no real network required.
 */
class PairDiscoveryClientTest {

    // -----------------------------------------------------------------------
    // Fake MdnsDiscovery — avoids NsdManager / Android runtime dependency
    // -----------------------------------------------------------------------

    /** Returns a fixed [MdnsResult] immediately, simulating a LAN hit. */
    private fun fakeMdnsHit(host: String = "192.168.1.42", port: Int = 8000): MdnsDiscovery =
        object : MdnsDiscovery(null) {
            override suspend fun discoverFirst(timeoutMs: Long): MdnsResult =
                MdnsResult(host = host, port = port, txtRecords = mapOf("version" to "1", "app" to "PHANTOM OS"))
        }

    /** Always returns null, simulating no LAN service found within timeout. */
    private fun fakeMdnsMiss(): MdnsDiscovery =
        object : MdnsDiscovery(null) {
            override suspend fun discoverFirst(timeoutMs: Long): MdnsResult? = null
        }

    // -----------------------------------------------------------------------
    // Canonical QR JSON matching server contract (v1 format)
    // -----------------------------------------------------------------------

    private val qrJson = """
        {
          "v": 1,
          "host": "phantom.local",
          "ip": "192.168.1.42",
          "port": 8000,
          "pair_id": "test-pair-id-1234",
          "server_pub": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "server_cert_sha256": "dev-no-pin",
          "exp": 9999999999,
          "nonce": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
        }
    """.trimIndent()

    // -----------------------------------------------------------------------
    // Test: LAN hit — queries LAN host directly, never hits relay
    // -----------------------------------------------------------------------

    @Test fun `resolve uses LAN host when mDNS returns a result`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { req ->
            requestedUrls += req.url.toString()
            respond(
                content = qrJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = PairDiscoveryClient(
            engineInstance = engine,
            mdnsDiscovery = fakeMdnsHit(host = "192.168.1.42", port = 8000),
        )

        val result = client.resolve("12345678")

        assertTrue("resolve must succeed on LAN hit", result.isSuccess)
        assertEquals("test-pair-id-1234", result.getOrThrow().pairId)
        assertEquals("must have made exactly 1 request", 1, requestedUrls.size)
        assertTrue(
            "відкритого HTTP у мережі немає — код мусить іти по TLS: ${requestedUrls[0]}",
            requestedUrls[0].startsWith("https://192.168.1.42:8000/"),
        )
        assertTrue(
            "relay must NOT be contacted on LAN hit",
            requestedUrls.none { it.contains("meet.example") },
        )
    }

    // -----------------------------------------------------------------------
    // Код — доказ присутності поруч. Вузла в мережі немає — коду нема куди йти.
    // -----------------------------------------------------------------------

    @Test fun `a code cannot travel to the meeting point`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { req ->
            requestedUrls += req.url.toString()
            respond(
                content = qrJson,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = PairDiscoveryClient(
            engineInstance = engine,
            mdnsDiscovery = fakeMdnsMiss(),
        )

        val result = client.resolve("12345678")

        assertTrue("вузла поруч немає — код мусить чесно впасти", result.isFailure)
        assertTrue(
            "перебирати вісім цифр через точку зустрічі не можна",
            requestedUrls.none { it.contains("meet.example") },
        )
    }

    @Test fun `without mDNS a code has nowhere to go`() = runTest {
        var calls = 0
        val engine = MockEngine { _ ->
            calls++
            respond(qrJson, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = PairDiscoveryClient(engineInstance = engine)

        assertTrue(client.resolve("12345678").isFailure)
        assertEquals("жодного запиту в мережу", 0, calls)
    }

    @Test fun `a node that answers with an error is not routed around`() = runTest {
        val requestedUrls = mutableListOf<String>()
        val engine = MockEngine { req ->
            requestedUrls += req.url.toString()
            respondError(HttpStatusCode.NotFound)
        }
        val client = PairDiscoveryClient(
            engineInstance = engine,
            mdnsDiscovery = fakeMdnsHit(host = "192.168.1.42", port = 8000),
        )

        val result = client.resolve("12345678")

        assertTrue("невідомий код — це невідомий код", result.isFailure)
        assertEquals("рівно один запит: до вузла поруч", 1, requestedUrls.size)
        assertTrue(requestedUrls[0].startsWith("https://192.168.1.42:8000/"))
    }

    // -----------------------------------------------------------------------
    // Test: invalid PIN rejected before any network call
    // -----------------------------------------------------------------------

    @Test fun `resolve rejects invalid PIN before touching network`() = runTest {
        var callCount = 0
        val engine = MockEngine { _ ->
            callCount++
            respond("", HttpStatusCode.OK)
        }
        val client = PairDiscoveryClient(engineInstance = engine, mdnsDiscovery = fakeMdnsMiss())

        val result = client.resolve("abc")

        assertTrue("must fail on non-numeric PIN", result.isFailure)
        assertEquals("must not make any network call", 0, callCount)
    }
}
