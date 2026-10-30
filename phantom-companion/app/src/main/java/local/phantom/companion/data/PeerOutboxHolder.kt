package local.phantom.companion.data

import android.util.Log
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.net.peer.PeerMessage

/**
 * Розмова та єдині двері до неї: ніщо не потрапляє в потік, не лягши на диск.
 *
 * Двері беруть перетворення, а не готовий список. Читати-порахувати-записати
 * трьома кроками означало, що стирання й лист, який саме відлітає, рахують від
 * одного стану, а перемагає той, хто записав другим. Стерта стрічка тоді
 * поверталась — і не лише на екран, бо [save] клав той самий програш на диск.
 *
 * Перетворення мусить лишатись чистим — без годинника й без запису: воно
 * повторюється на кожній програній гонці.
 *
 * Атомарної підміни стану замало. [save] — це шифрування й запис на диск,
 * тобто мілісекунди, і поза замком вони шикувались як завгодно: той, хто
 * програв гонку перетворення, міг виграти гонку диска, і лист, видимий на
 * екрані, після перезапуску зникав. Тому знімок кладеться в [pending] ПІД тим
 * самим замком, що й підміна, — і диск бачить знімки в тому ж порядку, що й
 * екран. Пише завжди один: решта віддає йому найсвіжіше й одразу йде далі,
 * тож головна гілка ніколи не стоїть під чужим шифруванням.
 *
 * Сам запис іде на [writer], а не на потоці того, хто публікує: перший
 * публікатор раніше платив диском сам, і ним часто був міст, поки JS чекав.
 *
 * Живе окремо від [PeerMesh] і без жодного андроїда, щоб ті гонки можна було
 * відтворити на справжньому коді, а не на його переказі в тесті.
 */
class PeerOutboxHolder(
    initial: List<PeerMessage> = emptyList(),
    private val writer: Executor = WRITER,
    private val save: (List<PeerMessage>) -> Unit = {},
) {

    private val _messages = MutableStateFlow(initial)
    val messages: StateFlow<List<PeerMessage>> = _messages.asStateFlow()

    private val lock = Any()
    private var pending: List<PeerMessage>? = null
    private var writing = false
    private var onDisk: List<PeerMessage> = initial

    fun publish(transform: (List<PeerMessage>) -> List<PeerMessage>) {
        while (true) {
            val current = _messages.value
            val candidate = transform(current)
            when (commit(current, candidate)) {
                Commit.RETRY -> Unit
                Commit.HANDED_OFF -> return
                Commit.MINE -> {
                    writer.execute(::drain)
                    return
                }
            }
        }
    }

    private enum class Commit { RETRY, HANDED_OFF, MINE }

    private fun commit(current: List<PeerMessage>, candidate: List<PeerMessage>): Commit =
        synchronized(lock) {
            if (!_messages.compareAndSet(current, candidate)) return@synchronized Commit.RETRY
            pending = candidate
            if (writing) return@synchronized Commit.HANDED_OFF
            writing = true
            Commit.MINE
        }

    private fun drain() {
        while (true) {
            val next = synchronized(lock) {
                val ready = pending
                pending = null
                // Прапорець знімається в тій самій критичній секції, де видно
                // порожній [pending]: інакше знімок, покладений між ними,
                // не дістався б диска взагалі.
                if (ready == null) {
                    writing = false
                    (lock as Object).notifyAll()
                }
                ready
            } ?: return
            // Виняток тут лишив би writing = true назавжди, і жоден наступний
            // знімок уже не дійшов би диска.
            val saved = runCatching { save(next) }
                .onFailure { Log.e(TAG, "запис журналу не вдався", it) }
                .isSuccess
            synchronized(lock) {
                if (saved) onDisk = next
                (lock as Object).notifyAll()
            }
        }
    }

    /** Чекає, доки диск наздожене екран. Для тестів і для виходу, не для головної гілки. */
    fun flush(timeoutMs: Long = 10_000L): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (writing) {
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                (lock as Object).wait(left)
            }
        }
        return true
    }

    /** Лист уже на диску, а не лише на екрані. Блокує до [timeoutMs]: не з головної гілки. */
    fun durable(id: String, timeoutMs: Long = 10_000L): Boolean {
        val until = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (true) {
                if (onDisk.any { it.id == id }) return true
                if (!writing) return false
                val left = until - System.currentTimeMillis()
                if (left <= 0) return false
                (lock as Object).wait(left)
            }
        }
    }

    private companion object {
        const val TAG = "PeerOutboxHolder"

        val WRITER: Executor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "phantom-msg-writer").apply { isDaemon = true }
        }
    }
}
