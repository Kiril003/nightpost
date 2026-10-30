package local.phantom.companion.feature.stream.ui.map.nav

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import java.io.File

/**
 * verification-is-a-time-series — the instrument the road drive is judged by.
 *
 * A screenshot cannot prove that the puck did not slide, and a logcat buffer
 * is gone by the time the operator parks. So a guidance session writes one
 * line per second to a file that survives the drive and pulls off the phone
 * without root:
 *
 *     adb pull /sdcard/Android/data/<pkg>/files/nav-journal/
 *
 * Debug builds only, and cheap by construction: the frame thread does a
 * [Channel.trySend] into a bounded buffer that drops the OLDEST line when the
 * writer falls behind — a journal may lose a second, it may never stall the
 * frame that draws the road.
 */
internal class NavJournal(private val dir: File) {

    private val lines = Channel<String>(capacity = QUEUE, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    fun offer(line: String) {
        lines.trySend(line)
    }

    /** Runs until its scope is cancelled. One session = one file. */
    suspend fun drain() = withContext(Dispatchers.IO) {
        val file = runCatching { openSession() }.getOrNull() ?: return@withContext
        val sink = runCatching { file.bufferedWriter() }.getOrNull() ?: return@withContext
        try {
            sink.appendLine(HEADER)
            var bytes = HEADER.length.toLong()
            for (line in lines) {
                sink.appendLine(line)
                sink.flush()
                bytes += line.length + 1
                // A capped file is the point: a drive that runs all day must
                // not be the reason the phone has no room left for maps.
                if (bytes >= MAX_SESSION_BYTES) break
            }
        } catch (_: java.io.IOException) {
            // A journal that throws would take the drive down with it.
        } finally {
            runCatching { sink.close() }
        }
    }

    private fun openSession(): File {
        dir.mkdirs()
        dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(KEEP_SESSIONS - 1)
            ?.forEach { it.delete() }
        return File(dir, "$PREFIX${System.currentTimeMillis()}.csv")
    }

    private companion object {
        const val PREFIX = "nav-"
        const val HEADER = "# ts_ms,fix,engine,lat,lon,speed_mps"
        const val QUEUE = 256
        const val KEEP_SESSIONS = 5
        const val MAX_SESSION_BYTES = 1L shl 20
    }
}
