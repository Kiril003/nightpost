package local.phantom.companion.core.net.pair

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.fullPath
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairFlowClientTest {

    private val canonicalSuccessBody = """
        {
          "device_jwt": "header.payload.sig",
          "device_id": "dev-123",
          "expires_at": "2026-06-04T00:00:00Z",
          "server_proof": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
          "user": {
            "id": "user-1",
            "username": "kiril",
            "role": "ROOT",
            "avatar_url": null
          },
          "profile": {
            "id": "profile-1",
            "user_id": "user-1",
            "display_name": "Кирило",
            "role": "OPERATOR",
            "avatar_uri": null,
            "is_primary": true
          }
        }
    """.trimIndent()

    private fun freshServerQr(pairId: String = "session-abcd-1234"): PairingQr {
        val server = X25519.generateKeypair()
        return PairingQr(
            v = 1,
            host = "phantom.local",
            ip = "192.168.1.10",
            port = 8000,
            pairId = pairId,
            serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE,
            nonce = bytesToB64(ByteArray(16) { it.toByte() }),
        )
    }

    @Test fun `claim posts to dev-no-pin host with proof + parses response`() = runTest {
        val captured = mutableListOf<JsonObject>()
        val engine = MockEngine { req ->
            assertEquals("http://phantom.local:8000/api/v1/pair/claim", "${req.url.protocol.name}://${req.url.host}:${req.url.port}${req.url.fullPath}")
            // Capture the JSON body for assertions.
            val text = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            captured += Json.parseToJsonElement(text).let { it as JsonObject }
            respond(
                content = canonicalSuccessBody,
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = PairFlowClient(engine)
        try {
            val outcome = client.claim(
                qr = freshServerQr(),
                device = DeviceInfo(name = "Test Pixel", model = "test/v1"),
                profileDisplayName = "Кирило",
            )
            assertEquals("dev-123", outcome.response.deviceId)
            assertEquals("profile-1", outcome.response.profile.id)
            assertEquals("Кирило", outcome.response.profile.displayName)
            assertTrue(outcome.response.profile.isPrimary)
            // Outgoing body carries every required field.
            val body = captured.single()
            assertEquals("session-abcd-1234", body["pair_id"]!!.jsonPrimitive.content)
            assertNotNull(body["client_pub"])
            assertNotNull(body["device_pub_ed25519"])
            assertNotNull(body["client_proof"])
            assertEquals("Кирило", body["profile_display_name"]!!.jsonPrimitive.content)
            // Device key returned to caller is round-trippable to 32 bytes.
            assertEquals(32, b64ToBytes(outcome.deviceEd25519PrivateKeyB64).size)
        } finally {
            client.close()
        }
    }

    @Test fun `claim raises PairFlowException on HTTP 400 with the body text`() = runTest {
        val engine = MockEngine { _ ->
            respondError(
                status = HttpStatusCode.BadRequest,
                content = """{"detail":{"code":"bad_proof"}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = PairFlowClient(engine)
        try {
            client.claim(
                qr = freshServerQr(),
                device = DeviceInfo(name = "x", model = "y"),
            )
            fail("expected PairFlowException for HTTP 400")
        } catch (t: PairFlowException) {
            assertTrue("error message must include status", t.message!!.contains("400"))
            assertTrue("error message must include body", t.message!!.contains("bad_proof"))
        } finally {
            client.close()
        }
    }

    @Test fun `claim rejects an expired QR before opening a connection`() = runTest {
        var requestCount = 0
        val engine = MockEngine { _ ->
            requestCount++
            respond(canonicalSuccessBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = PairFlowClient(engine)
        try {
            val server = X25519.generateKeypair()
            val expired = PairingQr(
                v = 1,
                host = "phantom.local",
                ip = "192.168.1.10",
                port = 8000,
                pairId = "stale",
                serverPubB64 = bytesToB64(server.publicKey),
                serverCertSha256 = "dev-no-pin",
                // Already in the past relative to wall clock.
                exp = 0,
                nonce = bytesToB64(ByteArray(16) { 0 }),
            )
            try {
                client.claim(qr = expired, device = DeviceInfo(name = "x", model = "y"))
                fail("expected PairFlowException for expired QR")
            } catch (t: PairFlowException) {
                assertTrue(t.message!!.contains("expired"))
            }
            assertEquals("must NOT open a connection on a stale QR", 0, requestCount)
        } finally {
            client.close()
        }
    }

    @Test fun `claim wraps profile_id when the caller supplies one`() = runTest {
        val captured = mutableListOf<JsonObject>()
        val engine = MockEngine { req ->
            val text = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes()
                .decodeToString()
            captured += Json.parseToJsonElement(text).let { it as JsonObject }
            respond(canonicalSuccessBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = PairFlowClient(engine)
        try {
            client.claim(
                qr = freshServerQr(),
                device = DeviceInfo(name = "x", model = "y"),
                profileId = "profile-99",
            )
            assertEquals("profile-99", captured.single()["profile_id"]!!.jsonPrimitive.content)
        } finally {
            client.close()
        }
    }

    // -----------------------------------------------------------------------
    // Multi-endpoint retry semantics — COMPANION_CONTRACT.md §10.4.
    //
    // The phone tries each candidate URL in order. A transport failure
    // (ConnectException, timeout) means the server never saw the request →
    // safe to fall through to the next endpoint. An HTTP-level rejection
    // means the server consumed the single-shot pair session → re-trying
    // would always 410, so we propagate immediately.
    // -----------------------------------------------------------------------

    private fun freshServerQrV2(
        endpoints: List<PairingQr.Endpoint>,
        pairId: String = "session-v2-1234",
    ): PairingQr {
        val server = X25519.generateKeypair()
        return PairingQr(
            v = 2,
            pairId = pairId,
            serverPubB64 = bytesToB64(server.publicKey),
            serverCertSha256 = "dev-no-pin",
            exp = Long.MAX_VALUE,
            nonce = bytesToB64(ByteArray(16) { (it + 1).toByte() }),
            endpoints = endpoints,
        )
    }

    @Test fun `claim falls through to the tunnel when LAN endpoint refuses connection`() = runTest {
        val seenHosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            seenHosts += req.url.host
            if (req.url.host == "lan.local") {
                // ConnectException is the canonical "server never saw us"
                // signal — PairFlowClient must treat it as retry-eligible.
                throw java.net.ConnectException("connection refused at lan.local")
            }
            respond(canonicalSuccessBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = PairFlowClient(engine)
        try {
            val outcome = client.claim(
                qr = freshServerQrV2(
                    endpoints = listOf(
                        PairingQr.Endpoint(type = "lan",   url = "http://lan.local:8000"),
                        PairingQr.Endpoint(
                            type = "relay",
                            url = "https://meet.example",
                            nodeId = "abc123",
                        ),
                    ),
                ),
                device = DeviceInfo(name = "x", model = "y"),
                routes = local.phantom.companion.core.net.relay.NodeRoutes { relay, node ->
                    assertEquals("https://meet.example", relay)
                    assertEquals("abc123", node)
                    "http://127.0.0.1:44444"
                },
            )
            assertEquals(listOf("lan.local", "127.0.0.1"), seenHosts)
            assertEquals(
                "successfulBaseUrl must reflect the endpoint that actually answered",
                "http://127.0.0.1:44444",
                outcome.successfulBaseUrl,
            )
        } finally {
            client.close()
        }
    }

    @Test fun `a relay endpoint is never dialled directly`() = runTest {
        val seenHosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            seenHosts += req.url.host
            throw java.net.ConnectException("refused")
        }
        val client = PairFlowClient(engine)
        try {
            client.claim(
                qr = freshServerQrV2(
                    endpoints = listOf(
                        PairingQr.Endpoint(type = "lan",   url = "http://lan.local:8000"),
                        PairingQr.Endpoint(
                            type = "relay",
                            url = "https://meet.example",
                            nodeId = "abc123",
                        ),
                    ),
                ),
                device = DeviceInfo(name = "x", model = "y"),
            )
            fail("expected PairFlowException — every endpoint refused")
        } catch (t: PairFlowException) {
            assertEquals(
                "the relay's own address hosts no node — only a tunnel reaches it",
                listOf("lan.local"),
                seenHosts,
            )
        } finally {
            client.close()
        }
    }

    @Test fun `claim does NOT retry next endpoint on HTTP rejection (single-shot session)`() = runTest {
        val seenHosts = mutableListOf<String>()
        val engine = MockEngine { req ->
            seenHosts += req.url.host
            // The server consumed the session and now returns 410 — the
            // phone must NOT try the next endpoint, since it would also
            // 410 with no useful diagnostic.
            respondError(
                status = HttpStatusCode.Gone,
                content = """{"detail":{"code":"session_expired"}}""",
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val client = PairFlowClient(engine)
        try {
            client.claim(
                qr = freshServerQrV2(
                    endpoints = listOf(
                        PairingQr.Endpoint(type = "lan",   url = "http://lan.local:8000"),
                        PairingQr.Endpoint(type = "relay", url = "https://meet.example"),
                    ),
                ),
                device = DeviceInfo(name = "x", model = "y"),
            )
            fail("expected PairFlowException for HTTP 410")
        } catch (t: PairFlowException) {
            assertTrue("error must include 410 — got: ${t.message}", t.message!!.contains("410"))
            assertEquals(
                "must NOT contact the relay after server consumed the session",
                listOf("lan.local"),
                seenHosts,
            )
        } finally {
            client.close()
        }
    }

    @Test fun `claim outcome carries 32-byte shared key + 32-byte device public key`() = runTest {
        val engine = MockEngine { _ ->
            respond(canonicalSuccessBody, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val client = PairFlowClient(engine)
        try {
            val outcome = client.claim(
                qr = freshServerQr(),
                device = DeviceInfo(name = "x", model = "y"),
            )
            // The HKDF output is 32 bytes per RFC 5869 with SHA-256 + L=32;
            // a regression in PairProofs.buildClientProof would surface here.
            assertEquals(32, outcome.sharedKey.size)
            // Ed25519 raw public key is 32 bytes; raw private key is also
            // 32 bytes. Round-trip both via b64 to lock the shape.
            assertEquals(32, b64ToBytes(outcome.deviceEd25519PublicKeyB64).size)
            assertEquals(32, b64ToBytes(outcome.deviceEd25519PrivateKeyB64).size)
            // successfulBaseUrl matches the v1 legacy URL derivation.
            assertEquals("http://phantom.local:8000", outcome.successfulBaseUrl)
        } finally {
            client.close()
        }
    }
}
