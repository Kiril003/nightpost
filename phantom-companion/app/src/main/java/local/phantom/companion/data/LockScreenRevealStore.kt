package local.phantom.companion.data

import android.content.Context
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Скільки замкнений телефон має право сказати.
 *
 * Екран блокування — це те, що бачить кожен, хто підняв телефон зі столу або
 * попросив його на блокпосту. Замок застосунку туди не дістає: сповіщення
 * малюється до нього. Тому рівень розкриття вирішується тут, а не в UI чату.
 */
enum class LockScreenReveal {
    /** Нічого. Плашки на замкненому екрані не існує. */
    HIDDEN,

    /** Є повідомлення, від кого й про що — ні. */
    REDACTED,

    /** Імʼя і текст, як у розблокованому телефоні. */
    FULL,

    /** Як вирішив телефон; де він ховає вміст — лише імʼя і «Нове повідомлення». */
    SYSTEM,
    ;

    fun visibility(): Int = when (this) {
        HIDDEN -> NotificationCompat.VISIBILITY_SECRET
        REDACTED, SYSTEM -> NotificationCompat.VISIBILITY_PRIVATE
        FULL -> NotificationCompat.VISIBILITY_PUBLIC
    }
}

/**
 * Загальний рівень плюс винятки на розмову.
 *
 * Канал навмисно НЕ несе lockscreenVisibility: канал у Android незмінний після
 * створення, і рівень, вкручений у нього, довелось би міняти новим id — тобто
 * втратою всіх налаштувань звуку, які оператор собі зробив. І головне: рівень
 * на каналі один на всіх, а тут потрібен виняток на конкретну розмову.
 */
class LockScreenRevealStore(context: Context) {

    private val prefs = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    data class Settings(
        val default: LockScreenReveal,
        val perConversation: Map<String, LockScreenReveal>,
    ) {
        fun forConversation(id: String): LockScreenReveal = perConversation[id] ?: default
    }

    /** Читається зі служби в момент доставки — без корутини і без затримки. */
    fun forConversation(id: String): LockScreenReveal = _state.value.forConversation(id)

    fun setDefault(level: LockScreenReveal) {
        if (_state.value.default == level) return
        _state.value = _state.value.copy(default = level)
        prefs.edit().putString(KEY_DEFAULT, level.name).apply()
    }

    fun setForConversation(id: String, level: LockScreenReveal?) {
        val current = _state.value.perConversation
        if (level == null) {
            if (id !in current) return
            _state.value = _state.value.copy(perConversation = current - id)
            prefs.edit().remove(PREFIX + id).apply()
            return
        }
        if (current[id] == level) return
        _state.value = _state.value.copy(perConversation = current + (id to level))
        prefs.edit().putString(PREFIX + id, level.name).apply()
    }

    private fun read(): Settings = Settings(
        default = level(prefs.getString(KEY_DEFAULT, null)) ?: SAFE_DEFAULT,
        perConversation = prefs.all
            .filterKeys { it.startsWith(PREFIX) }
            .mapNotNull { (k, v) -> level(v as? String)?.let { k.removePrefix(PREFIX) to it } }
            .toMap(),
    )

    // Зіпсований або застарілий запис — це не привід показати більше, ніж
    // просили: невідоме значення падає в безпечний рівень, не в FULL.
    private fun level(raw: String?): LockScreenReveal? =
        LockScreenReveal.entries.firstOrNull { it.name == raw }

    companion object {
        // Хто ніколи не обирав, іде за телефоном: записаний вибір не чіпаємо.
        val SAFE_DEFAULT = LockScreenReveal.SYSTEM
        private const val FILE_NAME = "phantom_lockscreen_reveal_v1"
        private const val KEY_DEFAULT = "default"
        private const val PREFIX = "chat:"

        @Volatile
        private var shared: LockScreenRevealStore? = null

        /**
         * Один екземпляр на процес: службі доставки він потрібен у момент
         * сповіщення, а два незалежні сховища розійшлись би між екраном
         * налаштувань і тим, що насправді малюється на замкненому екрані.
         */
        fun of(context: Context): LockScreenRevealStore =
            shared ?: synchronized(this) {
                shared ?: LockScreenRevealStore(context.applicationContext).also { shared = it }
            }
    }
}
