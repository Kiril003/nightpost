package local.phantom.companion.core.net.peer

import local.phantom.companion.core.net.crypto.Ed25519

/**
 * What makes a peer message believable.
 *
 * On a mesh there is no server to vouch for anyone. The signature is the whole
 * claim of authorship, so what gets signed must pin down everything an
 * attacker would otherwise be free to change: not just the words, but who they
 * are from, who they are for, and when. Sign only the body and the same
 * sentence can be replayed at a stranger, or re-addressed, and still verify.
 */
object PeerSealing {

    private const val SEP = ''

    /**
     * The exact bytes both sides sign and check. Fields are joined by a
     * separator that cannot occur in them, so «a|b» and «ab» can never collide
     * into the same signed sentence.
     */
    fun canonical(message: PeerMessage): ByteArray = buildString {
        append(message.id).append(SEP)
        append(message.fromId).append(SEP)
        append(message.toId).append(SEP)
        append(message.sentAtMs).append(SEP)
        append(message.body)
        // Дописується лише коли відповідь є: без цього кожен уже підписаний
        // лист перестав би перевірятись. Але коли є — мусить бути підписаним,
        // бо перецілена відповідь міняє зміст, не чіпаючи жодного слова.
        if (message.replyToId.isNotBlank()) {
            append(SEP).append(message.replyToId)
        }
    }.toByteArray(Charsets.UTF_8)

    fun seal(message: PeerMessage, privateKey: ByteArray): PeerMessage {
        val sig = Ed25519.sign(privateKey, canonical(message))
        return message.copy(signature = b64(sig))
    }

    /**
     * True only when this peer really said this, to this operator.
     *
     * [expectedFromId] is checked against the envelope so a valid signature
     * from one peer cannot be presented as another's — the key proves the
     * words, the roster proves who the key belongs to, and both must agree.
     */
    fun verify(
        message: PeerMessage,
        publicKey: ByteArray,
        expectedFromId: String? = null,
    ): Boolean {
        if (message.signature.isBlank()) return false
        if (expectedFromId != null && message.fromId != expectedFromId) return false
        val sig = runCatching { unB64(message.signature) }.getOrNull() ?: return false
        return runCatching { Ed25519.verify(publicKey, canonical(message), sig) }
            .getOrDefault(false)
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)
}
