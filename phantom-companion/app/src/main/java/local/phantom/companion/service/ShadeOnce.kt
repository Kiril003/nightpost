package local.phantom.companion.service

import android.content.Context
import java.security.MessageDigest
import java.util.Base64

/**
 * Один лист — щонайбільше одна нагода потрапити в шторку.
 *
 * Пам'ять мешу про «вже бачили» — це журнал, а журнал обрізається, чиститься
 * й відновлюється з копії; копія того самого листа зі сховку після цього
 * приходила б як новий лист і дзвонила вдруге. Ключ — (профіль, розмова, id)
 * під хешем: збіг id у різних людей не глушить чужий лист, а на диску немає
 * ні id, ні імен. Переживає смерть процесу; найстаріші випадають за [CAP].
 */
class ShadeOnce internal constructor(context: Context, private val cap: Int = CAP) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val keys: LinkedHashSet<String> by lazy {
        LinkedHashSet(prefs.getString(KEY, null).orEmpty().split(' ').filter { it.isNotEmpty() })
    }

    /** true — цей лист ще не мав нагоди; відтепер має. */
    @Synchronized
    fun first(profile: String, chat: String, messageId: String): Boolean {
        if (messageId.isEmpty()) return true
        if (!keys.add(keyOf(profile, chat, messageId))) return false
        val drop = keys.size - cap
        if (drop > 0) keys.iterator().let { at -> repeat(drop) { at.next(); at.remove() } }
        prefs.edit().putString(KEY, keys.joinToString(" ")).apply()
        return true
    }

    private fun keyOf(profile: String, chat: String, messageId: String): String {
        val raw = "$profile\u0000$chat\u0000$messageId".toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw).copyOf(KEY_BYTES)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    companion object {
        internal const val CAP = 1000
        private const val KEY_BYTES = 12
        private const val FILE_NAME = "phantom_shade_once_v1"
        private const val KEY = "keys"

        @Volatile private var shared: ShadeOnce? = null

        internal fun forget() { shared = null }

        fun of(context: Context): ShadeOnce =
            shared ?: synchronized(this) { shared ?: ShadeOnce(context.applicationContext).also { shared = it } }
    }
}
