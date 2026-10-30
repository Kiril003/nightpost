package local.phantom.companion.feature.precision

import android.content.Context
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Пише в `Android/data/<pkg>/files/blackbox/` — звідти `adb pull` без root. */
class BlackBoxWriter(context: Context) {

    private val enabled = context.resources.getBoolean(R.bool.precision_black_box)
    private val dir = File(context.getExternalFilesDir(null), DIR)
    private val lock = Any()
    private var file: File? = null
    private var failed = false

    val currentFile: File? get() = synchronized(lock) { file }

    fun write(line: String) {
        if (!enabled) return
        synchronized(lock) {
            if (failed) return
            try {
                val f = file ?: open().also { file = it }
                f.appendText(line + "\n")
            } catch (e: IOException) {
                // Один раз голосно, далі тихо: повний диск не має права
                // завалити навігацію.
                Log.w(TAG, "чорна скринька не пише, запис припинено", e)
                failed = true
            }
        }
    }

    /** Закрити поточний інцидент, щоб наступний ліг в окремий файл. */
    fun rotate() {
        synchronized(lock) { file = null }
    }

    fun listRecordings(): List<File> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(EXT) }?.sortedBy { it.name } ?: emptyList()

    private fun open(): File {
        dir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return File(dir, "gnss-$stamp$EXT")
    }

    private companion object {
        const val TAG = "PhantomBlackBox"
        const val DIR = "blackbox"
        const val EXT = ".jsonl"
    }
}
