package local.phantom.companion.core.net.pair

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Доказ спарювання з ПК — на тому самому векторі, який читає pytest ПК
 * (`phantom-os/src/backend/tests/test_pair_mesh_vector.py`).
 *
 * Телефон підписує доказ разом зі своїм сітьовим блоком; ПК, що рахує без
 * нього, відповідає 400 bad_proof на кожне спарювання. Цей тест прибиває
 * байти з боку телефона, pytest — що ПК приймає саме їх.
 */
class PairMeshVectorTest {

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

    private fun mesh(key: String) = PairProofs.MeshBlock(
        peerId = text(key, "peer_id"),
        pubKeyB64 = text(key, "pub_ed25519_b64"),
        dhPubKeyB64 = text(key, "dh_x25519_b64"),
    )

    private val qr get() = PairingQr(
        v = 1,
        host = "phantom.local",
        ip = "192.168.1.10",
        port = 8000,
        pairId = text("pair_id"),
        serverPubB64 = text("server_x25519_pub_b64"),
        serverCertSha256 = "dev-no-pin",
        exp = Long.MAX_VALUE,
        nonce = text("nonce_b64"),
    )

    private fun proof(mesh: PairProofs.MeshBlock?) = PairProofs.buildClientProof(
        qr = qr,
        clientPriv = b64ToBytes(text("client_x25519_priv_b64")),
        devicePubEd25519Raw = b64ToBytes(text("device_pub_ed25519_b64")),
        mesh = mesh,
    )

    @Test fun `рядок HKDF той самий, що у векторі`() {
        assertEquals(text("hkdf_info"), String(PairProofs.HKDF_INFO, Charsets.UTF_8))
    }

    @Test fun `спільний ключ збігається з ПК`() {
        assertEquals(text("expected", "shared_key_b64"), bytesToB64(proof(null).sharedKey))
    }

    @Test fun `доказ телефона з сітьовим блоком — байт у байт як у векторі`() {
        assertEquals(text("expected", "client_proof_b64"), proof(mesh("phone_mesh")).clientProofB64)
    }

    @Test fun `без блоку доказ як у старої збірки`() {
        assertEquals(text("expected", "client_proof_without_mesh_b64"), proof(null).clientProofB64)
    }

    @Test fun `доказ ПК з ключами вузла приймається, підмінений — ні`() {
        val sharedKey = proof(null).sharedKey
        val node = mesh("node_mesh")
        assertTrue(
            PairProofs.verifyServerProof(sharedKey, text("device_jwt"), text("expected", "server_proof_b64"), node),
        )
        val swapped = node.copy(dhPubKeyB64 = text("phone_mesh", "dh_x25519_b64"))
        assertFalse(
            PairProofs.verifyServerProof(sharedKey, text("device_jwt"), text("expected", "server_proof_b64"), swapped),
        )
    }

    @Test fun `ПК шле ключі без набивки — телефон читає їх так само`() {
        val node = mesh("node_mesh")
        val bare = node.copy(
            pubKeyB64 = node.pubKeyB64.trimEnd('='),
            dhPubKeyB64 = node.dhPubKeyB64.trimEnd('='),
        )
        assertTrue(
            PairProofs.verifyServerProof(
                proof(null).sharedKey,
                text("device_jwt"),
                text("expected", "server_proof_b64"),
                bare,
            ),
        )
    }

    private companion object {
        const val VECTOR = "pair_mesh_vector.json"
    }
}
