package local.phantom.companion.core.net.peer

/**
 * The bytes two phones exchange.
 *
 * Deliberately not HTTP and deliberately not a library. This link has to work
 * on a LAN with no internet, no server and no DNS, on a phone that may be
 * jammed a minute later — every dependency in that path is a thing that can
 * fail in a way nobody can debug in a basement. One line, one length, one
 * body, and a reply that is either an id or a reason.
 *
 * Framing is length-prefixed rather than delimiter-terminated: message bodies
 * carry newlines (people press enter), and a protocol that ends on the first
 * one truncates exactly the messages that matter most.
 */
object PeerWire {

    const val PROTOCOL = "PHANTOM/1"
    const val VERB_MSG = "MSG"
    const val VERB_ACK = "ACK"
    const val VERB_ERR = "ERR"

    /**
     * «Ми зустрілись» — the one added verb. Unlike MSG it is answered with a
     * body of its own: the other phone's half of the exchange.
     */
    const val VERB_HELLO = "HLO"

    /**
     * «Ось я» — знайомство у зворотний бік, від того, хто щойно прийняв код.
     * Як і [VERB_HELLO], відповідається карткою власного тіла, а не рядком.
     */
    const val VERB_INTRO = "INT"

    /**
     * The one refusal a sender changes its behaviour on, so neither side has
     * to match on prose. A build that never heard of [VERB_HELLO] answers with
     * this on its own — that is the whole fallback trigger, and it costs the
     * old build nothing, because it is already exactly what it says.
     *
     * There is deliberately no separate «that meeting is over» reason: the
     * sender forgets its session on ANY refusal of a session frame and meets
     * again, so a second code would be a constant nobody's behaviour depends
     * on. Refusals are also bounded by the outbox's attempts, not by trust in
     * the answer.
     */
    const val UNKNOWN_VERB = "unknown verb"

    /**
     * The exact refusal, from one place. A listener that declines a hello for
     * ANY reason answers with this, so it is indistinguishable from a build
     * that never heard the verb: otherwise anybody on the Wi-Fi learns which
     * version this phone runs by asking once, and learns it from a phone that
     * would not tell them anything else.
     */
    fun unknownVerb(verb: String): String = "$UNKNOWN_VERB ${verb.take(16)}"

    /** A body past this is refused before a byte of it is read into memory. */
    const val MAX_BODY_BYTES = 64 * 1024

    /** Default LAN port. Chosen high and fixed so discovery can assume it. */
    const val PORT = 47_331

    /** A receipt is base64 of HMAC-SHA256; nothing longer is worth reading. */
    const val MAX_RECEIPT_CHARS = 64

    sealed interface Inbound {
        data class Message(val json: String) : Inbound
        data class Hello(val json: String) : Inbound
        data class Intro(val json: String) : Inbound
        data class Ack(val receipt: String) : Inbound
        data class Malformed(val reason: String) : Inbound
    }

    /** `PHANTOM/1 MSG <bytes>` — the header a sender writes before the body. */
    fun header(verb: String, bodyBytes: Int): String = "$PROTOCOL $verb $bodyBytes"

    /** Third token is now a [PeerReceipt], not the message id: same shape, same reader. */
    fun ackLine(receipt: String): String =
        "$PROTOCOL $VERB_ACK ${receipt.take(MAX_RECEIPT_CHARS)}"

    fun errorLine(reason: String): String =
        "$PROTOCOL $VERB_ERR ${reason.replace('\n', ' ').take(120)}"

    /**
     * Why the other side said no, or null when the line is not a refusal at
     * all. A sender that cannot tell «I do not know that verb» from any other
     * no has to guess, and guessing here means either a person unable to write
     * to somebody who has not updated, or a downgrade nobody asked for.
     */
    fun errorReason(line: String?): String? {
        val parts = line?.trim()?.split(' ', limit = 3) ?: return null
        if (parts.size < 3 || parts[0] != PROTOCOL || parts[1] != VERB_ERR) return null
        return parts[2].trim().takeIf { it.isNotEmpty() }
    }

    /**
     * Reads a header line. Returns the body length to read, or null when the
     * line is not something this protocol should answer at all — a port scan,
     * a browser, another app's probe.
     */
    fun parseHeader(line: String?): Header? {
        val parts = line?.trim()?.split(' ') ?: return null
        if (parts.size != 3 || parts[0] != PROTOCOL) return null
        val length = parts[2].toIntOrNull() ?: return null
        if (length < 0 || length > MAX_BODY_BYTES) return null
        return Header(verb = parts[1], bodyBytes = length)
    }

    data class Header(val verb: String, val bodyBytes: Int)

    /**
     * True only when the reply carries the receipt this exact frame earns.
     * A reply that merely says ACK proves nothing — see [PeerReceipt].
     */
    fun isAck(line: String?, receipt: String): Boolean {
        val parts = line?.trim()?.split(' ') ?: return false
        if (parts.size != 3 || parts[0] != PROTOCOL || parts[1] != VERB_ACK) return false
        return PeerReceipt.matches(receipt.take(MAX_RECEIPT_CHARS), parts[2])
    }

    /**
     * Decides what an inbound frame is. The verb is checked before the body is
     * trusted for anything, and an unknown verb is refused rather than guessed
     * at — a future protocol version must not be silently misread as this one.
     *
     * [speaksHello] defaults to false so a listener that was not given a
     * handshake answers `HLO` with the same «unknown verb» the previous build
     * answers — not an imitation of it, the same line from the same branch.
     * That is what the sender falls back on, so it must not be a special case.
     */
    fun classify(
        header: Header,
        body: String,
        speaksHello: Boolean = false,
        speaksIntro: Boolean = false,
    ): Inbound =
        when (header.verb) {
            VERB_MSG -> if (body.isBlank()) {
                Inbound.Malformed("empty body")
            } else {
                Inbound.Message(body)
            }
            VERB_HELLO -> when {
                !speaksHello -> Inbound.Malformed(unknownVerb(header.verb))
                body.isBlank() -> Inbound.Malformed("empty body")
                else -> Inbound.Hello(body)
            }
            VERB_INTRO -> when {
                !speaksIntro -> Inbound.Malformed(unknownVerb(header.verb))
                body.isBlank() -> Inbound.Malformed("empty body")
                else -> Inbound.Intro(body)
            }
            VERB_ACK -> Inbound.Ack(body.trim())
            else -> Inbound.Malformed(unknownVerb(header.verb))
        }
}
