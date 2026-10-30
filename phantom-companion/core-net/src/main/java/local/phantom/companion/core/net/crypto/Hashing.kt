package local.phantom.companion.core.net.crypto

import java.security.MessageDigest

/**
 * SHA-256 over a byte array. Mirrors `cryptoUtils.ts.sha256` so the pair-
 * flow's `session_key_check` field round-trips identically.
 *
 * Implemented via `java.security.MessageDigest` which is FIPS-flagged on
 * Android API 29+ — no BouncyCastle bridge needed for hashing-only paths.
 * BouncyCastle is reserved for primitives (Argon2id, X25519, Ed25519, HKDF)
 * the platform does not expose directly.
 */
fun sha256(data: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(data)
