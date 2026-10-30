package local.phantom.companion.core.net.peer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.crypto.Hkdf
import local.phantom.companion.core.net.crypto.X25519
import local.phantom.companion.core.net.crypto.randomBytes

/**
 * A key for this meeting only.
 *
 * [PeerChannel] gives a pair ONE key, derived from two keys minted once per
 * phone and kept for life. It never travels, which is why it needed no
 * handshake — and it is also why a phone taken to a lab decrypts every frame
 * that pair ever sent, including the ones both sides deleted, because the key
 * is not stored anywhere: it is recomputed from what the phone holds.
 * [PeerForwardSecrecyTest] performs exactly that.
 *
 * Delivery here is always interactive: a message leaves the outbox only when
 * the other phone answers, live, on the same LAN. So at the one moment a key
 * is needed the other side is present and can contribute fresh material. That
 * is what makes prekeys, a server, and a ratchet's persistent state
 * unnecessary — and their absence is the feature. Nothing about a session is
 * ever written down, so nothing can desync: every state loss costs one round
 * trip and heals itself.
 *
 * Both phones throw a fresh X25519 keypair at each other, signed with the
 * long-term key the roster already vouches for, and mix the result with
 * today's static agreement:
 *
 *     key = HKDF(ephemeral-DH ‖ static-DH, salt = ids ‖ both nonces)
 *
 * The static half is a floor, not decoration: whatever happens to the
 * ephemerals, computing this key still needs the long-term private key, so a
 * PH4 session is never weaker than what shipped before it. It also keeps the
 * encounter tied to the keys the six digits and [PeerIdentity.KeySource]
 * speak for — ephemerals are never roster rows, never ride in an invite, and
 * never outlive the process, so they cannot launder how a key arrived.
 */
object PeerSession {

    const val VERSION = 1
    const val NONCE_BYTES = 16

    /**
     * A session dies with the process; these only bound a long-lived one.
     *
     * CHOSEN, NOT MEASURED. Nobody has timed a handshake on a real crew link,
     * and these numbers must not be read as if somebody had. What would change
     * them is that measurement and nothing else — not intuition about what
     * sounds safer.
     *
     * The idle timer is the one that governs: a pair quiet for an hour meets
     * again on the next letter, which costs one round trip on a link that had
     * to be alive to send anyway. The ceiling only touches a pair messaging
     * continuously for a day, which is rare. Tightening either buys little
     * against the threat this exists for: a phone seized while a session is
     * live already yields its stored conversation in plaintext, so the session
     * key adds only the recorded frames of the current window.
     */
    const val IDLE_MS = 60L * 60_000L
    const val LIFETIME_MS = 24L * 60L * 60_000L
    const val MAX_PER_PEER = 4

    private const val SEP = ''
    private const val TAG_INITIATOR = "PH4-HLO-I"
    private const val TAG_RESPONDER = "PH4-HLO-R"
    private val INFO = "PHANTOM OS/peer-msg-v4-session".toByteArray(Charsets.UTF_8)

    private val json = Json { ignoreUnknownKeys = true }

    /** `v` has no default for the reason [PeerEnvelope.Envelope] gives. */
    @Serializable
    data class Hello(
        val v: Int,
        val from: String,
        val to: String,
        val eph: String,
        val n: String,
        val sig: String,
    )

    /**
     * An initiator's hello and the private half that answers it. Single use:
     * [finish] and [discard] both zeroize, so an offer nobody answered leaves
     * nothing behind.
     */
    class Offer internal constructor(
        val hello: Hello,
        private val ephPrivate: ByteArray,
    ) {
        private var spent = false

        val frame: String get() = encode(hello)

        @Synchronized
        fun discard() {
            spent = true
            ephPrivate.fill(0)
        }

        // Zeroized bytes still clamp to a usable scalar, so a second use would
        // agree a wrong key in silence instead of refusing.
        @Synchronized
        internal fun consume(): ByteArray? {
            if (spent) return null
            spent = true
            return ephPrivate
        }
    }

