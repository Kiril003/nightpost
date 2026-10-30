package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** A group has no roster row to carry a read mark. Plain prefs: it is a timestamp. */
class PeerGroupReadStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _marks = MutableStateFlow(read())
    val marks: StateFlow<Map<String, Long>> = _marks.asStateFlow()

    fun readUpTo(groupId: String): Long = _marks.value[groupId] ?: 0L

    /** Monotonic: opening an old group must not un-read a newer one. */
    fun markRead(groupId: String, uptoMs: Long) {
        val current = _marks.value[groupId] ?: 0L
        if (uptoMs <= current) return
        val next = _marks.value + (groupId to uptoMs)
        _marks.value = next
        prefs.edit().putLong(groupId, uptoMs).apply()
    }

    /** Відновлює позначки прочитаного в групах з резервної копії. */
    fun adopt(incoming: Map<String, Long>) {
        if (incoming.isEmpty()) return
        val editor = prefs.edit()
        incoming.forEach { (gid, ts) -> editor.putLong(gid, ts) }
        editor.apply()
        _marks.value = read()
    }

    private fun read(): Map<String, Long> =
        prefs.all.mapNotNull { (k, v) -> (v as? Long)?.let { k to it } }.toMap()

    private companion object {
        const val FILE_NAME = "phantom_group_reads_v1"
    }
}
