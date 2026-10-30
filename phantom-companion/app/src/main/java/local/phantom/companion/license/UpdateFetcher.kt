package local.phantom.companion.license

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import local.phantom.companion.core.license.AvailableBuild

/**
 * Забирає оголошену збірку у файл і каже, ЧИ ЦЕ ВОНА.
 *
 * Жодного типу з android.* тут немає навмисно: усе, що вирішує долю 156 МБ на
 * поганій мережі — відновлення після обриву, стеля розміру, звірка суми, — має
 * перевірятися звичайним юніт-тестом, а не руками з телефоном у полі.
 *
 * Сума приїжджає в ПІДПИСАНІЙ політиці, тож звірка тут — це порівняння з тим,
 * що сказав власник ключа, а не з тим, що сказав сервер роздачі. Посилання
 * підписане й тимчасове; байти за ним — ні.
 */
class UpdateFetcher(
    private val cacheDir: File,
    private val open: (url: String, from: Long) -> Source = ::httpSource,
) {

    /** Потік байтів із відповіді разом із тим, що відповідь про себе каже. */
    class Source(
        val stream: InputStream,
        /** Чи сервер СПРАВДІ продовжив з місця обриву (206), а не почав спочатку. */
        val resumed: Boolean,
        /** Скільки байтів лишилось у цій відповіді, або -1 — не сказано. */
        val remaining: Long,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        override fun close() {
            runCatching { stream.close() }
            onClose()
        }
    }

    enum class Reason {
        /** Мережа обірвалась або сервер відмовив. Продовжити можна. */
        Network,

        /** Прийшло не те, що оголошено. Продовжувати НЕ можна. */
        Mismatch,

        /** Немає місця. Продовжити можна, звільнивши його. */
        Storage,
    }

    sealed interface Result {
        data class Ready(val file: File) : Result
        data class Failed(val reason: Reason) : Result
    }

    fun target(build: AvailableBuild): File = File(dir(), "${build.build}-${build.filename}")

    private fun part(build: AvailableBuild): File = File(dir(), "${build.build}-${build.filename}.part")

    private fun dir(): File = File(cacheDir, DIR).apply { mkdirs() }

    /**
     * Прибирає все, крім цієї збірки: недокачані шматки попередніх оголошень
     * інакше жили б у кеші до кінця світу, а важать вони як застосунок.
     */
    fun forget(keep: AvailableBuild? = null) {
        val keepNames = setOfNotNull(keep?.let { target(it).name }, keep?.let { part(it).name })
        dir().listFiles()?.forEach { if (it.name !in keepNames) it.delete() }
    }

    /**
     * @param onProgress скільки байтів уже лежить на диску — для картки.
     */
    suspend fun fetch(
        build: AvailableBuild,
        url: String,
        onProgress: (Long) -> Unit = {},
    ): Result {
        val done = target(build)
        // Уже завантажене й уже звірене не качаємо вдруге: людина, яка
        // відмовилась від встановлення й повернулась через годину, не має
        // платити за ті самі 156 МБ ще раз.
        if (done.length() == build.sizeBytes && digestOf(done) == build.sha256) {
            return Result.Ready(done)
        }
        done.delete()

        val part = part(build)
        var have = part.length()
        if (have > build.sizeBytes) {
            // Шматок більший за оголошену збірку — це вже не її шматок.
            part.delete()
            have = 0
        }
        if (dir().usableSpace < build.sizeBytes - have + SLACK_BYTES) {
            return Result.Failed(Reason.Storage)
        }
        onProgress(have)

        try {
            var attempts = 0
            while (have < build.sizeBytes) {
                coroutineContext.ensureActive()
                // Обрив посеред 156 МБ — норма, а не виняток, тож продовжуємо в
                // тому ж заході. Стеля — щоб сервер, який віддає по краплі, не
                // тримав нас у циклі до кінця батареї.
                if (++attempts > MAX_ATTEMPTS) return Result.Failed(Reason.Network)
                val source = runCatching { open(url, have) }.getOrNull()
                    ?: return Result.Failed(Reason.Network)
                val before = have
                source.use {
                    // Сервер, який зігнорував Range, віддає ВСЕ з нуля (200).
                    // Дописати це в хвіст наявного означало б файл-химеру, чия
                    // сума не зійдеться ніколи — тож починаємо спочатку.
                    if (have > 0 && !it.resumed) {
                        part.delete()
                        have = 0
                    }
                    if (it.remaining >= 0 && have + it.remaining != build.sizeBytes) {
                        // Відповідь описує не ту збірку, яку оголосили.
                        part.delete()
                        return Result.Failed(Reason.Mismatch)
                    }
                    have = drain(it.stream, part, from = have, limit = build.sizeBytes, onProgress)
                }
                // Мережа впала, не давши ЖОДНОГО нового байта: ще одна спроба
                // з тим самим зміщенням крутила б цикл без кінця.
                if (have <= before) return Result.Failed(Reason.Network)
            }
        } catch (io: java.io.IOException) {
            return Result.Failed(if (isSpace(io)) Reason.Storage else Reason.Network)
        }

        if (digestOf(part) != build.sha256) {
            // Байти не ті, які підписав власник. Лишити їх на диску означало б
            // тримати напоготові файл, який комусь колись здасться придатним.
            part.delete()
            return Result.Failed(Reason.Mismatch)
        }
        if (!part.renameTo(done)) return Result.Failed(Reason.Storage)
        return Result.Ready(done)
    }

    private suspend fun drain(
        stream: InputStream,
        part: File,
        from: Long,
        limit: Long,
        onProgress: (Long) -> Unit,
    ): Long {
        var have = from
        val buffer = ByteArray(CHUNK)
        java.io.RandomAccessFile(part, "rw").use { file ->
            file.setLength(have)
            file.seek(have)
            while (have < limit) {
                coroutineContext.ensureActive()
                val read = try {
                    stream.read(buffer, 0, minOf(buffer.size.toLong(), limit - have).toInt())
                } catch (io: java.io.IOException) {
                    if (isSpace(io)) throw io
                    break
                }
                if (read <= 0) break
                file.write(buffer, 0, read)
                have += read
                onProgress(have)
            }
        }
        return have
    }

    private fun digestOf(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = stream.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isSpace(io: java.io.IOException): Boolean =
        io.message?.contains("space", ignoreCase = true) == true

    companion object {
        const val DIR = "updates"
        const val MAX_ATTEMPTS = 40
        private const val CHUNK = 128 * 1024

        /** Запас понад саму збірку: розпакування ставить свою копію поруч. */
        private const val SLACK_BYTES = 64L * 1024 * 1024
    }
}

private fun httpSource(url: String, from: Long): UpdateFetcher.Source {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 30_000
    if (from > 0) connection.setRequestProperty("Range", "bytes=$from-")
    connection.connect()
    if (connection.responseCode !in 200..299) {
        connection.disconnect()
        throw java.io.IOException("HTTP ${connection.responseCode}")
    }
    return UpdateFetcher.Source(
        stream = connection.inputStream,
        resumed = connection.responseCode == HttpURLConnection.HTTP_PARTIAL,
        remaining = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1,
        onClose = connection::disconnect,
    )
}
