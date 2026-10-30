package local.phantom.companion.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

object BackupFormat {

    const val FILE_NAME = "nightpost.backup"
    const val MIME = "application/octet-stream"
    const val FORMAT_VERSION = 1
    const val MIN_PASSPHRASE_LENGTH = 12
    const val DEFAULT_MEMORY_KIB = 65_536
    const val DEFAULT_ITERATIONS = 3
    const val DEFAULT_PARALLELISM = 1
    const val CHUNK_SIZE = 64 * 1024

    private const val MAX_CHUNK_SIZE = 1024 * 1024
    private const val MAX_MEMORY_KIB = 262_144
    private const val MAX_ITERATIONS = 10
    private const val MAX_PARALLELISM = 4
    private const val MAX_HEADER_BYTES = 4096
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val WRAP_NONCE_BYTES = 12
    private const val STREAM_PREFIX_BYTES = 8
    private const val STREAM_NONCE_BYTES = 12
    private const val GCM_TAG_BITS = 128
    private const val GCM_TAG_BYTES = 16
    private const val AES_GCM = "AES/GCM/NoPadding"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    data class KdfParams(
        val m: Int = DEFAULT_MEMORY_KIB,
        val t: Int = DEFAULT_ITERATIONS,
        val p: Int = DEFAULT_PARALLELISM,
    ) {
        init {
            require(m >= 8_192) { "Argon2id m must be >= 8192 KiB (got $m)" }
            require(t >= 1) { "Argon2id t must be >= 1 (got $t)" }
            require(p >= 1) { "Argon2id p must be >= 1 (got $p)" }
        }

        val memoryKiB: Int get() = m
        val iterations: Int get() = t
        val parallelism: Int get() = p

        companion object {
            val Default = KdfParams(
                m = DEFAULT_MEMORY_KIB,
                t = DEFAULT_ITERATIONS,
                p = DEFAULT_PARALLELISM,
            )
        }
    }

    @Serializable
    data class Header(
        val v: Int = FORMAT_VERSION,
        val kdf: String = "argon2id",
        val m: Int = DEFAULT_MEMORY_KIB,
        val t: Int = DEFAULT_ITERATIONS,
        val p: Int = DEFAULT_PARALLELISM,
        val saltB64: String,
        val wrapNonceB64: String,
        val wrappedKeyB64: String,
        val prefixB64: String,
        val chunkSize: Int = CHUNK_SIZE,
    ) {
        val memoryKiB: Int get() = m
        val iterations: Int get() = t
        val parallelism: Int get() = p
    }

    @Serializable
    data class SealedKey(
        val keyB64: String,
        val m: Int = DEFAULT_MEMORY_KIB,
        val t: Int = DEFAULT_ITERATIONS,
        val p: Int = DEFAULT_PARALLELISM,
        val saltB64: String,
        val wrapNonceB64: String,
        val wrappedKeyB64: String,
    ) {
        fun rawKey(): ByteArray = Base64.getDecoder().decode(keyB64)
    }

    fun isValidPassphrase(passphrase: CharArray): Boolean =
        passphrase.size >= MIN_PASSPHRASE_LENGTH && passphrase.any { !it.isWhitespace() }

