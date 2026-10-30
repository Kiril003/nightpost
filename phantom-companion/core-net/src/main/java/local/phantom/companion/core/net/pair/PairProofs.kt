package local.phantom.companion.core.net.pair

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64

/**
 * Pair-flow proof construction. Mirrors the server-side helpers in
 * `PHANTOM OS/src/backend/security/pair_crypto.py`:
 *
 *   shared_key = HKDF-SHA256(
 *       ikm  = X25519-ECDH(client_priv, server_pub),
 *       salt = nonce_bytes,
 *       info = b"PHANTOM OS/mobile-pair-v1",
 *       length = 32,
 *   )
 *   client_proof = HMAC-SHA256(shared_key, pair_id_utf8 || device_pub_raw)
 *
 * The HKDF info string MUST stay byte-equivalent to
 * `pair_crypto._HKDF_INFO`; if either side changes the string, the
 * shared key derivation diverges and every pair-claim becomes a 400.
 *
 * Both halves of the pair flow live in `:core-net` because the proof
 * construction only depends on `crypto/` primitives; pulling Ktor or
 * camera deps in here would needlessly expand the surface.
 */
object PairProofs {

    /** HKDF info string — must match `_HKDF_INFO` in pair_crypto.py. */
    val HKDF_INFO: ByteArray =
        "PHANTOM OS/mobile-pair-v1".toByteArray(Charsets.UTF_8)

    /**
     * Хто цей бік У СІТЦІ — не в парингу.
     *
     * Ключі парингу одноразові: X25519 живе один claim, Ed25519 підписує лише
     * `/pair/refresh`. Адреси сховка PH5 виводяться зовсім з іншої пари —
     * довічної identity телефона (DeviceIdentityStore) і довічної identity
     * вузла. Доки цей блок не їхав у `mobile-pair-v1`, ані ПК, ані телефон не
     * мали чим скласти спільний ключ каналу: обидва чесно рахували адреси, і
     * адреси були різні. Мовчки.
     *
     * @property peerId 16 hex — відбиток [pubKeyB64] за правилом телефона, і
     *           в телефона, і у вузла (`phone_peers.phone_self`). Входить у сіль
     *           ключа каналу, тому мусить бути тим самим рядком в обох деревах.
     */
    data class MeshBlock(
        val peerId: String,
        val pubKeyB64: String,
        val dhPubKeyB64: String,
    ) {
        val isComplete: Boolean
            get() = peerId.isNotBlank() && pubKeyB64.isNotBlank() && dhPubKeyB64.isNotBlank()
    }

    /**
     * Result of a successful proof construction.
     *
     * @property sharedKey the 32-byte HKDF output; callers may feed it
     *           into a verify-server-proof step or simply discard it
     *           after the claim succeeds.
     * @property clientProofB64 base64 string ready to drop into the
     *           `client_proof` field of the /pair/claim request.
     */
    data class ClientProof(
        val sharedKey: ByteArray,
        val clientProofB64: String,
    ) {
        // Default data-class equals/hashCode treat ByteArray as identity;
        // override so repeated invocations comparing equality (test
        // fixtures + cache hits) behave as value semantics.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ClientProof) return false
            return sharedKey.contentEquals(other.sharedKey) &&
                clientProofB64 == other.clientProofB64
        }

        override fun hashCode(): Int =
            31 * sharedKey.contentHashCode() + clientProofB64.hashCode()
    }

    /**
     * Build the phone-side client proof for a parsed QR + a freshly-
     * generated ephemeral X25519 keypair + the device's long-term
     * Ed25519 public key.
     *
     * @param qr parsed [PairingQr] — only `serverPubB64`, `nonce`, and
     *           `pairId` are read.
     * @param clientPriv 32-byte raw X25519 private key the phone just
     *           generated for this attempt. The corresponding public
     *           key goes into the `client_pub` field of /pair/claim.
     * @param devicePubEd25519Raw 32-byte raw Ed25519 public key — the
     *           device's long-term identity key. The matching private
     *           key NEVER leaves the phone.
     */
    fun buildClientProof(
        qr: PairingQr,
        clientPriv: ByteArray,
        devicePubEd25519Raw: ByteArray,
        mesh: MeshBlock? = null,
    ): ClientProof {
        val serverPub = b64ToBytes(qr.serverPubB64)
        val nonceBytes = b64ToBytes(qr.nonce)
        val shared = X25519.ecdh(clientPriv, serverPub)
        val sharedKey = Hkdf.derive(
            ikm = shared,
            salt = nonceBytes,
            info = HKDF_INFO,
            length = 32,
        )
        val message = qr.pairId.toByteArray(Charsets.UTF_8) + devicePubEd25519Raw +
            meshTail(mesh)
        val mac = Mac.getInstance("HmacSHA256").also {
            it.init(SecretKeySpec(sharedKey, "HmacSHA256"))
        }
        val proof = mac.doFinal(message)
        return ClientProof(
            sharedKey = sharedKey,
            clientProofB64 = bytesToB64(proof),
        )
    }

    /**
     * Хвіст доказу, яким сітьові ключі привʼязані до цього паринга.
     *
     * Порожній, коли блоку немає, — саме тому телефон попередньої збірки
     * лишається сумісним: він рахує коротке повідомлення, сервер бачить, що
     * полів немає, і рахує таке саме. А ось відщипнути блок від запиту нової
     * збірки не вийде: телефон уже підписав довге повідомлення, і без полів
     * доказ не зійдеться.
     *
     * Байт-у-байт як `pair_crypto._mesh_tail`.
     */
    fun meshTail(mesh: MeshBlock?): ByteArray {
        if (mesh == null || !mesh.isComplete) return ByteArray(0)
        return mesh.peerId.toByteArray(Charsets.UTF_8) +
            b64ToBytes(mesh.pubKeyB64) +
            b64ToBytes(mesh.dhPubKeyB64)
    }

    /**
     * Verify the server-side proof returned in the /pair/claim response.
     * Prevents a malicious relay from tricking the phone into accepting
     * a fake JWT — the server must prove it holds the same [sharedKey]
     * by signing the [deviceJwt].
     */
    fun verifyServerProof(
        sharedKey: ByteArray,
        deviceJwt: String,
        serverProofB64: String,
        node: MeshBlock? = null,
    ): Boolean {
        val mac = Mac.getInstance("HmacSHA256").also {
            it.init(SecretKeySpec(sharedKey, "HmacSHA256"))
        }
        // Ключі вузла всередині доказу, а не поруч із ним: інакше посередник
        // підмінив би сітьовий ключ ПК, телефон склав би адресу з чужим
        // ключем і чесно писав би в скриньку, якої ПК ніколи не назве.
        val expected = mac.doFinal(
            deviceJwt.toByteArray(Charsets.UTF_8) + meshTail(node),
        )
        // Constant-time compare on raw HMAC bytes — string compare via b64 leaks
        // timing on every prefix-match byte. Backend uses hmac.compare_digest
        // (pair_crypto.py:266); the phone must match.
        val provided = runCatching { b64ToBytes(serverProofB64) }.getOrElse { return false }
        return MessageDigest.isEqual(expected, provided)
    }
}