    /** The responder's answer plus the session it already holds. */
    class Answer internal constructor(val hello: Hello, val session: Session) {
        val frame: String get() = encode(hello)
    }

    /**
     * The key itself, and nothing that could write it down. [peerId] is who it
     * was agreed with; [sid] is what a frame names so the other side can find
     * it without naming the key.
     */
    class Session internal constructor(
        val sid: String,
        val peerId: String,
        val key: ByteArray,
        val startedAtMs: Long,
    ) {
        internal fun wipe() = key.fill(0)
    }

    fun encode(hello: Hello): String = json.encodeToString(Hello.serializer(), hello)

    /**
     * Structure only — no key material is touched here, so the caller can look
     * [Hello.from] up in the roster before any DH happens. A stranger is
     * refused before this phone does arithmetic on their behalf.
     */
    fun parse(raw: String): Hello? {
        val hello = runCatching { json.decodeFromString(Hello.serializer(), raw) }.getOrNull()
            ?: return null
        if (hello.v != VERSION) return null
        val fields = listOf(hello.from, hello.to, hello.eph, hello.n, hello.sig)
        if (fields.any { it.isBlank() || SEP in it }) return null
        if (unB64(hello.eph)?.size != X25519.PUBLIC_KEY_SIZE) return null
        if (unB64(hello.n)?.size != NONCE_BYTES) return null
        if (unB64(hello.sig)?.size != Ed25519.SIGNATURE_SIZE) return null
        return hello
    }

    /** Null when this phone has no usable signing key — never an unsigned hello. */
    fun offer(myId: String, theirId: String, mySigningKey: ByteArray): Offer? {
        if (myId.isBlank() || theirId.isBlank()) return null
        if (SEP in myId || SEP in theirId) return null
        if (mySigningKey.size != Ed25519.PRIVATE_KEY_SIZE) return null
        val eph = runCatching { X25519.generateKeypair() }.getOrNull() ?: return null
        val nonce = randomBytes(NONCE_BYTES)
        val hello = Hello(
            v = VERSION,
            from = myId,
            to = theirId,
            eph = b64(eph.publicKey),
            n = b64(nonce),
            sig = "",
        )
        val sig = runCatching {
            Ed25519.sign(mySigningKey, initiatorTranscript(hello))
        }.getOrNull() ?: run {
            eph.privateKey.fill(0)
            return null
        }
        return Offer(hello.copy(sig = b64(sig)), eph.privateKey)
    }

    /**
     * The responder's side. Verifies the initiator against the long-term key
     * the roster holds, mints its own ephemeral, and answers with a signature
     * over BOTH hellos — so two halves from two different encounters can never
     * be spliced into one session.
     */
    fun answer(
        theirHello: Hello,
        myId: String,
        theirSigningPubB64: String,
        mySigningKey: ByteArray,
        myStaticDh: ByteArray,
        theirStaticDhB64: String,
        nowMs: Long,
    ): Answer? {
        if (theirHello.to != myId || theirHello.from == myId) return null
        if (mySigningKey.size != Ed25519.PRIVATE_KEY_SIZE) return null
        val theirSigningPub = unB64(theirSigningPubB64)?.takeIf {
            it.size == Ed25519.PUBLIC_KEY_SIZE
        } ?: return null
        val theirSig = unB64(theirHello.sig) ?: return null
        if (!Ed25519.verify(theirSigningPub, initiatorTranscript(theirHello), theirSig)) return null

        val eph = runCatching { X25519.generateKeypair() }.getOrNull() ?: return null
        try {
            val mine = Hello(
                v = VERSION,
                from = myId,
                to = theirHello.from,
                eph = b64(eph.publicKey),
                n = b64(randomBytes(NONCE_BYTES)),
                sig = "",
            )
            if (mine.eph == theirHello.eph) return null
            val sig = runCatching {
                Ed25519.sign(mySigningKey, responderTranscript(mine, theirHello))
            }.getOrNull() ?: return null
            val key = derive(
                myEphPrivate = eph.privateKey,
                theirEph = theirHello.eph,
                myStaticDh = myStaticDh,
                theirStaticDhB64 = theirStaticDhB64,
                myId = myId,
                theirId = theirHello.from,
                initiator = theirHello,
                responder = mine,
            ) ?: return null
            val session = Session(
                sid = sid(initiator = theirHello, responder = mine) ?: return null,
                peerId = theirHello.from,
                key = key,
                startedAtMs = nowMs,
            )
            return Answer(mine.copy(sig = b64(sig)), session)
        } finally {
            eph.privateKey.fill(0)
        }
    }

