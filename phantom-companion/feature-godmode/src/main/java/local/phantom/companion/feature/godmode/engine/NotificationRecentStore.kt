package local.phantom.companion.feature.godmode.engine

import java.util.ArrayDeque

/**
 * Останні сповіщення для ока симбіота (`phone.notifications.recent`).
 *
 * Листенер складає сюди вже РЕДАГОВАНУ форму (той самий `NotificationClassifier`,
 * що й для стрічки) — заголовок і тіло без кодів, OTP і 2FA. Спам не кладемо.
 * Кільце на [CAP] записів: симбіот бачить недавнє, не всю історію.
 *
 * Процес-глобал, як `ScreenContextStore`/`NotificationImportantBus`: листенер
 * створює ОС, не наш DI. Чиста логіка — перевіряється звичайним тестом.
 */
object NotificationRecentStore {

    data class Recent(
        val packageName: String,
        val appLabel: String,
        val title: String,
        val text: String,
        val atMs: Long,
    )

    private val ring = ArrayDeque<Recent>()
    private val lock = Any()

    fun push(entry: Recent) {
        synchronized(lock) {
            ring.addFirst(entry)
            while (ring.size > CAP) ring.removeLast()
        }
    }

    /** Найновіші спершу, не більше [limit]. */
    fun recent(limit: Int = CAP): List<Recent> = synchronized(lock) {
        ring.take(limit.coerceIn(1, CAP))
    }

    internal fun resetForTest() = synchronized(lock) { ring.clear() }

    const val CAP: Int = 20
}