    fun mintKey(
        passphrase: CharArray,
        kdf: KdfParams = KdfParams.Default,
        random: SecureRandom = SecureRandom(),
    ): SealedKey {
        require(isValidPassphrase(passphrase)) {
            "Passphrase must be at least $MIN_PASSPHRASE_LENGTH non-blank characters"
        }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val wrapNonce = ByteArray(WRAP_NONCE_BYTES).also(random::nextBytes)
        val rawK = ByteArray(KEY_BYTES).also(random::nextBytes)
        val kek = deriveKek(passphrase, salt, kdf.m, kdf.t, kdf.p)
        try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, wrapNonce))
            cipher.updateAAD(wrapAad(kdf.m, kdf.t, kdf.p))
            val wrappedK = cipher.doFinal(rawK)
            val b64 = Base64.getEncoder().withoutPadding()
            return SealedKey(
                keyB64 = b64.encodeToString(rawK),
                m = kdf.m,
                t = kdf.t,
                p = kdf.p,
                saltB64 = b64.encodeToString(salt),
                wrapNonceB64 = b64.encodeToString(wrapNonce),
                wrappedKeyB64 = b64.encodeToString(wrappedK),
            )
        } finally {
            kek.fill(0)
            rawK.fill(0)
        }
    }

    fun unwrapKey(header: Header, passphrase: CharArray): ByteArray? {
        if (!isValidPassphrase(passphrase)) return null
        if (!isValidHeader(header)) return null
        val b64 = Base64.getDecoder()
        val salt = runCatching { b64.decode(header.saltB64) }.getOrNull() ?: return null
        val wrapNonce = runCatching { b64.decode(header.wrapNonceB64) }.getOrNull() ?: return null
        val wrappedK = runCatching { b64.decode(header.wrappedKeyB64) }.getOrNull() ?: return null
        if (salt.size != SALT_BYTES || wrapNonce.size != WRAP_NONCE_BYTES || wrappedK.size != KEY_BYTES + GCM_TAG_BYTES) {
            return null
        }
        val kek = deriveKek(passphrase, salt, header.m, header.t, header.p)
        return try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(GCM_TAG_BITS, wrapNonce))
            cipher.updateAAD(wrapAad(header.m, header.t, header.p))
            cipher.doFinal(wrappedK)
        } catch (_: Exception) {
            null
        } finally {
            kek.fill(0)
        }
    }

    fun seal(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
        kdf: KdfParams = KdfParams.Default,
        keyStore: BackupKeyStore? = null,
        random: SecureRandom = SecureRandom(),
    ): SealedKey {
        val sealedKey = mintKey(passphrase, kdf, random)
        keyStore?.save(sealedKey)
        reseal(input, output, sealedKey, random)
        return sealedKey
    }

    fun reseal(
        input: InputStream,
        output: OutputStream,
        sealedKey: SealedKey,
        random: SecureRandom = SecureRandom(),
    ): Header {
        val prefix = ByteArray(STREAM_PREFIX_BYTES).also(random::nextBytes)
        val prefixB64 = Base64.getEncoder().withoutPadding().encodeToString(prefix)
        val header = Header(
            v = FORMAT_VERSION,
            kdf = "argon2id",
            m = sealedKey.m,
            t = sealedKey.t,
            p = sealedKey.p,
            saltB64 = sealedKey.saltB64,
            wrapNonceB64 = sealedKey.wrapNonceB64,
            wrappedKeyB64 = sealedKey.wrappedKeyB64,
            prefixB64 = prefixB64,
            chunkSize = CHUNK_SIZE,
        )
        val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
        output.write(headerBytes)
        output.write('\n'.code)
        val key = sealedKey.rawKey()
        try {
            encryptChunks(input, output, header, headerBytes, key)
        } finally {
            key.fill(0)
        }
        return header
    }

    fun reseal(
        input: InputStream,
        output: OutputStream,
        keyStore: BackupKeyStore,
        random: SecureRandom = SecureRandom(),
    ): Header? {
        val sealedKey = keyStore.load() ?: return null
        return reseal(input, output, sealedKey, random)
    }

    fun readHeader(input: InputStream): Header? {
        val headerBytes = readHeaderLine(input) ?: return null
        val header = runCatching {
            json.decodeFromString(Header.serializer(), headerBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        return header.takeIf(::isValidHeader)
    }

    fun open(
        input: InputStream,
        output: OutputStream,
        passphrase: CharArray,
    ): Boolean {
        if (!isValidPassphrase(passphrase)) return false
        val headerBytes = readHeaderLine(input) ?: return false
        val header = runCatching {
            json.decodeFromString(Header.serializer(), headerBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return false
        if (!isValidHeader(header)) return false
        val key = unwrapKey(header, passphrase) ?: return false
        return try {
            decryptChunks(input, output, header, headerBytes, key)
        } finally {
            key.fill(0)
        }
    }

    fun openWithKey(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
    ): Boolean {
        if (key.size != KEY_BYTES) return false
        val headerBytes = readHeaderLine(input) ?: return false
        val header = runCatching {
            json.decodeFromString(Header.serializer(), headerBytes.toString(Charsets.UTF_8))
        }.getOrNull() ?: return false
        if (!isValidHeader(header)) return false
        val keyCopy = key.copyOf()
        return try {
            decryptChunks(input, output, header, headerBytes, keyCopy)
        } finally {
            keyCopy.fill(0)
        }
    }

    fun sealManifest(
        manifest: BackupManifest,
        output: OutputStream,
        passphrase: CharArray,
        kdf: KdfParams = KdfParams.Default,
        keyStore: BackupKeyStore? = null,
        random: SecureRandom = SecureRandom(),
    ): SealedKey {
        val bytes = manifest.encode().toByteArray(Charsets.UTF_8)
        return seal(ByteArrayInputStream(bytes), output, passphrase, kdf, keyStore, random)
    }

    fun resealManifest(
        manifest: BackupManifest,
        output: OutputStream,
        keyStore: BackupKeyStore,
        random: SecureRandom = SecureRandom(),
    ): Header? {
        val bytes = manifest.encode().toByteArray(Charsets.UTF_8)
        return reseal(ByteArrayInputStream(bytes), output, keyStore, random)
    }

    class Opened(val manifest: BackupManifest, val key: SealedKey)

    fun openForRestore(bytes: ByteArray, passphrase: CharArray): Opened? {
        val header = readHeader(ByteArrayInputStream(bytes)) ?: return null
        val raw = unwrapKey(header, passphrase) ?: return null
        try {
            val out = ByteArrayOutputStream()
            if (!openWithKey(ByteArrayInputStream(bytes), out, raw)) return null
            val manifest = BackupManifest.decode(out.toByteArray().toString(Charsets.UTF_8)) ?: return null
            val key = SealedKey(
                keyB64 = Base64.getEncoder().withoutPadding().encodeToString(raw),
                m = header.m,
                t = header.t,
                p = header.p,
                saltB64 = header.saltB64,
                wrapNonceB64 = header.wrapNonceB64,
                wrappedKeyB64 = header.wrappedKeyB64,
            )
            return Opened(manifest, key)
        } finally {
            raw.fill(0)
        }
    }

    fun openManifest(
        input: InputStream,
        passphrase: CharArray,
    ): BackupManifest? {
        val out = ByteArrayOutputStream()
        if (!open(input, out, passphrase)) return null
        return BackupManifest.decode(out.toByteArray().toString(Charsets.UTF_8))
    }

    private fun isValidHeader(header: Header): Boolean =
        header.v == FORMAT_VERSION &&
            header.kdf == "argon2id" &&
            header.m in 8_192..MAX_MEMORY_KIB &&
            header.t in 1..MAX_ITERATIONS &&
            header.p in 1..MAX_PARALLELISM &&
            header.chunkSize in 1..MAX_CHUNK_SIZE

    private fun wrapAad(m: Int, t: Int, p: Int): ByteArray =
        "NP-BACKUP-WRAP-v1|$m|$t|$p".toByteArray(Charsets.UTF_8)

    private fun deriveKek(passphrase: CharArray, salt: ByteArray, m: Int, t: Int, p: Int): ByteArray {
        val passBytes = charsToUtf8(passphrase)
        val out = ByteArray(KEY_BYTES)
        try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(m)
                .withIterations(t)
                .withParallelism(p)
                .withSalt(salt)
                .build()
            val argon = Argon2BytesGenerator()
            argon.init(params)
            argon.generateBytes(passBytes, out, 0, out.size)
            return out
        } finally {
            passBytes.fill(0)
        }
    }

    private fun charsToUtf8(chars: CharArray): ByteArray {
        val byteBuf: ByteBuffer = Charsets.UTF_8.encode(CharBuffer.wrap(chars))
        val out = ByteArray(byteBuf.remaining())
        byteBuf.get(out)
        if (byteBuf.hasArray()) {
            byteBuf.array().fill(0)
        }
        return out
    }

    private fun encryptChunks(
        input: InputStream,
        output: OutputStream,
        header: Header,
        headerBytes: ByteArray,
        key: ByteArray,
    ) {
        val chunkSize = header.chunkSize
        require(chunkSize in 1..MAX_CHUNK_SIZE) { "Invalid chunkSize: $chunkSize" }
        val prefix = Base64.getDecoder().decode(header.prefixB64)
        require(prefix.size == STREAM_PREFIX_BYTES) { "Invalid prefix length: ${prefix.size}" }
        val headerHash = sha256(headerBytes)

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        val plainBuf = ByteArray(chunkSize)
        val cipherBuf = ByteArray(chunkSize + GCM_TAG_BYTES)
        val nonce = ByteArray(STREAM_NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, STREAM_PREFIX_BYTES)
        val aad = ByteArray(32 + 1 + 4 + 4)
        System.arraycopy(headerHash, 0, aad, 0, 32)
        val frameHeader = ByteArray(5)

        var counter = 0
        var carryByte = -1

        try {
            while (true) {
                var plainLen = 0
                if (carryByte >= 0) {
                    plainBuf[0] = carryByte.toByte()
                    plainLen = 1
                    carryByte = -1
                }
                while (plainLen < chunkSize) {
                    val r = input.read(plainBuf, plainLen, chunkSize - plainLen)
                    if (r < 0) break
                    plainLen += r
                }
                val nextByte = input.read()
                val isLast = nextByte < 0
                if (!isLast) {
                    carryByte = nextByte
                }
                val lastFlag: Byte = if (isLast) 1 else 0

                putIntBe(nonce, STREAM_PREFIX_BYTES, counter)
                aad[32] = lastFlag
                putIntBe(aad, 33, counter)
                putIntBe(aad, 37, plainLen)

                cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.updateAAD(aad)
                val written = cipher.doFinal(plainBuf, 0, plainLen, cipherBuf, 0)
                check(written == plainLen + GCM_TAG_BYTES) { "Unexpected GCM output length: $written" }

                frameHeader[0] = lastFlag
                putIntBe(frameHeader, 1, plainLen)
                output.write(frameHeader)
                output.write(cipherBuf, 0, written)

                if (isLast) break
                check(counter != Int.MAX_VALUE) { "Stream exceeds maximum chunk count" }
                counter++
            }
        } finally {
            plainBuf.fill(0)
            cipherBuf.fill(0)
        }
    }

    private fun decryptChunks(
        input: InputStream,
        output: OutputStream,
        header: Header,
        headerBytes: ByteArray,
        key: ByteArray,
    ): Boolean {
        val chunkSize = header.chunkSize
        if (chunkSize !in 1..MAX_CHUNK_SIZE) return false
        val prefix = runCatching { Base64.getDecoder().decode(header.prefixB64) }.getOrNull() ?: return false
        if (prefix.size != STREAM_PREFIX_BYTES) return false
        val headerHash = sha256(headerBytes)

        val secretKey = SecretKeySpec(key, "AES")
        val cipher = Cipher.getInstance(AES_GCM)
        val plainBuf = ByteArray(chunkSize)
        val cipherBuf = ByteArray(chunkSize + GCM_TAG_BYTES)
        val nonce = ByteArray(STREAM_NONCE_BYTES)
        System.arraycopy(prefix, 0, nonce, 0, STREAM_PREFIX_BYTES)
        val aad = ByteArray(32 + 1 + 4 + 4)
        System.arraycopy(headerHash, 0, aad, 0, 32)
        val frameHeader = ByteArray(5)

        var counter = 0
        try {
            while (true) {
                if (!readExact(input, frameHeader, 0, 5)) return false
                val lastFlag = frameHeader[0]
                if (lastFlag != 0.toByte() && lastFlag != 1.toByte()) return false
                val plainLen = getIntBe(frameHeader, 1)
                if (plainLen < 0 || plainLen > chunkSize) return false
                if (lastFlag == 0.toByte() && plainLen != chunkSize) return false

                val cipherLen = plainLen + GCM_TAG_BYTES
                if (!readExact(input, cipherBuf, 0, cipherLen)) return false

                putIntBe(nonce, STREAM_PREFIX_BYTES, counter)
                aad[32] = lastFlag
                putIntBe(aad, 33, counter)
                putIntBe(aad, 37, plainLen)

                cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.updateAAD(aad)
                val decryptedLen = cipher.doFinal(cipherBuf, 0, cipherLen, plainBuf, 0)
                if (decryptedLen != plainLen) return false

                if (lastFlag == 1.toByte()) {
                    if (input.read() != -1) return false
                    if (plainLen > 0) output.write(plainBuf, 0, plainLen)
                    return true
                }
                if (plainLen > 0) output.write(plainBuf, 0, plainLen)
                if (counter == Int.MAX_VALUE) return false
                counter++
            }
        } catch (_: Exception) {
            return false
        } finally {
            plainBuf.fill(0)
            cipherBuf.fill(0)
        }
    }

    private fun readHeaderLine(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream(256)
        while (buf.size() < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) return buf.toByteArray()
            buf.write(b)
        }
        return null
    }

    private fun readExact(input: InputStream, buf: ByteArray, off: Int, len: Int): Boolean {
        var total = 0
        while (total < len) {
            val r = input.read(buf, off + total, len - total)
            if (r < 0) return false
            total += r
        }
        return true
    }

    private fun putIntBe(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value ushr 24).toByte()
        buf[offset + 1] = (value ushr 16).toByte()
        buf[offset + 2] = (value ushr 8).toByte()
        buf[offset + 3] = value.toByte()
    }

    private fun getIntBe(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 24) or
            ((buf[offset + 1].toInt() and 0xFF) shl 16) or
            ((buf[offset + 2].toInt() and 0xFF) shl 8) or
            (buf[offset + 3].toInt() and 0xFF)

    private fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)
}
