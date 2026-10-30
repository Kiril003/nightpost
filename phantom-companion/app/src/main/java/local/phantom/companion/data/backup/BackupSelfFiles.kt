package local.phantom.companion.data.backup

import java.io.File

object BackupSelfFiles {

    private val PATH = Regex("^(p_[A-Za-z0-9_-]+/)?(state\\.json|thumb\\.img|photo\\.img)$|^cover-[A-Za-z0-9_-]+\\.img$")
    private const val MAX_BYTES = 8L * 1024 * 1024

    fun allowed(path: String): Boolean = PATH.matches(path)

    fun collect(dir: File): Map<String, ByteArray> {
        if (!dir.isDirectory) return emptyMap()
        val out = sortedMapOf<String, ByteArray>()
        dir.walkTopDown().maxDepth(2).filter { it.isFile && it.length() in 1..MAX_BYTES }.forEach { f ->
            val rel = f.relativeTo(dir).invariantSeparatorsPath
            if (allowed(rel)) runCatching { out[rel] = f.readBytes() }
        }
        return out
    }

    fun restore(dir: File, files: Map<String, ByteArray>) {
        for ((rel, bytes) in files) {
            if (!allowed(rel)) continue
            val target = File(dir, rel)
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }
    }
}