    /**
     * The initiator's side, once the answer comes back. Consumes [offer]: the
     * ephemeral private key is gone by the time this returns, refusal or not.
     */
    fun finish(
        offer: Offer,
        theirHello: Hello,
        theirSigningPubB64: String,
        myStaticDh: ByteArray,
        theirStaticDhB64: String,
        nowMs: Long,
    ): Session? {
        val ephPrivate = offer.consume() ?: return null
        try {
            if (theirHello.from != offer.hello.to) return null
            if (theirHello.to != offer.hello.from) return null
            // A hello echoed straight back would otherwise agree a key with
            // ourselves and look like a live peer.
            if (theirHello.eph == offer.hello.eph) return null
            val theirSigningPub = unB64(theirSigningPubB64)?.takeIf {
                it.size == Ed25519.PUBLIC_KEY_SIZE
            } ?: return null
            val theirSig = unB64(theirHello.sig) ?: return null
            if (!Ed25519.verify(
                    theirSigningPub,
                    responderTranscript(theirHello, offer.hello),
                    theirSig,
                )
            ) {
                return null
            }
            val key = derive(
                myEphPrivate = ephPrivate,
                theirEph = theirHello.eph,
                myStaticDh = myStaticDh,
                theirStaticDhB64 = theirStaticDhB64,
                myId = offer.hello.from,
                theirId = theirHello.from,
                initiator = offer.hello,
                responder = theirHello,
            ) ?: return null
            return Session(
                sid = sid(initiator = offer.hello, responder = theirHello) ?: return null,
                peerId = theirHello.from,
                key = key,
                startedAtMs = nowMs,
            )
        } finally {
            offer.discard()
        }
    }

    /** What a cached session is pinned to: a changed roster key must not match. */
    fun binding(peer: PeerIdentity): String = peer.pubKey + SEP + peer.dhPubKey

    /**
     * Internal rather than private so the python node can be held to the same
     * bytes: the salt order, the ikm order and the digest are exactly what two
     * implementations of one KDF drift on, and they drift in silence.
     */
    internal fun derive(
        myEphPrivate: ByteArray,
        theirEph: String,
        myStaticDh: ByteArray,
        theirStaticDhB64: String,
        myId: String,
        theirId: String,
        initiator: Hello,
        responder: Hello,
    ): ByteArray? {
        if (myStaticDh.size != X25519.PRIVATE_KEY_SIZE) return null
        val theirEphPub = unB64(theirEph)?.takeIf { it.size == X25519.PUBLIC_KEY_SIZE }
            ?: return null
        val theirStaticPub = unB64(theirStaticDhB64)?.takeIf {
            it.size == X25519.PUBLIC_KEY_SIZE
        } ?: return null
        val ephemeral = runCatching { X25519.ecdh(myEphPrivate, theirEphPub) }.getOrNull()
            ?: return null
        val static = runCatching { X25519.ecdh(myStaticDh, theirStaticPub) }.getOrNull()
            ?: run {
                ephemeral.fill(0)
                return null
            }
        val ikm = ephemeral + static
        try {
            val low = if (myId <= theirId) myId else theirId
            val high = if (myId <= theirId) theirId else myId
            return Hkdf.derive(
                ikm = ikm,
                salt = "$low$SEP$high$SEP${initiator.n}$SEP${responder.n}"
                    .toByteArray(Charsets.UTF_8),
                info = INFO,
                length = 32,
            )
        } finally {
            ephemeral.fill(0)
            static.fill(0)
            ikm.fill(0)
        }
    }

