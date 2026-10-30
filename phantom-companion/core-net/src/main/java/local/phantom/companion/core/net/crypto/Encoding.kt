package local.phantom.companion.core.net.crypto

import java.security.SecureRandom
import java.util.Base64

/**
 * Encoding helpers ported from `phantom-companion/src/services/cryptoUtils.ts`.
 *
 * Why mirror the JS API surface 1:1: the pair-flow client (Phase 2) will
 * exchange base64 strings with the existing `/api/v1/pair/init` and
 * `/api/v1/pair/claim` endpoints, and those endpoints were originally written
 * against the WebCrypto sample. Keeping `bytesToB64` / `b64ToBytes` /
 * `bytesToHex` / `hexToBytes` named identically lets a side-by-side diff
 * between TS and Kotlin stay readable in code review.
 *
 * Base64 mode: `Base64.getEncoder().withoutPadding()` does **not** match
 * `btoa` (which keeps `=` padding). The desktop expects padded output, so we
 * use the standard encoder. `b64ToBytes` accepts both padded and un-padded
 * input so a phone built from a stale snapshot of the protocol does not
 * crash on a server that drops padding.
 */

private val SECURE_RANDOM: SecureRandom by lazy {
    // Android API 29+ default SecureRandom uses /dev/urandom-backed
    // AndroidOpenSSL provider — explicitly instantiating documents the
    // intent. JVM tests fall through to the platform default which is
    // adequate for round-trip coverage.
    SecureRandom()
}

/**
 * Cryptographically-secure random bytes. Used as IV/nonce for AES-GCM,
 * salt for Argon2id, and challenge nonces in the pair-flow.
 */
fun randomBytes(length: Int): ByteArray {
    require(length >= 0) { "randomBytes length must be non-negative (got $length)" }
    val out = ByteArray(length)
    if (length > 0) SECURE_RANDOM.nextBytes(out)
    return out
}

/**
 * Lower-case hex encoding (matches `cryptoUtils.ts.bytesToHex`).
 */
fun bytesToHex(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size * 2)
    for (b in bytes) {
        val v = b.toInt() and 0xff
        sb.append(HEX_CHARS[v ushr 4])
        sb.append(HEX_CHARS[v and 0x0f])
    }
    return sb.toString()
}

/**
 * Tolerant hex decoder. Accepts upper- and lower-case digits; rejects odd
 * length and non-hex bytes with [IllegalArgumentException] so corrupt
 * payloads fail loudly rather than silently truncating.
 */
fun hexToBytes(hex: String): ByteArray {
    require(hex.length % 2 == 0) { "hex string must have even length (got ${hex.length})" }
    val out = ByteArray(hex.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(hex[i * 2], 16)
        val lo = Character.digit(hex[i * 2 + 1], 16)
        require(hi >= 0 && lo >= 0) { "non-hex character at index ${i * 2}" }
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/**
 * Standard base64 with `=` padding. Matches `btoa(...)` output on the
 * WebCrypto reference so payloads round-trip across the wire unchanged.
 */
fun bytesToB64(bytes: ByteArray): String =
    Base64.getEncoder().encodeToString(bytes)

/**
 * Decoder that accepts both padded and un-padded base64. Forward-compat
 * with backends that strip padding (some Python libs do).
 */
fun b64ToBytes(s: String): ByteArray =
    Base64.getDecoder().decode(s)

private val HEX_CHARS = "0123456789abcdef".toCharArray()
