package local.phantom.companion.core.net.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.util.Base64

/**
 * Argon2id PIN hasher.
 *
 * Threat model: the encoded string is what lands in [Profile.pinHashArgon2]
 * inside the SQLCipher-encrypted DB. Even if the DB key leaks, replaying a
 * stolen PIN still has to grind through Argon2id with its memory-hard cost.
 * The default cost (64 MiB / 3 iterations / parallelism 1) targets ~1 s on
 * mid-range Android (Snapdragon 6-gen-1 class) and is the same posture
 * recommended by OWASP cheatsheet 2024. Stronger phones complete faster;
 * weaker phones tolerate the wait because PIN entry is a once-per-unlock
 * operation, not a per-keystroke check.
 *
 * Encoded format (PHC standard, what passlib / libsodium consume):
 *
 *     `$argon2id$v=19$m=65536,t=3,p=1$<saltb64>$<hashb64>`
 *
 * Where the trailing salt and hash are un-padded base64 (per the PHC spec).
 * [verify] re-derives the hash with the encoded parameters and constant-
 * time-compares the result; the function never prints the PIN, the salt,
 * or the hash to logcat.
 */
object PinHasher {

    /**
     * Tunable Argon2id parameters. Defaults to [Default]; tests pass
     * [Test] (cheap, fast) so the JVM suite stays under a second.
     */
    data class Params(
        /** Memory cost in KiB. 65 536 KiB ≈ 64 MiB. */
        val memoryKiB: Int,
        /** Iterations. 3 is the OWASP 2024 floor for Argon2id at 64 MiB. */
        val iterations: Int,
        /** Parallelism. Mid-range Android single-thread bias keeps this 1. */
        val parallelism: Int,
        /** Output hash length in bytes. 32 bytes (256 bits) = HKDF-friendly. */
        val hashLength: Int,
        /** Salt length in bytes. 16 bytes is the libsodium / RFC 9106 floor. */
        val saltLength: Int,
    ) {
        init {
            require(memoryKiB >= 8 * 1024) { "Argon2 memoryKiB must be ≥ 8192 (8 MiB) — got $memoryKiB" }
            require(iterations >= 1) { "Argon2 iterations must be ≥ 1 — got $iterations" }
            require(parallelism >= 1) { "Argon2 parallelism must be ≥ 1 — got $parallelism" }
            require(hashLength >= 16) { "Argon2 hashLength must be ≥ 16 — got $hashLength" }
            require(saltLength >= 8) { "Argon2 saltLength must be ≥ 8 — got $saltLength" }
        }

        companion object {
            /** Production posture: ~64 MiB, 3 iters. */
            val Default: Params = Params(
                memoryKiB = 65_536,
                iterations = 3,
                parallelism = 1,
                hashLength = 32,
                saltLength = 16,
            )

            /**
             * Fast preset for unit tests: 16 MiB / 2 iters. Still real
             * Argon2id (so the encoded format is exercised) but cheap
             * enough that the suite finishes in <500 ms on a Radxa.
             */
            val Test: Params = Params(
                memoryKiB = 16_384,
                iterations = 2,
                parallelism = 1,
                hashLength = 32,
                saltLength = 16,
            )
        }
    }

    /**
     * PIN policy: 4–6 digits.
     *
     * Було 6 через специфікацію знайомства; троє тестувальників 11.09 сказали
     * одне й те саме — «пинкод 4 знака». Шість цифр на вході коштують більше,
     * ніж дають: довжина не є тут стіною (Argon2id і апаратний замок — є), а
     * зайві дві цифри відсіюють людину на другому екрані з чотирьох.
     */
    const val MIN_PIN_LENGTH: Int = 4

    const val MAX_PIN_LENGTH: Int = 6

    /**
     * Hash a PIN. The salt is generated fresh from [randomBytes] unless
     * caller supplies their own (re-key path during PIN change uses the
     * old salt to bind the new hash to the same encoded prefix for
     * easier audit log review).
     *
     * @return PHC-formatted encoded string suitable for direct storage
     *         in [Profile.pinHashArgon2].
     */
    fun hash(pin: String, params: Params = Params.Default, salt: ByteArray? = null): String {
        require(pin.length >= MIN_PIN_LENGTH) {
            "PIN must be at least $MIN_PIN_LENGTH characters (got ${pin.length})"
        }
        val effectiveSalt = salt ?: randomBytes(params.saltLength)
        require(effectiveSalt.size == params.saltLength) {
            "explicit salt length (${effectiveSalt.size}) must match params.saltLength (${params.saltLength})"
        }
        val hashBytes = derive(pin, effectiveSalt, params)
        return encode(params, effectiveSalt, hashBytes)
    }

    /**
     * Verify a PIN against its encoded form. Returns `false` on any
     * structural error (wrong magic, malformed segments, base64 errors)
     * so a corrupt row never leaks "almost-right" timing to a probe.
     */
    fun verify(pin: String, encoded: String): Boolean {
        val parsed = parse(encoded) ?: return false
        val recomputed = derive(pin, parsed.salt, parsed.params)
        return constantTimeEquals(recomputed, parsed.hash)
    }

    // ── Internals ─────────────────────────────────────────────────────

    private data class Parsed(val params: Params, val salt: ByteArray, val hash: ByteArray)

    private fun derive(pin: String, salt: ByteArray, params: Params): ByteArray {
        val argon = Argon2BytesGenerator()
        val builder = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKiB)
            .withParallelism(params.parallelism)
            .withSalt(salt)
        argon.init(builder.build())
        val out = ByteArray(params.hashLength)
        argon.generateBytes(pin.toByteArray(Charsets.UTF_8), out, 0, out.size)
        return out
    }

    private fun encode(params: Params, salt: ByteArray, hash: ByteArray): String {
        val saltB64 = Base64.getEncoder().withoutPadding().encodeToString(salt)
        val hashB64 = Base64.getEncoder().withoutPadding().encodeToString(hash)
        return buildString {
            append("\$argon2id\$v=19\$m=").append(params.memoryKiB)
            append(",t=").append(params.iterations)
            append(",p=").append(params.parallelism)
            append('$').append(saltB64)
            append('$').append(hashB64)
        }
    }

    private fun parse(encoded: String): Parsed? {
        // Expected segments: "", "argon2id", "v=19", "m=...,t=...,p=...", saltb64, hashb64
        val parts = encoded.split('$')
        if (parts.size != 6) return null
        if (parts[1] != "argon2id") return null
        if (parts[2] != "v=19") return null

        val costPairs = parts[3].split(',').mapNotNull {
            val kv = it.split('=')
            if (kv.size != 2) null else kv[0] to kv[1]
        }.toMap()
        val memory = costPairs["m"]?.toIntOrNull() ?: return null
        val time = costPairs["t"]?.toIntOrNull() ?: return null
        val parallel = costPairs["p"]?.toIntOrNull() ?: return null

        val salt = runCatching { Base64.getDecoder().decode(parts[4]) }.getOrNull() ?: return null
        val hash = runCatching { Base64.getDecoder().decode(parts[5]) }.getOrNull() ?: return null

        val params = runCatching {
            Params(
                memoryKiB = memory,
                iterations = time,
                parallelism = parallel,
                hashLength = hash.size,
                saltLength = salt.size,
            )
        }.getOrNull() ?: return null

        return Parsed(params, salt, hash)
    }

    /**
     * Constant-time byte-array equality. Hand-rolled with bitwise OR over
     * the XOR delta so a probing attacker cannot use a per-byte timing
     * oracle to recover the hash one byte at a time.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].toInt() xor b[i].toInt())
        }
        return diff == 0
    }
}
