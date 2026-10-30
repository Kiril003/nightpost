package local.phantom.companion.data

import java.io.File
import java.security.MessageDigest
import local.phantom.companion.core.net.peer.PeerProfile

/**
 * Фото людей, що приїхали тегом фото в [PeerProfile]. Окремими файлами, а не в
 * реєстрі: реєстр переписується на кожен лист, а фото — до 24 КБ на людину.
 */
class PeerPhotoStore(private val dir: File) {

    /** Кличеться лише коли реєстр кадр ПРИЙНЯВ — старий rev сюди не доходить. */
    fun absorb(fromId: String, body: String) {
        val frame = PeerProfile.decode(body) ?: return
        when (val p = frame.photo) {
            is PeerProfile.Field.Set -> if (PeerProfile.imageKind(p.value) != null) write(fromId, p.value)
            PeerProfile.Field.Cleared -> forget(fromId)
            PeerProfile.Field.Absent -> Unit
        }
    }

    fun forget(id: String) {
        File(dir, nameOf(id)).delete()
    }

    fun has(id: String): Boolean = File(dir, nameOf(id)).isFile

    /** Файл за іменем з адреси. Імʼя — лише 32 hex, інакше не шлях, а спроба вийти з каталогу. */
    fun file(name: String): File? =
        if (NAME.matches(name)) File(dir, name).takeIf { it.isFile } else null

    private fun write(id: String, bytes: ByteArray) {
        dir.mkdirs()
        val tmp = File(dir, nameOf(id) + ".tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(File(dir, nameOf(id)))) tmp.delete()
        }
    }

    companion object {
        private val NAME = Regex("[0-9a-f]{32}")

        fun nameOf(id: String): String =
            MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(32)
    }
}
