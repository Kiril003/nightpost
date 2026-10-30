package local.phantom.companion.core.net.peer

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import local.phantom.companion.core.net.crypto.AesGcm
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.randomBytes

/**
 * Пакет особи: телефон віддає ПК свої насіння Ed25519/X25519 (docs/ONE_SELF.md, крок 1).
 *
 *   key   = HKDF-SHA256(X25519(client_eph, server_eph_із_QR), сіль = nonce QR, info = [INFO])
 *   aad   = "[INFO]␟pair_id␟self_id␟node_id"
 *   тіло  = AES-256-GCM(key, iv, ed25519_seed ‖ x25519_private)
 *   доказ = HMAC-SHA256(key, "own-self-ok␟pair_id␟self_id␟node_id")
 *
 * Дзеркало — `phantom-os/src/backend/node/own_self.py`, спільний вектор —
 * `own_self_vector.json`. Серверний ключ одноразовий, тож пакет із минулого
 * сеансу нічого не дає.
 */
object OwnSelf {

    const val INFO = "PHANTOM OS/own-self-v1"
    private const val SEP = "\u001f"
    private const val DONE = "own-self-ok"
    private const val SEED = 32
    private const val ID_BYTES = 8

    /** Моя особа. [toString] показує лише імʼя. */
    data class Keys(val selfId: String, val edSeed: ByteArray, val dhPrivate: ByteArray) {
        /** Імʼя — відбиток ключа підпису, інакше ПК відповість `bad_identity`. */
        fun isWhole(): Boolean =
            edSeed.size == SEED && dhPrivate.size == SEED &&
                runCatching { idOf(Ed25519.publicKeyFromPrivate(edSeed)) == selfId }.getOrDefault(false)

        override fun toString(): String = "OwnSelf.Keys(selfId=$selfId)"

        override fun equals(other: Any?): Boolean =
            other is Keys && other.selfId == selfId &&
                MessageDigest.isEqual(other.edSeed, edSeed) && MessageDigest.isEqual(other.dhPrivate, dhPrivate)

        override fun hashCode(): Int = selfId.hashCode()
    }

    /** Те, що йде дротом, і ключ сеансу — лише для перевірки доказу ПК. */
    class Sealed(val clientPubB64: String, val ivB64: String, val sealedB64: String, val key: ByteArray) {
        override fun toString(): String = "OwnSelf.Sealed(${sealedB64.length} b64)"
    }

    /** Правило `DeviceIdentityStore.fingerprint`: SHA-256 від base64 без набивки, 8 байтів. */
    fun idOf(edPub: ByteArray): String {
        val b64 = java.util.Base64.getEncoder().withoutPadding().encodeToString(edPub)
        val digest = MessageDigest.getInstance("SHA-256").digest(b64.toByteArray(Charsets.UTF_8))
        return digest.take(ID_BYTES).joinToString("") { "%02x".format(it) }
    }

    fun key(myPriv: ByteArray, theirPub: ByteArray, nonce: ByteArray): ByteArray =
        Hkdf.derive(ikm = X25519.ecdh(myPriv, theirPub), salt = nonce, info = INFO.toByteArray(Charsets.UTF_8), length = 32)

    fun aad(pairId: String, selfId: String, nodeId: String): ByteArray =
        listOf(INFO, pairId, selfId, nodeId).joinToString(SEP).toByteArray(Charsets.UTF_8)

    fun seal(
        serverPubB64: String,
        nonceB64: String,
        pairId: String,
        nodeId: String,
        keys: Keys,
        clientPriv: ByteArray = X25519.generateKeypair().privateKey,
        iv: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
    ): Sealed {
        val key = key(clientPriv, b64ToBytes(serverPubB64), b64ToBytes(nonceB64))
        val sealed = AesGcm.encrypt(key, keys.edSeed + keys.dhPrivate, iv, aad(pairId, keys.selfId, nodeId))
        return Sealed(bytesToB64(X25519.publicKeyFromPrivate(clientPriv)), bytesToB64(iv), bytesToB64(sealed), key)
    }

    /** Бік ПК — для тестів і на випадок, коли особа колись поїде у зворотний бік. */
    fun open(
        serverPriv: ByteArray,
        clientPubB64: String,
        nonceB64: String,
        pairId: String,
        selfId: String,
        nodeId: String,
        ivB64: String,
        sealedB64: String,
    ): Keys? = runCatching {
        val key = key(serverPriv, b64ToBytes(clientPubB64), b64ToBytes(nonceB64))
        val plain = AesGcm.decrypt(key, b64ToBytes(sealedB64), b64ToBytes(ivB64), aad(pairId, selfId, nodeId))
        Keys(selfId, plain.copyOfRange(0, SEED), plain.copyOfRange(SEED, 2 * SEED)).takeIf {
            plain.size == 2 * SEED && it.isWhole()
        }
    }.getOrNull()

    fun doneProof(key: ByteArray, pairId: String, selfId: String, nodeId: String): String =
        bytesToB64(mac(key, pairId, selfId, nodeId))

    fun verifyDone(key: ByteArray, pairId: String, selfId: String, nodeId: String, proofB64: String): Boolean {
        val given = runCatching { b64ToBytes(proofB64) }.getOrElse { return false }
        return MessageDigest.isEqual(mac(key, pairId, selfId, nodeId), given)
    }

    private fun mac(key: ByteArray, pairId: String, selfId: String, nodeId: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(listOf(DONE, pairId, selfId, nodeId).joinToString(SEP).toByteArray(Charsets.UTF_8))
        }
}
