package local.phantom.companion.core.net.pair

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.peer.OwnSelf
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfHandoverClientTest {

    private val server = X25519.generateKeypair()
    private val nonce = ByteArray(24) { (it + 1).toByte() }
    private val me: OwnSelf.Keys = run {
        val ed = Ed25519.generateKeypair()
        OwnSelf.Keys(OwnSelf.idOf(ed.publicKey), ed.privateKey, X25519.generateKeypair().privateKey)
    }

    private fun qr(purpose: String = "self", nodeId: String = NODE, exp: Long = Long.MAX_VALUE) = PairingQr(
        v = 1,
        host = "phantom.local",
        ip = "192.168.1.10",
        port = 8000,
        pairId = "self-session-1",
        serverPubB64 = bytesToB64(server.publicKey),
        serverCertSha256 = "dev-no-pin",
        exp = exp,
        nonce = bytesToB64(nonce),
        purpose = purpose,
        nodeId = nodeId,
    )

    private fun ok(body: String) = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()).let {
        body to it
    }

    /** ПК, що чесно відчиняє пакет і відповідає доказом над ключем сеансу. */
    private fun honestPc(seen: MutableList<String>, forgeProof: Boolean = false) = MockEngine { req ->
        val text = (req.body as io.ktor.http.content.OutgoingContent.ByteArrayContent).bytes().decodeToString()
        seen += "${req.url.encodedPath}|$text"
        val body = Json.parseToJsonElement(text).jsonObject
        fun f(k: String) = body[k]!!.jsonPrimitive.content
        val opened = OwnSelf.open(
            serverPriv = server.privateKey,
            clientPubB64 = f("client_pub"),
            nonceB64 = f("nonce_echo"),
            pairId = f("pair_id"),
            selfId = f("self_id"),
            nodeId = f("node_id"),
            ivB64 = f("iv"),
            sealedB64 = f("sealed"),
        )!!
        assertArrayEquals(me.edSeed, opened.edSeed)
        val key = OwnSelf.key(server.privateKey, b64ToBytes(f("client_pub")), nonce)
        val proof = OwnSelf.doneProof(key, f("pair_id"), f("self_id"), if (forgeProof) "x" else f("node_id"))
        val (content, headers) = ok("""{"self_id":"${f("self_id")}","node_id":"${f("node_id")}","proof":"$proof"}""")
        respond(content, HttpStatusCode.OK, headers)
    }

    @Test fun `особа їде запечатаною на той ПК, що в QR, і ПК доводить прийом`() = runTest {
        val seen = mutableListOf<String>()
        val result = SelfHandoverClient(honestPc(seen)).handOver(qr(), me)
        assertEquals(SelfHandoverClient.Result.Done(me.selfId, NODE), result)
        assertEquals(1, seen.size)
        assertTrue(seen.single().startsWith("/api/v1/pair/self|"))
    }

    @Test fun `насіння не їде відкритим у жодному записі`() = runTest {
        val seen = mutableListOf<String>()
        SelfHandoverClient(honestPc(seen)).handOver(qr(), me)
        val wire = seen.single()
        for (secret in listOf(me.edSeed, me.dhPrivate)) {
            for (spelling in listOf(bytesToHex(secret), bytesToB64(secret), bytesToB64(secret).trimEnd('='))) {
                assertFalse(wire.contains(spelling))
            }
        }
    }

    @Test fun `доказ не над тим вузлом — не «передано»`() = runTest {
        val result = SelfHandoverClient(honestPc(mutableListOf(), forgeProof = true)).handOver(qr(), me)
        assertEquals(SelfHandoverClient.Result.Refused("bad_proof"), result)
    }

    @Test fun `QR спарювання, а не особи, — жодного запиту`() = runTest {
        val seen = mutableListOf<String>()
        val result = SelfHandoverClient(honestPc(seen)).handOver(qr(purpose = ""), me)
        assertEquals(SelfHandoverClient.Result.Refused("not_self_qr"), result)
        assertTrue(seen.isEmpty())
    }

    @Test fun `прострочений QR — жодного запиту`() = runTest {
        val seen = mutableListOf<String>()
        val result = SelfHandoverClient(honestPc(seen)).handOver(qr(exp = 1L), me)
        assertEquals(SelfHandoverClient.Result.Refused("expired"), result)
        assertTrue(seen.isEmpty())
    }

    @Test fun `відмова ПК — з її кодом і без повтору на іншій адресі`() = runTest {
        var calls = 0
        val engine = MockEngine {
            calls++
            respond(
                """{"detail":{"code":"session_expired"}}""",
                HttpStatusCode.Gone,
                headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
        val result = SelfHandoverClient(engine).handOver(qr(), me)
        assertEquals(SelfHandoverClient.Result.Refused("session_expired"), result)
        assertEquals(1, calls)
    }

    @Test fun `особа з чужим іменем не їде взагалі`() = runTest {
        val seen = mutableListOf<String>()
        val result = SelfHandoverClient(honestPc(seen)).handOver(qr(), me.copy(selfId = "0123456789abcdef"))
        assertEquals(SelfHandoverClient.Result.Refused("bad_identity"), result)
        assertTrue(seen.isEmpty())
    }

    private companion object {
        const val NODE = "37e70f24bd956f9f"
    }
}
