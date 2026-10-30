package local.phantom.companion.core.net.peer

/**
 * Зникаючі повідомлення в розмові вдвох: Вимк. / 1 день / 1 тиждень / 1 місяць.
 *
 * **Дріт.** Налаштування — кадр `PH-TTL:<секунди>;<коли встановлено, мс>`.
 * Його може надіслати будь-хто з двох, і діє НАЙПІЗНІШИЙ із кадрів обох — так
 * само, як у Signal: це налаштування розмови, а не однієї людини. Кадр лягає
 * в стрічку обох службовим рядком («повідомлення зникають через 1 день»).
 *
 * **Строк листа.** Нового поля в листі немає: строк — той, що діяв у мить
 * [PeerMessage.sentAtMs] (останній кадр, встановлений не пізніше), тож обидва
 * боки рахують його однаково з того самого журналу.
 *  * свій лист зникає через строк від надсилання;
 *  * чужий — через строк від ПРОЧИТАННЯ ([ReadMark]: коли межа прочитаного
 *    вперше перейшла цей лист); непрочитаний не зникає.
 * Кожен бік стирає свою копію сам — «на обох боках» означає два незалежні
 * годинники, а не команду «зітри» через мережу, яку можна загубити.
 *
 * **Чесно.** Зникнення не захищає від знімка екрана чи фото іншим телефоном —
 * так і сказано людині там, де вона вмикає.
 *
 * Групи поки без зникнення: налаштування в групі потребує згоди складу.
 */
object PeerDisappear {

    const val TAG = "PH-TTL:"

    const val DAY_S = 86_400L
    const val WEEK_S = 7 * DAY_S
    const val MONTH_S = 30 * DAY_S
    val CHOICES_S = listOf(0L, DAY_S, WEEK_S, MONTH_S)

    data class Setting(val seconds: Long, val setAtMs: Long, val byId: String)

    /** Межа прочитаного зрушила до [uptoMs] у мить [atMs] (годинник читача). */
    data class ReadMark(val uptoMs: Long, val atMs: Long)

    fun encode(seconds: Long, setAtMs: Long): String? =
        if (seconds in CHOICES_S && setAtMs > 0) "$TAG$seconds;$setAtMs" else null

    fun isTimer(body: String): Boolean = body.startsWith(TAG)

    fun decode(body: String): Pair<Long, Long>? {
        if (!isTimer(body)) return null
        val f = body.removePrefix(TAG).split(';')
        if (f.size != 2) return null
        val seconds = f[0].toLongOrNull()?.takeIf { it in CHOICES_S } ?: return null
        val at = f[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return seconds to at
    }

    private fun between(m: PeerMessage, me: String, peerId: String): Boolean =
        (m.fromId == me && m.toId == peerId) || (m.fromId == peerId && m.toId == me)

    /** Усі налаштування розмови з [peerId], старші перші. */
    fun settings(log: List<PeerMessage>, me: String, peerId: String): List<Setting> =
        log.asSequence()
            .filter { between(it, me, peerId) }
            .mapNotNull { m -> decode(m.body)?.let { (s, at) -> Setting(s, at, m.fromId) } }
            .sortedBy { it.setAtMs }
            .toList()

    fun current(log: List<PeerMessage>, me: String, peerId: String): Setting? = settings(log, me, peerId).lastOrNull()

    /** Строк (секунди), що діяв у мить [atMs]. */
    fun ttlAt(settings: List<Setting>, atMs: Long): Long = settings.lastOrNull { it.setAtMs <= atMs }?.seconds ?: 0L

    /** Мить, коли лист зникне; null — не зникне (строку немає або чужий ще не прочитано). */
    fun expiresAt(m: PeerMessage, me: String, settings: List<Setting>, reads: List<ReadMark>): Long? {
        val ttl = ttlAt(settings, m.sentAtMs)
        if (ttl <= 0) return null
        val start = if (m.fromId == me) m.sentAtMs else reads.firstOrNull { it.uptoMs >= m.sentAtMs }?.atMs ?: return null
        return start + ttl * 1000
    }

    /** Що зникає: слова людини й шматки вкладень. Службові кадри (прочитання, сам строк) лишаються. */
    private fun perishable(m: PeerMessage): Boolean = PeerOutbox.isSpoken(m) || PeerFile.isChunk(m.body)

    /** Рядки розмови з [peerId], чий строк минув на [nowMs]. */
    fun expired(log: List<PeerMessage>, me: String, peerId: String, reads: List<ReadMark>, nowMs: Long): List<PeerMessage> {
        val settings = settings(log, me, peerId)
        if (settings.none { it.seconds > 0 }) return emptyList()
        return log.filter { m ->
            between(m, me, peerId) && perishable(m) && PeerGroup.decode(m.body) == null &&
                (expiresAt(m, me, settings, reads)?.let { it <= nowMs } ?: false)
        }
    }

    /** Додати мітку прочитання, лише якщо межа справді зрушила вперед. */
    fun noteRead(reads: List<ReadMark>, uptoMs: Long, atMs: Long, keep: Int = MAX_MARKS): List<ReadMark> {
        if (uptoMs <= (reads.lastOrNull()?.uptoMs ?: 0L)) return reads
        return (reads + ReadMark(uptoMs, atMs)).takeLast(keep)
    }

    const val MAX_MARKS = 512
}
