package local.phantom.companion.core.license

import java.security.MessageDigest

object LicenseKey {

    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun normalize(raw: String): String =
        raw.trim().uppercase().replace(" ", "").replace("–", "-")

    fun isWellFormed(raw: String): Boolean {
        val parts = normalize(raw).split("-")
        if (parts.size != 5 || parts[0] != "PHTM") return false
        val body = parts.subList(1, 4).joinToString("")
        if (body.length != 15 || body.any { it !in CROCKFORD }) return false
        return parts[4] == checksum(body)
    }

    fun hash(raw: String): String = MessageDigest.getInstance("SHA-256")
        .digest(normalize(raw).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun checksum(body: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(body.toByteArray(Charsets.UTF_8))
        return digest.take(5).joinToString("") {
            CROCKFORD[(it.toInt() and 0xFF) % 32].toString()
        }
    }
}
