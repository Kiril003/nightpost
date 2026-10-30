package local.phantom.companion.data

import android.content.Context
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** «Тиша вночі»: у ці години листи й дзвінки приходять без звуку, крім людей із [except]. */
data class QuietHours(
    val enabled: Boolean = false,
    val from: LocalTime = LocalTime.of(23, 0),
    val to: LocalTime = LocalTime.of(7, 0),
    val except: Set<String> = emptySet(),
) {

    fun covers(at: LocalTime): Boolean = when {
        !enabled || from == to -> false
        from < to -> at >= from && at < to
        else -> at >= from || at < to
    }

    fun hushes(peerId: String, at: LocalTime): Boolean = covers(at) && peerId !in except

    // Пояс береться щоразу, а не один раз: людина перелетіла — ніч перелетіла з нею.
    fun hushes(peerId: String, at: Instant, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        hushes(peerId, at.atZone(zone).toLocalTime())

    companion object {
        private val HHMM = Regex("""^([01]?\d|2[0-3]):([0-5]\d)$""")

        fun parse(s: String): LocalTime? =
            HHMM.find(s.trim())?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        fun format(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)
    }
}

class QuietHoursStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<QuietHours> = _state.asStateFlow()

    fun hushesNow(peerId: String): Boolean = _state.value.hushes(peerId, Instant.now())

    fun set(q: QuietHours) {
        _state.value = q
        prefs.edit()
            .putBoolean(KEY_ON, q.enabled)
            .putString(KEY_FROM, QuietHours.format(q.from))
            .putString(KEY_TO, QuietHours.format(q.to))
            .putStringSet(KEY_EXCEPT, q.except.toSet())
            .apply()
    }

    private fun read(): QuietHours {
        val base = QuietHours()
        return QuietHours(
            enabled = prefs.getBoolean(KEY_ON, base.enabled),
            from = prefs.getString(KEY_FROM, null)?.let(QuietHours::parse) ?: base.from,
            to = prefs.getString(KEY_TO, null)?.let(QuietHours::parse) ?: base.to,
            // getStringSet віддає живий набір самих налаштувань — лише копією.
            except = prefs.getStringSet(KEY_EXCEPT, null)?.toSet() ?: base.except,
        )
    }

    private companion object {
        const val FILE_NAME = "phantom_quiet_hours_v1"
        const val KEY_ON = "on"
        const val KEY_FROM = "from"
        const val KEY_TO = "to"
        const val KEY_EXCEPT = "except"
    }
}
