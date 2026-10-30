package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.AesGcm
import local.phantom.companion.core.net.crypto.randomBytes

/**
 * What actually crosses the LAN.
 *
 * Until this existed, every word — and every shared position — travelled as
 * readable JSON that anybody on the same Wi-Fi could keep. The signature made
 * a message believable; it never made it private. This wraps the whole signed
 * message in AES-256-GCM under the pair's channel key, so what an observer
 * keeps is a length and two fingerprints.
 *
 * Sign THEN encrypt, and the signature stays inside the ciphertext: hiding it
 * denies a listener even the proof of who wrote what. The classic re-addressing
 * attack on that order is already dead here because [PeerSealing] signs the
 * recipient, and the receiver checks it is the recipient.
 *
 * The framing above this ([PeerWire]) is untouched — the envelope is only the
 * body, so nothing about the transport had to be re-proved.
 *
 * v3 dropped the cleartext `id`. It was there so the ACK could name the
 * message; [PeerReceipt] names it with a key instead, and an id an observer
 * can read is an id an observer can answer for — as well as the one field
 * that let them tell two retries of one letter from two letters.
 *
 * v4 changes the KEY, not the shape: the same envelope under a [PeerSession]
 * key that exists only for one meeting and is written down nowhere, named by
 * a `sid` so the other side can find it without the name saying anything about
 * the key. v3 is kept verbatim, because a peer running an older build must
 * keep receiving letters — at exactly today's security level, never below it.
 */
object PeerEnvelope {

    const val VERSION = 3

    /** The encounter-key envelope. Same fields plus the session it belongs to. */
    const val VERSION_SESSION = 4

    /** A hostile sid must not become a 64 KB key in anybody's cache. */
    const val MAX_SID_CHARS = 32

    private const val SEP = ''
    private const val TAG = "PH3"
    private const val TAG_SESSION = "PH4"

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * `v` deliberately has no default: kotlinx omits defaults when encoding,
     * and a version field that silently disappears from the wire is the kind
     * of thing that is only noticed by the build that cannot read it.
     *
     * `sid` does default, and to the empty string — which is exactly what
     * keeps a v3 frame byte-identical to the one the previous build wrote.
     */
    @Serializable
    data class Envelope(
        val v: Int,
        val from: String,
        val to: String,
        val n: String,
        val ct: String,
        val sid: String = "",
    )

    /** The nonce comes back out because the receipt for this frame binds it. */
    data class Sealed(val frame: String, val nonceB64: String)

    /**
     * Null when the frame would not fit [PeerWire.MAX_BODY_BYTES]. Base64 plus
     * the tag inflates a body by a third, so the caller must be told before it
     * hands the transport something the receiver refuses to read.
     *
     * The nonce defaults to a fresh one per call, and encryption happens per
     * delivery attempt — a retry that reused the frame would reuse the nonce,
     * which under GCM leaks the plaintexts of both.
     */
    fun seal(
        message: PeerMessage,
        key: ByteArray,
        nonce: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
    ): Sealed? = sealAs(VERSION, message, key, nonce, sid = "")

    /**
     * The same letter under the key of one meeting. [sid] names which meeting,
     * and it is inside the AAD: a frame lifted from one encounter and offered
     * under another's name will not open.
     */
    fun sealV4(
        message: PeerMessage,
        sid: String,
        key: ByteArray,
        nonce: ByteArray = randomBytes(AesGcm.IV_SIZE_BYTES),
    ): Sealed? {
        if (sid.isBlank() || sid.length > MAX_SID_CHARS || SEP in sid) return null
        return sealAs(VERSION_SESSION, message, key, nonce, sid)
    }

