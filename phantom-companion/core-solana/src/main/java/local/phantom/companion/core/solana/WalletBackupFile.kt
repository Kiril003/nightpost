package local.phantom.companion.core.solana

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Зашифрована копія гаманця у файлі: слова під паролем людини (PBKDF2 → AES-GCM).
 * Без пароля файл — шум; пароль ніде не зберігається.
 */
object WalletBackupFile {

    const val MIME = "application/octet-stream"
    const val NAME = "nightpost-wallet.backup"
    const val MIN_PASSWORD = 8
    private const val ITERATIONS = 210_000

    @Serializable
    private data class Sealed(val nightpost_wallet: Int = 1, val iter: Int, val salt: String, val iv: String, val data: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun seal(words: List<String>, password: CharArray, random: SecureRandom = SecureRandom()): ByteArray {
        require(password.size >= MIN_PASSWORD)
        val salt = ByteArray(16).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val plain = words.joinToString(" ").toByteArray()
        try {
            val out = cipher(Cipher.ENCRYPT_MODE, password, salt, iv, ITERATIONS).doFinal(plain)
            val b64 = Base64.getEncoder()
            return json.encodeToString(Sealed.serializer(), Sealed(iter = ITERATIONS, salt = b64.encodeToString(salt), iv = b64.encodeToString(iv), data = b64.encodeToString(out))).toByteArray()
        } finally {
            plain.fill(0)
        }
    }

    /** null — не наш файл або пароль не той: GCM не відрізняє одне від другого, і людині це байдуже. */
    fun open(bytes: ByteArray, password: CharArray): List<String>? = runCatching {
        val s = json.decodeFromString(Sealed.serializer(), bytes.decodeToString())
        if (s.nightpost_wallet != 1 || s.iter !in 10_000..5_000_000) return null
        val b64 = Base64.getDecoder()
        val plain = cipher(Cipher.DECRYPT_MODE, password, b64.decode(s.salt), b64.decode(s.iv), s.iter).doFinal(b64.decode(s.data))
        try {
            plain.decodeToString().split(' ').filter { it.isNotEmpty() }
        } finally {
            plain.fill(0)
        }
    }.getOrNull()

    private fun cipher(mode: Int, password: CharArray, salt: ByteArray, iv: ByteArray, iter: Int): Cipher {
        val spec = PBEKeySpec(password, salt, iter, 256)
        val key = try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
        try {
            return Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
        } finally {
            key.fill(0)
        }
    }
}
