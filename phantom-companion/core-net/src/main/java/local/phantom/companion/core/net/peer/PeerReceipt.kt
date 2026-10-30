package local.phantom.companion.core.net.peer

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Proof that the person it was for actually got it.
 *
 * Until this existed the ACK named the message id and nothing else, so anybody
 * who could read the id off the wire could also answer for it — and a forged
 * «доставлено» is worse than an honest «чекає», because it is the one state
 * nobody re-checks. The tag is an HMAC under the key only this pair holds,
 * over the message AND the nonce of the exact frame that carried it: reading
 * the wire gives an observer neither the key nor, since envelope v3, the id.
 */
object PeerReceipt {

    private const val SEP = ''
    private const val TAG = "PH3-ACK"
    private const val ALGORITHM = "HmacSHA256"

    /**
     * Base64 of HMAC-SHA256, unpadded. Fixed length is what lets [matches]
     * compare without leaking where two receipts first differ.
     */
    const val LENGTH = 43

    /**
     * Null when there is no key to write it with — the caller must treat that
     * as an undelivered message, never as a receipt that happens to be empty.
     *
     * A field carrying the separator itself is refused rather than escaped.
     * Joining without lengths is only safe while every one of these four comes
     * from a party that already holds the key; a handshake verb or a relay hop
     * would make one of them somebody else's text, and «a|bc» would start
     * signing the same sentence as «ab|c».
     */
    fun of(
        channelKey: ByteArray,
        messageId: String,
        fromId: String,
        toId: String,
        nonceB64: String,
    ): String? {
        if (channelKey.isEmpty()) return null
        if (messageId.isBlank() || fromId.isBlank() || toId.isBlank()) return null
        if (nonceB64.isBlank()) return null
        if (listOf(messageId, fromId, toId, nonceB64).any { SEP in it }) return null
        val canonical = "$TAG$SEP$messageId$SEP$fromId$SEP$toId$SEP$nonceB64"
        val tag = runCatching {
            Mac.getInstance(ALGORITHM)
                .apply { init(SecretKeySpec(channelKey, ALGORITHM)) }
                .doFinal(canonical.toByteArray(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return java.util.Base64.getEncoder().withoutPadding().encodeToString(tag)
    }

    /** Every character is compared: an early return times out the guess. */
    fun matches(expected: String?, offered: String?): Boolean {
        if (expected.isNullOrBlank() || offered.isNullOrBlank()) return false
        if (expected.length != offered.length) return false
        var diff = 0
        for (i in expected.indices) diff = diff or (expected[i].code xor offered[i].code)
        return diff == 0
    }
}
