package local.phantom.companion.core.voice.stream

/**
 * Договір «ніколи не мовчить довше півтори секунди», поки PHANTOM думає чи діє.
 *
 * Тиша рахується не від останнього слова моделі, а від миті, коли вже
 * сказане мало б дозвучати: синтез віддає речення за раз, і доки воно
 * звучить, говорити поверх нього не можна. Звідси оцінка тривалості за
 * символами — та сама ~94 мс на символ, що виміряна для Supertonic на A56.
 */
class QuietGuard(
    private val fillers: List<String>,
    /** Остання фраза, коли думати довелось довго: після неї — чесне мовчання. */
    private val longWait: String?,
    private val quietMs: Long = 1_200L,
    private val msPerChar: Long = 94L,
    private val maxFillers: Int = 3,
) {
    private var busyUntil = 0L
    private var used = 0
    private var gaveUp = false
    private var closed = false

    fun started(nowMs: Long) {
        busyUntil = nowMs
        used = 0
        gaveUp = false
        closed = false
    }

    /** Щось пішло в синтез: до [busyUntil] PHANTOM і так говорить. */
    fun spoke(text: String, nowMs: Long) {
        busyUntil = maxOf(busyUntil, nowMs) + text.length * msPerChar
    }

    /** Відповідь дописана — далі тишу заповнює сама відповідь або її кінець. */
    fun closed() {
        closed = true
    }

    /** Що сказати просто зараз, щоб не мовчати; null — говорити нічого не треба. */
    fun next(nowMs: Long): String? {
        if (closed || gaveUp || fillers.isEmpty()) return null
        if (nowMs - busyUntil < quietMs) return null
        val line = if (used < maxFillers) {
            fillers[used % fillers.size]
        } else {
            gaveUp = true
            longWait ?: return null
        }
        used++
        spoke(line, nowMs)
        return line
    }

    /** До якої миті звучатиме вже сказане — для тестів і для годинника ходу. */
    val speakingUntil: Long get() = busyUntil
}
