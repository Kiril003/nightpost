package local.phantom.companion.core.voice.turn

import android.os.SystemClock
import android.util.Log

/**
 * ЖИВА РОЗМОВА — секундомір однієї репліки.
 *
 * Бюджет затримки в проєктних документах був наполовину з оцінок, а
 * налаштовувати за оцінкою — це вигадувати. Цей клас ставить штампи в
 * єдиних місцях, які взагалі щось значать для вуха, і друкує один рядок
 * на репліку, який можна прочитати з `adb logcat -s PhantomTurn`.
 *
 * Процесо-глобальний і без стану між репліками: паралельних розмов не буває
 * — мікрофон один.
 */
object TurnClock {

    enum class Stage {
        /** VAD почув кінець акустики (провізорний кінець репліки). */
        SPEECH_END,

        /** Здогад: модель запущено наперед, ще не закривши транскрипт. */
        SPECULATED,

        /** Транскрипт остаточний. Звідси людина рахує паузу. */
        COMMIT,

        /** Запит пішов у модель. */
        DISPATCH,

        /** Перший токен від моделі. */
        FIRST_TOKEN,

        /** Перший шматок тексту віддано синтезу. */
        FIRST_CLAUSE,

        /** Перший семпл вийшов у динамік. */
        FIRST_AUDIO,

        /** Репліку договорено. */
        DONE,
    }

    private val stamps = LinkedHashMap<Stage, Long>()

    @Volatile
    private var origin: Long = 0L

    @Synchronized
    fun startTurn(source: String) {
        stamps.clear()
        origin = SystemClock.elapsedRealtime()
        Log.i(TAG, "turn start · $source")
    }

    @Synchronized
    fun mark(stage: Stage) {
        if (origin == 0L) startTurn("implicit")
        val at = SystemClock.elapsedRealtime() - origin
        // Перший штамп стадії — той, що описує затримку; повтори в межах
        // репліки (другий токен, другий сегмент) нічого не додають.
        if (stamps.putIfAbsent(stage, at) == null) {
            Log.i(TAG, "${stage.name.lowercase()} +${at}ms")
        }
        if (stage == Stage.DONE) report()
    }

    @Synchronized
    fun report() {
        if (stamps.isEmpty()) return
        val commit = stamps[Stage.COMMIT]
        val audio = stamps[Stage.FIRST_AUDIO]
        val line = buildString {
            append("turn:")
            stamps.forEach { (s, at) -> append(" ${s.name.lowercase()}=${at}") }
            if (commit != null && audio != null) append(" · ttfw_from_commit=${audio - commit}ms")
        }
        Log.i(TAG, line)
    }

    @Synchronized
    fun elapsedSince(stage: Stage): Long? =
        stamps[stage]?.let { SystemClock.elapsedRealtime() - origin - it }

    private const val TAG = "PhantomTurn"
}
