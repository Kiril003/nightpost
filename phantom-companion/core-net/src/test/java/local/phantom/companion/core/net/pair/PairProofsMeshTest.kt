package local.phantom.companion.core.net.pair

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.bytesToB64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Сітьові ключі всередині доказу `mobile-pair-v1`, а не поруч із ним.
 *
 * Доти паринг обмінювався лише ключами САМОГО паринга — одноразовим X25519 і
 * Ed25519 для `/pair/refresh`. Довічний X25519, з якого виводиться адреса
 * скриньки у сховку, не їхав нікуди, тож ПК і телефон рахували РІЗНІ адреси й
 * жоден не мав як про це дізнатись.
 */
class PairProofsMeshTest {

    private val server = X25519.generateKeypair()
    private val nonceBytes = ByteArray(16) { (it + 0x11).toByte() }
    private val pairId = "pair-mesh-1"

    private val qr = PairingQr(
        v = 1,
        host = "phantom.local",
        ip = "192.168.1.10",
        port = 8000,
        pairId = pairId,
        serverPubB64 = bytesToB64(server.publicKey),
        serverCertSha256 = "dev-no-pin",
        exp = Long.MAX_VALUE,
        nonce = bytesToB64(nonceBytes),
    )

    private val meshDh = X25519.generateKeypair()
    private val meshEd = Ed25519.generateKeypair()

    private val mesh = PairProofs.MeshBlock(
        peerId = "a1b2c3d4e5f60718",
        pubKeyB64 = bytesToB64(meshEd.publicKey),
        dhPubKeyB64 = bytesToB64(meshDh.publicKey),
    )

    private fun sharedKeyOf(ephemeralPriv: ByteArray): ByteArray = Hkdf.derive(
        ikm = X25519.ecdh(ephemeralPriv, server.publicKey),
        salt = nonceBytes,
        info = PairProofs.HKDF_INFO,
        length = 32,
    )

    private fun hmac(key: ByteArray, message: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(message)
        }

    @Test fun `хвіст доказу — імʼя і два СИРІ ключі`() {
        assertArrayEquals(
            mesh.peerId.toByteArray(Charsets.UTF_8) + meshEd.publicKey + meshDh.publicKey,
            PairProofs.meshTail(mesh),
        )
    }

    @Test fun `без блоку хвіст порожній — телефон старої збірки лишається сумісним`() {
        assertEquals(0, PairProofs.meshTail(null).size)
        assertEquals(0, PairProofs.meshTail(PairProofs.MeshBlock("", "", "")).size)
    }

    @Test fun `сервер відтворює доказ із блоком`() {
        val ephemeral = X25519.generateKeypair()
        val device = Ed25519.generateKeypair()
        val proof = PairProofs.buildClientProof(
            qr = qr,
            clientPriv = ephemeral.privateKey,
            devicePubEd25519Raw = device.publicKey,
            mesh = mesh,
        )
        val expected = hmac(
            sharedKeyOf(ephemeral.privateKey),
            pairId.toByteArray(Charsets.UTF_8) + device.publicKey +
                mesh.peerId.toByteArray(Charsets.UTF_8) + meshEd.publicKey + meshDh.publicKey,
        )
        assertEquals(bytesToB64(expected), proof.clientProofB64)
    }

    @Test fun `відщипнути блок від запиту не вийде`() {
        // Посередник, що прибрав сітьові поля, лишив би вузол без ключа й
        // сховок — без спільної адреси. Доказ мусить перестати сходитись.
        val ephemeral = X25519.generateKeypair()
        val device = Ed25519.generateKeypair()
        val withMesh = PairProofs.buildClientProof(
            qr, ephemeral.privateKey, device.publicKey, mesh,
        )
        val withoutMesh = PairProofs.buildClientProof(
            qr, ephemeral.privateKey, device.publicKey, null,
        )
        assertFalse(withMesh.clientProofB64 == withoutMesh.clientProofB64)
    }

    @Test fun `підмінений ключ вузла валить перевірку відповіді`() {
        val sharedKey = ByteArray(32) { 5 }
        val jwt = "device.jwt.value"
        val node = PairProofs.MeshBlock(
            peerId = "2b038b02f23b2ce79479256413a3efca",
            pubKeyB64 = bytesToB64(meshEd.publicKey),
            dhPubKeyB64 = bytesToB64(meshDh.publicKey),
        )
        val honest = bytesToB64(
            hmac(sharedKey, jwt.toByteArray(Charsets.UTF_8) + PairProofs.meshTail(node)),
        )
        assertTrue(PairProofs.verifyServerProof(sharedKey, jwt, honest, node))

        val swapped = node.copy(dhPubKeyB64 = bytesToB64(X25519.generateKeypair().publicKey))
        assertFalse(PairProofs.verifyServerProof(sharedKey, jwt, honest, swapped))
    }

    @Test fun `відповідь без блоку перевіряється як раніше`() {
        val sharedKey = ByteArray(32) { 9 }
        val jwt = "legacy.jwt"
        val proof = bytesToB64(hmac(sharedKey, jwt.toByteArray(Charsets.UTF_8)))
        assertTrue(PairProofs.verifyServerProof(sharedKey, jwt, proof))
    }
}
