package local.phantom.companion.core.net.pair

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingQrTest {

    private val sampleJson = """
        {
          "v": 1,
          "host": "phantom.local",
          "ip": "192.168.1.10",
          "port": 8000,
          "pair_id": "abcd1234",
          "server_pub": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
          "server_cert_sha256": "dev-no-pin",
          "exp": 9999999999,
          "nonce": "AAAAAAAAAAAAAAAA"
        }
    """.trimIndent()

    @Test fun `fromJson parses the canonical payload`() {
        val qr = PairingQr.fromJson(sampleJson)
        assertEquals(1, qr.v)
        assertEquals("phantom.local", qr.host)
        assertEquals("abcd1234", qr.pairId)
        assertTrue(qr.isDevNoPin)
        assertFalse(qr.isExpired { 1_700_000_000 })
    }

    @Test fun `dev-no-pin selects http scheme`() {
        val qr = PairingQr.fromJson(sampleJson)
        assertEquals("http://phantom.local:8000", qr.baseUrl())
    }

    @Test fun `non-dev cert pin selects https scheme`() {
        val qr = PairingQr.fromJson(
            sampleJson.replace("dev-no-pin", "deadbeef".repeat(8)),
        )
        assertEquals("https://phantom.local:8000", qr.baseUrl())
    }

    @Test fun `explicit scheme on host is honoured`() {
        val qr = PairingQr.fromJson(
            sampleJson.replace("phantom.local", "https://phantom.example.com"),
        )
        // Explicit scheme + host → port from the URL is dropped because
        // the operator already encoded it. The implementation simply
        // keeps the explicit URL.
        assertEquals("https://phantom.example.com", qr.baseUrl())
    }

    @Test fun `blank host falls back to ip`() {
        val qr = PairingQr.fromJson(
            sampleJson.replace("\"host\": \"phantom.local\"", "\"host\": \"\""),
        )
        assertEquals("http://192.168.1.10:8000", qr.baseUrl())
    }

    @Test fun `expired QR detected`() {
        val qr = PairingQr.fromJson(
            sampleJson.replace("9999999999", "1000000000"),
        )
        assertTrue(qr.isExpired { 2_000_000_000 })
    }

    @Test fun `wrong version rejected at parse time`() {
        try {
            PairingQr.fromJson(sampleJson.replace("\"v\": 1", "\"v\": 2"))
            fail("expected IllegalArgumentException for v=2")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `bad JSON rejected with IllegalArgumentException`() {
        try {
            PairingQr.fromJson("not json")
            fail("expected IllegalArgumentException for bad JSON")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test fun `port out of range rejected`() {
        try {
            PairingQr.fromJson(sampleJson.replace("\"port\": 8000", "\"port\": 0"))
            fail("expected IllegalArgumentException for port=0")
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    // ── Regression: candidateUrls returns BOTH host + ip for v1 ──────────
    // 2026-05-09 live E2E surfaced this. Phone could not resolve
    // `phantom.local` (mDNS off-domain) and the previous v1 path returned
    // a single URL with the host name, so claim never tried the raw IP.
    // Now both URLs come back and PairFlowClient retries on transport
    // failure (covered separately in PairFlowClientTest).

    @Test fun `v1 candidateUrls returns host then ip`() {
        val qr = PairingQr.fromJson(sampleJson)
        assertEquals(
            listOf("http://phantom.local:8000", "http://192.168.1.10:8000"),
            qr.candidateUrls(),
        )
    }

    @Test fun `v1 candidateUrls drops the ip when host already equals ip`() {
        // Backend's `_local_ip_guess()` may yield a UDP-probe IP that
        // matches the configured `host` field after mDNS resolution.
        // Ensure we don't return duplicate URLs.
        val qr = PairingQr.fromJson(
            sampleJson
                .replace("\"host\": \"phantom.local\"", "\"host\": \"192.168.1.10\"")
        )
        assertEquals(
            listOf("http://192.168.1.10:8000"),
            qr.candidateUrls(),
        )
    }

    @Test fun `v1 candidateUrls returns host only when ip is blank`() {
        val qr = PairingQr.fromJson(
            sampleJson.replace("\"ip\": \"192.168.1.10\"", "\"ip\": \"\"")
        )
        assertEquals(
            listOf("http://phantom.local:8000"),
            qr.candidateUrls(),
        )
    }

    @Test fun `baseUrl picks the first candidate (host first for v1)`() {
        val qr = PairingQr.fromJson(sampleJson)
        // baseUrl() is a thin wrapper over candidateUrls().first(), so
        // bumping the host-first ordering would break callers that still
        // assume the legacy single-URL semantics. Lock it.
        assertEquals("http://phantom.local:8000", qr.baseUrl())
    }
}
