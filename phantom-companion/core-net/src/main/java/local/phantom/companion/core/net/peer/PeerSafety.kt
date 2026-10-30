package local.phantom.companion.core.net.peer

/**
 * The safety number of one conversation — one number for two phones.
 *
 * It is the two fingerprints ([PeerInvite.fingerprint]) side by side, in an
 * order both phones compute the same way. Two fingerprints and NOT a hash of
 * the pair, on purpose: a hash of the pair can be ground from both ends at
 * once (a birthday search, 2^40 for 80 bits), while here each half has to
 * equal one person's real key on its own, and that is a preimage.
 *
 * Identical on both screens exactly when each phone holds the other's real
 * keys. Comparing it IS the whole ceremony; how — camera, voice, typing — is
 * a matter of convenience, not strength, and the copy says which is which.
 */
object PeerSafety {

    const val DIGITS = PeerInvite.FINGERPRINT_DIGITS * 2

    /** The web messenger's prefix too: one QR is read by both surfaces. */
    const val QR_PREFIX = "phantom-safety:"

    fun number(
        myPubKey: String,
        myDhPubKey: String,
        theirPubKey: String,
        theirDhPubKey: String,
    ): String {
        val mine = PeerInvite.fingerprint(myPubKey, myDhPubKey)
        val theirs = PeerInvite.fingerprint(theirPubKey, theirDhPubKey)
        return if (mine <= theirs) mine + theirs else theirs + mine
    }

    /** «4829 1375 … » — groups of four, one line. */
    fun pretty(number: String): String = number.chunked(4).joinToString(" ")

    /** Three rows of four groups: the eye compares a row at a time. */
    fun lines(number: String): List<String> = number.chunked(16).map { pretty(it) }

    fun qr(number: String): String = QR_PREFIX + number

    /**
     * The digits inside whatever was scanned or typed, or null. Spaces and the
     * prefix are noise; letters are not — an invite code, a URL or a stray
     * sentence must never be mistaken for a number that merely differs.
     */
    fun read(text: String?): String? {
        val whole = text?.trim() ?: return null
        val body = if (whole.startsWith(QR_PREFIX, ignoreCase = true)) {
            whole.substring(QR_PREFIX.length)
        } else {
            whole
        }
        if (body.any { it.isLetter() }) return null
        return body.filter { it.isDigit() }.takeIf { it.length == DIGITS }
    }

    /**
     * Which half moved. The sentence on screen depends on it: «your copy of
     * their key is wrong» and «their copy of yours is wrong» send the two
     * people to different phones to fix it.
     */
    enum class Outcome {
        MATCH,
        /** The key under their name on THIS phone is not theirs. */
        THEIRS_DIFFERS,
        /** The key under my name on THEIR phone is not mine. */
        MINE_DIFFERS,
        BOTH_DIFFER,
        NOT_A_NUMBER,
        ;

        val mismatch: Boolean get() = this == THEIRS_DIFFERS || this == MINE_DIFFERS || this == BOTH_DIFFER
    }

    fun compare(scanned: String?, myFingerprint: String, theirFingerprint: String): Outcome {
        val seen = read(scanned) ?: return Outcome.NOT_A_NUMBER
        val halves = setOf(seen.take(PeerInvite.FINGERPRINT_DIGITS), seen.drop(PeerInvite.FINGERPRINT_DIGITS))
        val mineHeld = myFingerprint in halves
        val theirsHeld = theirFingerprint in halves
        return when {
            mineHeld && theirsHeld -> Outcome.MATCH
            theirsHeld -> Outcome.MINE_DIFFERS
            mineHeld -> Outcome.THEIRS_DIFFERS
            else -> Outcome.BOTH_DIFFER
        }
    }
}
