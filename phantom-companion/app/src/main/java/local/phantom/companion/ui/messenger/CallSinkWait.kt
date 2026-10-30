package local.phantom.companion.ui.messenger

import kotlinx.coroutines.delay

/**
 * Чекає, поки бандл оголосить приймача кадрів дзвінка, і каже про це вузлу.
 * Повертає false, якщо за [giveUpMs] приймача так і не зʼявилось.
 *
 * Перші десять секунд питає часто (холодний старт із повноекранного сповіщення), далі раз на секунду.
 */
internal suspend fun awaitCallSink(
    declared: suspend () -> Boolean,
    onReady: () -> Unit,
    giveUpMs: Long = 120_000L,
): Boolean {
    var waited = 0L
    while (true) {
        if (declared()) {
            onReady()
            return true
        }
        if (waited > giveUpMs) return false
        val step = if (waited < 10_000L) 200L else 1_000L
        delay(step)
        waited += step
    }
}