    /**
     * A name for the encounter, not for its key: it is a digest of four public
     * values, so an observer learns which frames belong together — which the
     * addressing already told them — and nothing else.
     */
    internal fun sid(initiator: Hello, responder: Hello): String? {
        val parts = listOf(initiator.eph, responder.eph, initiator.n, responder.n)
            .map { unB64(it) ?: return null }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest().take(8).joinToString("") { "%02x".format(it) }
    }

    internal fun initiatorTranscript(hello: Hello): ByteArray =
        listOf(TAG_INITIATOR, hello.from, hello.to, hello.eph, hello.n)
            .joinToString(SEP.toString()).toByteArray(Charsets.UTF_8)

    internal fun responderTranscript(mine: Hello, theirs: Hello): ByteArray =
        listOf(TAG_RESPONDER, mine.from, mine.to, mine.eph, mine.n, theirs.eph, theirs.n)
            .joinToString(SEP.toString()).toByteArray(Charsets.UTF_8)

    /**
     * Sessions for as long as the mesh runs and no longer.
     *
     * There is no `save`. That absence IS the forward-secrecy claim: a key
     * that was never written cannot be recovered from a phone somebody took,
     * and a state that is not persisted cannot desync into a pair that has
     * silently stopped working.
     *
     * Entries are pinned to the peer's long-term keys, so a re-pair with a
     * fresh key is never served an old session — the entry an attacker would
     * want is simply not the entry that gets looked up.
     */
    class Cache(
        private val idleMs: Long = IDLE_MS,
        private val lifetimeMs: Long = LIFETIME_MS,
        private val maxPerPeer: Int = MAX_PER_PEER,
    ) {
        private class Entry(val session: Session, val boundTo: String, var lastUsedMs: Long)

        private val entries = mutableMapOf<String, Entry>()

        @Synchronized
        fun put(session: Session, boundTo: String, nowMs: Long) {
            expire(nowMs)
            entries[session.sid] = Entry(session, boundTo, nowMs)
            val mine = entries.entries
                .filter { it.value.session.peerId == session.peerId }
                .sortedByDescending { it.value.session.startedAtMs }
            mine.drop(maxPerPeer).forEach { entries.remove(it.key) }
        }

        /** The receiving side: a frame names its encounter and nothing else. */
        @Synchronized
        fun bySid(sid: String, boundTo: String, nowMs: Long): Session? {
            expire(nowMs)
            val entry = entries[sid] ?: return null
            if (entry.boundTo != boundTo) return null
            entry.lastUsedMs = nowMs
            return entry.session
        }

        /** The sending side: whichever encounter with them is freshest. */
        @Synchronized
        fun newestFor(peerId: String, boundTo: String, nowMs: Long): Session? {
            expire(nowMs)
            val entry = entries.values
                .filter { it.session.peerId == peerId && it.boundTo == boundTo }
                .maxByOrNull { it.session.startedAtMs } ?: return null
            entry.lastUsedMs = nowMs
            return entry.session
        }

        @Synchronized
        fun forget(peerId: String) {
            entries.entries.filter { it.value.session.peerId == peerId }
                .map { it.key }
                .forEach { entries.remove(it)?.session?.wipe() }
        }

        @Synchronized
        fun forgetSid(sid: String) {
            entries.remove(sid)?.session?.wipe()
        }

        @Synchronized
        fun clear() {
            entries.values.forEach { it.session.wipe() }
            entries.clear()
        }

        @Synchronized
        fun size(): Int = entries.size

        /**
         * Aged-out entries are dropped, not wiped: a caller may be holding the
         * key it just fetched, and zeroizing it under their hand would seal a
         * message with 32 zero bytes. Wiping belongs to the explicit exits —
         * [clear], [forget] — where nobody is mid-send.
         */
        private fun expire(nowMs: Long) {
            entries.entries
                .filter { (_, e) ->
                    nowMs - e.lastUsedMs > idleMs || nowMs - e.session.startedAtMs > lifetimeMs
                }
                .map { it.key }
                .forEach { entries.remove(it) }
        }
    }

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray? =
        runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull()
}
