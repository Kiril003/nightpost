package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.bytesToHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакет особи — на тому самому векторі, який відчиняє pytest ПК
 * (`phantom-os/src/backend/tests/test_own_self_handover.py`).
 *
 * Розбіжність тут не дає помилки на телефоні: ПК просто відповідає
 * `bad_seal`, і особа не переїжджає.
 */
class OwnSelfVectorTest {

    private val vector: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(VECTOR)) {
            "вектор $VECTOR не знайдено у test resources"
        }
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun text(vararg path: String): String {
        var node = vector
        for (i in 0 until path.size - 1) node = node[path[i]]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private val keys get() = OwnSelf.Keys(
        selfId = text("me", "self_id"),
        edSeed = b64ToBytes(text("me", "ed25519_seed_b64")),
        dhPrivate = b64ToBytes(text("me", "x25519_priv_b64")),
    )

    private fun sealVector() = OwnSelf.seal(
        serverPubB64 = text("server_x25519_pub_b64"),
        nonceB64 = text("nonce_b64"),
        pairId = text("pair_id"),
        nodeId = text("node_id"),
        keys = keys,
        clientPriv = b64ToBytes(text("client_x25519_priv_b64")),
        iv = b64ToBytes(text("iv_b64")),
    )

    @Test fun `мітка HKDF та сама, що у векторі`() {
        assertEquals(text("info"), OwnSelf.INFO)
    }

    @Test fun `імʼя — відбиток мого ключа підпису`() {
        val pub = Ed25519.publicKeyFromPrivate(keys.edSeed)
        assertEquals(text("me", "self_id"), OwnSelf.idOf(pub))
        assertTrue(keys.isWhole())
        assertFalse(keys.copy(selfId = "0123456789abcdef").isWhole())
    }

    @Test fun `публічний X25519 з приватного — як у векторі`() {
        assertEquals(
            text("me", "x25519_pub_b64"),
            bytesToB64(X25519.publicKeyFromPrivate(keys.dhPrivate)).trimEnd('='),
        )
    }

    @Test fun `ключ і AAD збігаються з ПК`() {
        val sealed = sealVector()
        assertEquals(text("expected", "key_b64"), bytesToB64(sealed.key))
        assertEquals(
            text("expected", "aad_b64"),
            bytesToB64(OwnSelf.aad(text("pair_id"), text("me", "self_id"), text("node_id"))),
        )
        assertEquals(text("client_x25519_pub_b64"), sealed.clientPubB64)
    }

    @Test fun `запечатаний пакет — байт у байт як у векторі`() {
        val sealed = sealVector()
        assertEquals(text("iv_b64"), sealed.ivB64)
        assertEquals(text("expected", "sealed_b64"), sealed.sealedB64)
    }

    @Test fun `доказ ПК перевіряється, підмінений — ні`() {
        val key = b64ToBytes(text("expected", "key_b64"))
        val proof = text("expected", "done_proof_b64")
        assertEquals(proof, OwnSelf.doneProof(key, text("pair_id"), text("me", "self_id"), text("node_id")))
        assertTrue(OwnSelf.verifyDone(key, text("pair_id"), text("me", "self_id"), text("node_id"), proof))
        assertFalse(OwnSelf.verifyDone(key, text("pair_id"), text("me", "self_id"), "0000000000000000", proof))
        assertFalse(OwnSelf.verifyDone(key, text("pair_id"), text("me", "self_id"), text("node_id"), "не base64"))
    }

    @Test fun `пакет відчиняється лише в тому самому сеансі`() {
        val server = b64ToBytes(text("server_x25519_priv_b64"))
        val sealed = sealVector()
        val opened = OwnSelf.open(
            serverPriv = server,
            clientPubB64 = sealed.clientPubB64,
            nonceB64 = text("nonce_b64"),
            pairId = text("pair_id"),
            selfId = text("me", "self_id"),
            nodeId = text("node_id"),
            ivB64 = sealed.ivB64,
            sealedB64 = sealed.sealedB64,
        )
        assertArrayEquals(keys.edSeed, opened!!.edSeed)
        assertArrayEquals(keys.dhPrivate, opened.dhPrivate)
        assertNull(
            OwnSelf.open(
                serverPriv = server,
                clientPubB64 = sealed.clientPubB64,
                nonceB64 = text("nonce_b64"),
                pairId = "own-self-vector-2",
                selfId = text("me", "self_id"),
                nodeId = text("node_id"),
                ivB64 = sealed.ivB64,
                sealedB64 = sealed.sealedB64,
            ),
        )
    }

    @Test fun `лист, підписаний моїм насінням, — той самий підпис, що зробить ПК`() {
        val letter = vector["letter"]!!.jsonObject
        val message = PeerMessage(
            id = letter["id"]!!.jsonPrimitive.content,
            fromId = letter["fromId"]!!.jsonPrimitive.content,
            toId = letter["toId"]!!.jsonPrimitive.content,
            body = letter["body"]!!.jsonPrimitive.content,
            sentAtMs = letter["sentAtMs"]!!.jsonPrimitive.long,
        )
        assertEquals(text("expected", "letter_signature"), PeerSealing.seal(message, keys.edSeed).signature)
    }

    @Test fun `ні особа, ні пакет не друкують насіння`() {
        val sealed = sealVector()
        val secrets = listOf(keys.edSeed, keys.dhPrivate, sealed.key).flatMap {
            listOf(bytesToHex(it), bytesToB64(it), bytesToB64(it).trimEnd('='))
        }
        for (shown in listOf(keys.toString(), sealed.toString())) {
            for (secret in secrets) assertFalse(shown, shown.contains(secret))
        }
    }

    private companion object {
        const val VECTOR = "own_self_vector.json"
    }
}
