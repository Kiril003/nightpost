package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519

/**
 * The key two phones share and nobody else has.
 *
 * One key per pair, both directions. Both sides compute it from the same two
 * public keys and their own private one, so it never travels and never needs
 * a handshake — which matters on a LAN with no internet, where a round trip
 * may simply never complete.
 *
 * The salt is the two fingerprints in lexicographic order, so A and B derive
 * the identical key without agreeing who goes first. Direction confusion is
 * not the salt's job: a frame played back at its own sender is caught by the
 * envelope, which binds who it was for.
 */
object PeerChannel {

    private const val SEP = ''
    private val INFO = "PHANTOM OS/peer-msg-v2".toByteArray(Charsets.UTF_8)

    /**
     * Null whenever the material cannot make a key — a peer added before this
     * build has no X25519 key at all, and a caller that treated that as an
     * empty key would encrypt to a value an attacker knows.
     */
    fun derive(
        myPrivateKey: ByteArray,
        theirDhPubKeyB64: String,
        myId: String,
        theirId: String,
    ): ByteArray? {
        if (theirDhPubKeyB64.isBlank() || myId.isBlank() || theirId.isBlank()) return null
        val theirs = runCatching { java.util.Base64.getDecoder().decode(theirDhPubKeyB64.trim()) }
            .getOrNull() ?: return null
        if (theirs.size != X25519.PUBLIC_KEY_SIZE) return null
        if (myPrivateKey.size != X25519.PRIVATE_KEY_SIZE) return null
        val shared = runCatching { X25519.ecdh(myPrivateKey, theirs) }.getOrNull() ?: return null
        return Hkdf.derive(ikm = shared, salt = salt(myId, theirId), info = INFO, length = 32)
    }

    private fun salt(a: String, b: String): ByteArray {
        val low = if (a <= b) a else b
        val high = if (a <= b) b else a
        return "$low$SEP$high".toByteArray(Charsets.UTF_8)
    }

    /**
     * Derived keys held for as long as the mesh runs and no longer.
     *
     * Keyed by the peer AND the exact public key it was derived against, so a
     * re-pair with a fresh key can never be served the old one — the cache
     * entry an attacker would want simply is not the entry that gets looked
     * up. Never written to disk: one ECDH per cold pair costs nothing, and a
     * second secret store is a second thing to leak.
     */
    class Cache {
        private val keys = mutableMapOf<String, ByteArray>()

        @Synchronized
        fun get(
            peerId: String,
            theirDhPubKeyB64: String,
            derive: () -> ByteArray?,
        ): ByteArray? {
            if (peerId.isBlank() || theirDhPubKeyB64.isBlank()) return null
            val at = peerId + SEP + theirDhPubKeyB64.trim()
            keys[at]?.let { return it }
            val fresh = derive() ?: return null
            keys[at] = fresh
            return fresh
        }

        @Synchronized
        fun forget(peerId: String) {
            keys.keys.filter { it.startsWith(peerId + SEP) }.forEach { keys.remove(it) }
        }

        @Synchronized
        fun clear() {
            keys.values.forEach { it.fill(0) }
            keys.clear()
        }

        @Synchronized
        fun size(): Int = keys.size
    }
}
