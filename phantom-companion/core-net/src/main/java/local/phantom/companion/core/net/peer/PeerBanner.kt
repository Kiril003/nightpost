package local.phantom.companion.core.net.peer

/**
 * Одне фото банера профілю: `PH-PBN:` + base64 без доповнення від
 * `[версія][16 байт хеша][байти WebP/JPEG]`.
 *
 * Фото їде окремим кадром, бо шість фото по 24 КіБ у кадр профілю не влазять.
 * Хеш перевіряє приймач: байти, що не збігаються з хешем, не лягають на диск,
 * а приймати він їх має лише тоді, коли хеш стоїть в описі банера цієї людини
 * ([PeerProfile.Banner]). Інакше будь-хто міг би покласти будь-що нам у сховище.
 */
object PeerBanner {

    const val TAG = "PH-PBN:"
    private const val V1 = 1

    /** Стеля одного фото: 800 px по довшій стороні, рішення O2. */
    const val MAX_BYTES = 24 * 1024

    data class Piece(val hash: String, val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean = other is Piece && hash == other.hash && bytes.contentEquals(other.bytes)
        override fun hashCode(): Int = 31 * hash.hashCode() + bytes.contentHashCode()
    }

    fun isBanner(body: String): Boolean = body.startsWith(TAG)

    /** Перші 16 байт SHA-256, 32 hex. */
    fun hashOf(bytes: ByteArray): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(16).joinToString("") { "%02x".format(it) }

    /** Null — не WebP/JPEG або понад [MAX_BYTES]. */
    fun encode(bytes: ByteArray): String? {
        if (bytes.isEmpty() || bytes.size > MAX_BYTES || PeerProfile.imageKind(bytes) == null) return null
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).copyOf(16)
        val raw = byteArrayOf(V1.toByte()) + hash + bytes
        return TAG + java.util.Base64.getEncoder().withoutPadding().encodeToString(raw)
    }

    /** Null — не наш кадр, інша версія, байти не картинка або не збігаються з хешем. */
    fun decode(body: String): Piece? {
        if (!isBanner(body)) return null
        val raw = runCatching { java.util.Base64.getDecoder().decode(body.removePrefix(TAG)) }.getOrNull() ?: return null
        if (raw.size < 18 || raw[0].toInt() != V1) return null
        val bytes = raw.copyOfRange(17, raw.size)
        if (bytes.size > MAX_BYTES || PeerProfile.imageKind(bytes) == null) return null
        val hash = raw.copyOfRange(1, 17).joinToString("") { "%02x".format(it) }
        return if (hashOf(bytes) == hash) Piece(hash, bytes) else null
    }

    /**
     * Прохання глядача: `PH-PBQ:<blob>` — «дай петлю свого банера». Відео не
     * їде всім наперед: 8 МБ кожному контакту на кожну зміну — це чужий тариф.
     * Власник відповідає шматками [PeerFile] лише на свій поточний блоб.
     */
    const val PULL = "PH-PBQ:"

    fun isPull(body: String): Boolean = body.startsWith(PULL)

    fun pull(blobId: String): String? = if (PeerFile.isBlobId(blobId)) PULL + blobId else null

    fun pulled(body: String): String? =
        body.takeIf { it.startsWith(PULL) }?.removePrefix(PULL)?.trim()?.takeIf { PeerFile.isBlobId(it) }
}
