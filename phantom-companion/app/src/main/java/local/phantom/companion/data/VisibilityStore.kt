package local.phantom.companion.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.net.peer.PeerIdentity

/** «Був(ла) в мережі», «Фото профілю», «Дзвінки»: Усі / Мої люди (хто в колах) / Ніхто. */
class VisibilityStore(context: Context) {

    enum class Level(val wire: String) {
        ALL("all"),
        PEOPLE("people"),
        NOBODY("nobody");

        fun allows(inCircle: Boolean): Boolean = this == ALL || (this == PEOPLE && inCircle)

        companion object {
            fun of(wire: String?): Level? = entries.firstOrNull { it.wire == wire }
        }
    }

    data class Choice(val lastSeen: Level = Level.ALL, val photo: Level = Level.ALL, val calls: Level = Level.ALL) {
        fun hides(inCircle: Boolean): Int =
            (if (photo.allows(inCircle)) 0 else HIDE_PHOTO) or (if (lastSeen.allows(inCircle)) 0 else HIDE_SEEN)
    }

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _choice = MutableStateFlow(
        Choice(
            lastSeen = Level.of(prefs.getString(KEY_SEEN, null)) ?: Level.ALL,
            photo = Level.of(prefs.getString(KEY_PHOTO, null)) ?: Level.ALL,
            calls = Level.of(prefs.getString(KEY_CALLS, null)) ?: Level.ALL,
        ),
    )
    val choice: StateFlow<Choice> = _choice.asStateFlow()

    fun set(next: Choice) {
        _choice.value = next
        prefs.edit().putString(KEY_SEEN, next.lastSeen.wire).putString(KEY_PHOTO, next.photo.wire).putString(KEY_CALLS, next.calls.wire).apply()
    }

    companion object {
        const val HIDE_PHOTO = 1
        const val HIDE_SEEN = 2

        private const val FILE_NAME = "phantom_visibility_v1"
        private const val KEY_SEEN = "last_seen"
        private const val KEY_PHOTO = "photo"
        private const val KEY_CALLS = "calls"

        /** Взаємно, як у Telegram: хто ховає свій час від людини, не бачить і її. */
        fun seenShown(peer: PeerIdentity, mine: Int): Boolean =
            !peer.hidesSeen && mine and HIDE_SEEN == 0
    }
}
