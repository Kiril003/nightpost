package local.phantom.companion.data.nearby

import local.phantom.companion.core.net.crypto.randomBytes
import java.security.MessageDigest

/**
 * Імʼя, яким телефон оголошує себе в ефірі. Не імʼя людини і не сталий хеш:
 * свіжа сіль на кожне вікно, тож сторонній не звʼяже два вікна в один слід.
 * Знайомий, що знає PHANTOM-id, перевіряє сіль і впізнає.
 */
object NearbyTag {

    private const val PREFIX = "P1"
    private const val SALT = 6
    private const val MARK = 8

    fun of(selfId: String, salt: ByteArray = randomBytes(SALT)): String {
        require(salt.size == SALT)
        return PREFIX + b64(salt + mark(salt, selfId))
    }

    /** Хто з [candidates] стоїть за цим іменем, або null — чужий. */
    fun whose(endpointName: String, candidates: Collection<String>): String? {
        if (!endpointName.startsWith(PREFIX)) return null
        val raw = runCatching { unB64(endpointName.removePrefix(PREFIX)) }.getOrNull() ?: return null
        if (raw.size != SALT + MARK) return null
        val salt = raw.copyOfRange(0, SALT)
        val seen = raw.copyOfRange(SALT, raw.size)
        return candidates.firstOrNull { MessageDigest.isEqual(mark(salt, it), seen) }
    }

    private fun mark(salt: ByteArray, id: String): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            update("phantom-nearby-v1".toByteArray(Charsets.UTF_8))
            update(salt)
            update(id.toByteArray(Charsets.UTF_8))
            digest()
        }.copyOf(MARK)

    private fun b64(bytes: ByteArray): String =
        java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun unB64(text: String): ByteArray = java.util.Base64.getUrlDecoder().decode(text)
}
