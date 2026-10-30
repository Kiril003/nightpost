package local.phantom.companion.data

import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerBlob

/** Пара ключа заяв ([ClaimKeyStore]): окрема від ключа месенджера, ніколи з нього не виведена. */
class ClaimKey(val publicKeyB64: String, val privateKey: ByteArray)

/**
 * Підпис заяв для сервера закладів і балів — рішення власника №6.4 (10.10):
 * оцінки, «Я на місці» й привʼязку гаманця підписує ОКРЕМИЙ ключ заяв, тож сервер
 * не може звʼязати їх із ключем, який знають контакти. Речення складає сама
 * [DeviceClaim]; разове число з [java.security.SecureRandom], як у вкладень.
 */
class ClaimSigner(
    private val key: () -> ClaimKey?,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun prove(claim: DeviceClaim): PeerMesh.AttachmentProof? {
        val k = key() ?: return null
        val raw = ByteArray(PeerBlob.CLAIM_NONCE_CHARS / 2)
        java.security.SecureRandom().nextBytes(raw)
        val nonce = raw.joinToString("") { "%02x".format(it) }
        val at = nowMs()
        val sig = runCatching {
            Ed25519.sign(k.privateKey, claim.sentence(k.publicKeyB64, nonce, at).toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return PeerMesh.AttachmentProof(
            pubKey = k.publicKeyB64,
            nonce = nonce,
            issuedMs = at,
            signature = java.util.Base64.getEncoder().encodeToString(sig),
        )
    }
}
