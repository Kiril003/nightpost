package local.phantom.companion.data

import java.io.File
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerProfile

/**
 * Фото банерів людей, що приїхали кадрами `PH-PBN:`. Файл зветься хешем своїх
 * байтів, тож одне фото двох профілів лежить один раз.
 *
 * Фото лягає на диск лише коли його хеш стоїть в описі банера ЦІЄЇ людини
 * (тег 11–12 у її профілі). Фото, що випередило опис, чекає в памʼяті — не
 * більше шести на людину — і забирається, щойно опис приїде.
 */
class PeerBannerStore(private val dir: File) {

    private val early = LinkedHashMap<String, LinkedHashMap<String, ByteArray>>()

    /** True — фото лягло. [spec] — `bannerSpec` людини з реєстру. */
    @Synchronized
    fun absorb(fromId: String, body: String, spec: String?): Boolean {
        val piece = PeerBanner.decode(body) ?: return false
        if (piece.hash in hashesOf(spec)) {
            write(piece.hash, piece.bytes)
            early[fromId]?.remove(piece.hash)
            return true
        }
        if (fromId !in early && early.size >= MAX_EARLY_PEOPLE) early.remove(early.keys.first())
        val held = early.getOrPut(fromId) { LinkedHashMap() }
        held.remove(piece.hash)
        if (held.size >= PeerProfile.MAX_BANNER_ITEMS) held.remove(held.keys.first())
        held[piece.hash] = piece.bytes
        return false
    }

    /**
     * Вхід з дроту на кожен лист: [profileApplied] — реєстр щойно прийняв профіль
     * людини, [spec] — її опис уже після нього, [all] — описи всіх у реєстрі.
     */
    @Synchronized
    fun take(fromId: String, body: String, profileApplied: Boolean, spec: String?, all: () -> Collection<String?>) {
        if (profileApplied) {
            adopt(fromId, spec)
            keepOnly(all())
        }
        absorb(fromId, body, spec)
    }

    /** Опис людини прийнято: забрати фото, що приїхали раніше за нього. */
    @Synchronized
    fun adopt(fromId: String, spec: String?) {
        val held = early[fromId] ?: return
        val want = hashesOf(spec)
        held.entries.filter { it.key in want }.forEach { write(it.key, it.value) }
        early.remove(fromId)
    }

    /** Лишити тільки фото з [specs] — описів усіх людей у реєстрі. */
    @Synchronized
    fun keepOnly(specs: Collection<String?>) {
        val want = specs.flatMap { hashesOf(it) }.toSet()
        dir.listFiles()?.forEach { if (it.name !in want) it.delete() }
    }

    /** Фото банера для веба: [url] null — опис уже є, а фото ще в дорозі. */
    data class Shown(val url: String?, val fx: Int, val fy: Int)
    data class Row(val videoStill: Boolean, val items: List<Shown>)

    /** Null — банера немає (не повідомлено або прибрано). */
    fun row(spec: String?, mount: String): Row? {
        val b = spec?.let { PeerProfile.Banner.parse(it) } ?: return null
        return Row(b.videoStill, b.items.map { Shown(if (has(it.hash)) mount + it.hash else null, it.fx, it.fy) })
    }

    fun has(hash: String): Boolean = NAME.matches(hash) && File(dir, hash).isFile

    /** Файл за іменем з адреси: лише 32 hex, інакше це спроба вийти з каталогу. */
    fun file(name: String): File? = if (NAME.matches(name)) File(dir, name).takeIf { it.isFile } else null

    private fun write(hash: String, bytes: ByteArray) {
        dir.mkdirs()
        val dst = File(dir, hash)
        if (dst.isFile) return
        val tmp = File(dir, "$hash.tmp")
        runCatching {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(dst)) tmp.delete()
        }
    }

    companion object {
        private val NAME = Regex("[0-9a-f]{32}")
        private const val MAX_EARLY_PEOPLE = 16

        fun hashesOf(spec: String?): Set<String> =
            spec?.let { PeerProfile.Banner.parse(it) }?.items?.map { it.hash }?.toSet().orEmpty()
    }
}