    private fun sealAs(
        version: Int,
        message: PeerMessage,
        key: ByteArray,
        nonce: ByteArray,
        sid: String,
    ): Sealed? {
        // Облік сховка — наша місцева бухгалтерія, не частина листа. Порожні
        // поля kotlinx не пише, тож кадр лишається байт у байт таким, як був.
        val plaintext = json
            .encodeToString(
                PeerMessage.serializer(),
                message.copy(heldNonce = "", heldUntilMs = 0L),
            )
            .toByteArray(Charsets.UTF_8)
        val aad = aad(version, message.fromId, message.toId, sid) ?: return null
        val ct = runCatching { AesGcm.encrypt(key, plaintext, nonce, aad) }.getOrNull()
            ?: return null
        val nonceB64 = b64(nonce)
        val frame = json.encodeToString(
            Envelope.serializer(),
            Envelope(
                v = version,
                from = message.fromId,
                to = message.toId,
                n = nonceB64,
                ct = b64(ct),
                sid = sid,
            ),
        )
        if (frame.toByteArray(Charsets.UTF_8).size > PeerWire.MAX_BODY_BYTES) return null
        return Sealed(frame, nonceB64)
    }

    /**
     * The addressing an observer can see anyway; nothing more is exposed.
     *
     * A version and its `sid` must agree: v3 carrying one, or v4 without one,
     * is refused rather than read under whichever AAD happens to be guessed.
     */
    fun parse(raw: String): Envelope? {
        val env = runCatching { json.decodeFromString(Envelope.serializer(), raw) }.getOrNull()
            ?: return null
        if (env.from.isBlank() || env.to.isBlank()) return null
        if (env.n.isBlank() || env.ct.isBlank()) return null
        if (SEP in env.from || SEP in env.to || SEP in env.sid) return null
        return when (env.v) {
            VERSION -> env.takeIf { it.sid.isEmpty() }
            VERSION_SESSION -> env.takeIf {
                it.sid.isNotBlank() && it.sid.length <= MAX_SID_CHARS
            }
            else -> null
        }
    }

    /**
     * The sealed message inside, or null — and null is the only detail a
     * refusal ever carries. [channelKey] is handed the claimed sender so the
     * caller can look them up in the roster first: a frame from somebody who
     * is not a contact never gets as far as a decryption attempt.
     *
     * What is checked before the message is handed back: the outer claim and
     * the inner one must be the same claim. Otherwise a valid frame could be
     * re-addressed at the envelope and believed as somebody else's.
     */
    fun open(
        raw: String,
        /**
         * The encounter key for a v4 frame, looked up by its [Envelope.sid].
         * Defaulting to none is what keeps every v3-only caller unchanged —
         * and a v4 frame is refused rather than quietly opened with the
         * pair's permanent key, which would hand back exactly what this
         * version exists to take away.
         */
        sessionKey: (sid: String) -> ByteArray? = { null },
        channelKey: (fromId: String) -> ByteArray?,
    ): PeerMessage? {
        val env = parse(raw) ?: return null
        val key = when (env.v) {
            VERSION_SESSION -> sessionKey(env.sid)
            else -> channelKey(env.from)
        } ?: return null
        val nonce = runCatching { unB64(env.n) }.getOrNull() ?: return null
        val ct = runCatching { unB64(env.ct) }.getOrNull() ?: return null
        if (nonce.size != AesGcm.IV_SIZE_BYTES) return null
        val aad = aad(env.v, env.from, env.to, env.sid) ?: return null
        val plaintext = runCatching {
            AesGcm.decrypt(key, ct, nonce, aad)
        }.getOrNull() ?: return null
        val message = runCatching {
            json.decodeFromString(PeerMessage.serializer(), String(plaintext, Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (message.fromId != env.from) return null
        if (message.toId != env.to) return null
        return message
    }

    private fun aad(version: Int, from: String, to: String, sid: String): ByteArray? = when (version) {
        VERSION -> "$TAG$SEP$from$SEP$to".toByteArray(Charsets.UTF_8)
        VERSION_SESSION -> "$TAG_SESSION$SEP$from$SEP$to$SEP$sid".toByteArray(Charsets.UTF_8)
        else -> null
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray =
        java.util.Base64.getDecoder().decode(text)
}
