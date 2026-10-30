package local.phantom.companion.data

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.File
import local.phantom.companion.core.net.peer.PeerProfile

/** Своє обличчя на вкладці «Профіль»: фото, обкладинка й імʼя правляться на місці, без форми. */
class SelfFace(
    private val dir: File,
    private val activeId: suspend () -> String?,
    private val rename: suspend (String) -> Boolean,
    private val herald: () -> ProfileHerald,
) {

    /** Обкладинка своя в кожної особи: друга особа на тому самому телефоні її не бачить. */
    fun coverFile(profileId: String): File = File(dir, "cover-" + profileId.replace(UNSAFE, "_") + ".img")

    suspend fun cover(): File? = activeId()?.let { coverFile(it) }?.takeIf { it.isFile }

    suspend fun setCover(bytes: ByteArray): Boolean {
        val id = activeId() ?: return false
        dir.mkdirs()
        val out = coverFile(id)
        val tmp = File(dir, out.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(out)) {
            out.writeBytes(bytes)
            tmp.delete()
        }
        return true
    }

    suspend fun clearCover(): Boolean = activeId()?.let { coverFile(it).delete() } ?: false

    /** Імʼя тим самим шляхом, що й міст веба: база, запрошення, кадр профілю всім. */
    suspend fun setName(raw: String): String? {
        val name = (PeerProfile.fitName(raw) as? PeerProfile.Field.Set)?.value ?: return null
        if (rename(name)) herald().bump()
        return name
    }

    /** Фото йде людям тим самим кадром, що й з веба: мініатюра і велике. */
    fun setPhoto(thumb: ByteArray, photo: ByteArray): Boolean =
        herald().publish(
            0L,
            PeerProfile.Body(rev = 0L, avatar = PeerProfile.Field.Set(thumb), photo = PeerProfile.Field.Set(photo)),
        ) != null

    fun clearPhoto(): Boolean =
        herald().publish(
            0L,
            PeerProfile.Body(rev = 0L, avatar = PeerProfile.Field.Cleared, photo = PeerProfile.Field.Cleared),
        ) != null

    fun setAbout(statusText: String, bio: String): Boolean {
        val st = statusText.trim().let { if (it.isEmpty()) PeerProfile.Field.Cleared else PeerProfile.Field.Set(it) }
        val b = bio.trim().let { if (it.isEmpty()) PeerProfile.Field.Cleared else PeerProfile.Field.Set(it) }
        return herald().publish(
            0L,
            PeerProfile.Body(rev = 0L, statusEmoji = PeerProfile.Field.Cleared, statusText = st, bio = b),
        ) != null
    }

    fun clearForProfile(profileId: String) {
        coverFile(profileId).delete()
        herald().clearProfile(profileId)
    }

    /**
     * Старий екран профілю клав фото лише в `avatarUri` бази, а вкладка й люди
     * читають кадр профілю. Один раз переносимо: немає кадрового фото — беремо з uri.
     */
    fun adoptLegacyPhoto(avatarUri: String?, load: (String) -> Pair<ByteArray, ByteArray>?): Boolean {
        val h = herald()
        if (avatarUri.isNullOrBlank() || h.ownPhotoFile() != null || h.hasClearingOrPhoto) return false
        val (thumb, photo) = runCatching { load(avatarUri) }.getOrNull() ?: return false
        return setPhoto(thumb, photo)
    }

    companion object {
        private val UNSAFE = Regex("[^A-Za-z0-9_-]")

        val THUMB_SIDES = listOf(96, 80, 64, 48)
        val PHOTO_SIDES = listOf(512, 384, 320, 256)
        const val COVER_SIDE = 1280
        private val QUALITIES = listOf(82, 70, 58, 46)

        /** Найбільший розмір, що влазить у стелю; null — не влазить жоден. */
        fun fit(cap: Int, sides: List<Int>, encode: (side: Int, quality: Int) -> ByteArray?): ByteArray? {
            for (side in sides) for (q in QUALITIES) {
                val bytes = encode(side, q) ?: continue
                if (bytes.size <= cap) return bytes
            }
            return null
        }

        fun centerSquare(src: Bitmap): Bitmap {
            val s = minOf(src.width, src.height)
            return Bitmap.createBitmap(src, (src.width - s) / 2, (src.height - s) / 2, s, s)
        }

        fun jpeg(src: Bitmap, side: Int, quality: Int): ByteArray {
            val scale = side.toFloat() / maxOf(src.width, src.height)
            val scaled = if (scale >= 1f) src else
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
            return ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
                out.toByteArray()
            }
        }
    }
}
