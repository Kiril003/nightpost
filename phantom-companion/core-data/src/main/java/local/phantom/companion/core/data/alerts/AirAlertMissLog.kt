package local.phantom.companion.core.data.alerts

import android.content.Context

/**
 * Тривоги, що були і про які людина не дізналась.
 *
 * Зберігається рівно останній факт: область і час. Списку немає свідомо — з
 * трьох пропущених тривог не роблять журнал, з них роблять один висновок, і
 * він той самий, що з однієї.
 *
 * SharedPreferences, а не DataStore: офлайн-гейт продукту не пускає сюди
 * залежність, і це вже записано в домі.
 */
object AirAlertMissLog {

    private const val FILE = "air_alert_miss"
    private const val KEY_WHERE = "where"
    private const val KEY_AT = "at"

    data class Miss(val oblast: String, val atEpochMs: Long)

    fun record(context: Context, oblast: String, atEpochMs: Long) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_WHERE, oblast)
                .putLong(KEY_AT, atEpochMs)
                .apply()
        }
    }

    fun last(context: Context): Miss? = runCatching {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val where = prefs.getString(KEY_WHERE, null) ?: return@runCatching null
        val at = prefs.getLong(KEY_AT, 0L)
        if (at <= 0L) null else Miss(where, at)
    }.getOrNull()

    /** Прочитане — і зняте: квитанція показується раз, бо вона про подію, а не про стан. */
    fun clear(context: Context) {
        runCatching {
            context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
