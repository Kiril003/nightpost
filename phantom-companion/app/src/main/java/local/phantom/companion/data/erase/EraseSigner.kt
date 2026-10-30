package local.phantom.companion.data.erase

import java.security.SecureRandom
import java.util.Base64
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerBlob
import local.phantom.companion.data.DeviceIdentityStore
import local.phantom.companion.data.PeerMesh

class EraseSigner(
    private val identity: () -> DeviceIdentityStore.Identity?,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val nonceHex: () -> String = { randomNonceHex() },
) {
    constructor(identityStore: DeviceIdentityStore) : this({ kotlin.runCatching { identityStore.get() }.getOrNull() })

    fun prove(): PeerMesh.AttachmentProof? = sign { pub, nonce, at -> rootSentence(pub, nonce, at) }

    fun cancel(): PeerMesh.AttachmentProof? = sign { pub, nonce, at -> cancelSentence(pub, nonce, at) }

    private fun sign(sentence: (String, String, Long) -> String): PeerMesh.AttachmentProof? {
        val id = identity() ?: return null
        val nonce = nonceHex()
        val at = nowMs()
        val bytes = sentence(id.publicKeyB64, nonce, at).toByteArray(Charsets.UTF_8)
        val sig = runCatching { Ed25519.sign(id.privateKey, bytes) }.getOrNull() ?: return null
        return PeerMesh.AttachmentProof(
            pubKey = id.publicKeyB64,
            nonce = nonce,
            issuedMs = at,
            signature = Base64.getEncoder().encodeToString(sig),
        )
    }

    companion object {
        internal fun rootSentence(pub: String, nonce: String, issuedMs: Long): String =
            "NP-ERASE-v1|$pub|$nonce|$issuedMs"

        internal fun cancelSentence(pub: String, nonce: String, issuedMs: Long): String =
            "NP-ERASE-CANCEL-v1|$pub|$nonce|$issuedMs"

        private fun randomNonceHex(): String {
            val raw = ByteArray(PeerBlob.CLAIM_NONCE_CHARS / 2)
            SecureRandom().nextBytes(raw)
            return raw.joinToString("") { "%02x".format(it) }
        }
    }
}
