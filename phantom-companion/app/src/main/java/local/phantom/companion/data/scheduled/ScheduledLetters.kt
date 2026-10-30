package local.phantom.companion.data.scheduled

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * Відкладені листи телефона. Лежать у зашифрованому файлі, а не у вкладці
 * вебу: сторінка в WebView живе недовго, а лист «на завтра» мусить дожити.
 * Надсилає будильник ([local.phantom.companion.service.ScheduledLetterAlarms]),
 * а після перезавантаження будильники ставляться наново з цього списку.
 */
class ScheduledLetters(
    private val read: () -> String?,
    private val write: (String) -> Unit,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    @Serializable
    data class Letter(
        val id: String,
        val conversationId: String,
        val text: String,
        val dueAtMs: Long,
        val createdAtMs: Long,
        val quiet: Boolean = false,
        /** Чому не пішов; порожньо — ще чекає свого часу. */
        val failure: String = "",
        /** [REPEATS]: після відправки лист лишається з наступним часом. Порожньо — один раз. */
        val repeat: String = "",
        /** Без часу: іде, щойно людина озветься (будь-який її кадр), а не будильником. */
        val whenOnline: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val ser = ListSerializer(Letter.serializer())
    private val lock = Any()

    fun all(): List<Letter> = synchronized(lock) {
        read()?.let { runCatching { json.decodeFromString(ser, it) }.getOrNull() }.orEmpty().sortedBy { it.dueAtMs }
    }

    private fun save(list: List<Letter>) = write(json.encodeToString(ser, list))

    /** Null — нема тексту, розмови або час уже минув (крім «коли буде в мережі»: там часу нема). */
    fun add(
        conversationId: String,
        text: String,
        dueAtMs: Long,
        quiet: Boolean,
        nowMs: Long,
        repeat: String = "",
        whenOnline: Boolean = false,
    ): Letter? {
        val body = text.trim()
        if (body.isEmpty() || conversationId.isBlank() || (!whenOnline && dueAtMs <= nowMs)) return null
        val letter = Letter(
            id = UUID.randomUUID().toString(),
            conversationId = conversationId,
            text = body,
            dueAtMs = if (whenOnline) 0L else dueAtMs,
            createdAtMs = nowMs,
            quiet = quiet,
            repeat = if (whenOnline) "" else repeat.takeIf { it in REPEATS }.orEmpty(),
            whenOnline = whenOnline,
        )
        synchronized(lock) { save(all() + letter) }
        return letter
    }

    /**
     * Змінити текст, час, повтор або «коли в мережі». Null у полі — лишити як є.
     * Новий час знімає стару відмову: людина переносить лист саме тому, що він не пішов.
     */
    fun update(
        id: String,
        nowMs: Long,
        text: String? = null,
        dueAtMs: Long? = null,
        repeat: String? = null,
        whenOnline: Boolean? = null,
    ): Edit = synchronized(lock) {
        val list = all()
        val old = list.firstOrNull { it.id == id } ?: return Edit.Gone
        val body = text?.trim() ?: old.text
        if (body.isEmpty()) return Edit.Empty
        val online = whenOnline ?: if (dueAtMs != null) false else old.whenOnline
        val due = if (online) 0L else dueAtMs ?: old.dueAtMs
        if (!online && due <= nowMs) return Edit.Past
        val rep = if (online) "" else (repeat ?: old.repeat).takeIf { it in REPEATS }.orEmpty()
        val retimed = due != old.dueAtMs || online != old.whenOnline
        val next = old.copy(
            text = body,
            dueAtMs = due,
            repeat = rep,
            whenOnline = online,
            failure = if (retimed) "" else old.failure,
        )
        save(list.map { if (it.id == id) next else it })
        Edit.Done(next)
    }

    sealed interface Edit {
        data class Done(val letter: Letter) : Edit
        data object Gone : Edit
        data object Empty : Edit
        data object Past : Edit
    }

    fun remove(id: String): Letter? = synchronized(lock) {
        val list = all()
        val gone = list.firstOrNull { it.id == id } ?: return null
        save(list - gone)
        gone
    }

    fun get(id: String): Letter? = all().firstOrNull { it.id == id }

    /** Ті, чий час настав і які ще не падали. */
    fun due(nowMs: Long): List<Letter> = all().filter { !it.whenOnline && it.failure.isEmpty() && it.dueAtMs <= nowMs }

    /** Найраніший, що ще чекає (і прострочений теж, якщо телефон був вимкнений), — на нього ставиться будильник. */
    fun next(): Letter? = all().firstOrNull { !it.whenOnline && it.failure.isEmpty() }

    private fun markFailed(id: String, why: String) = synchronized(lock) {
        save(all().map { if (it.id == id) it.copy(failure = why.ifBlank { "?" }) else it })
    }

    /** Будильник і «Надіслати зараз» можуть зійтися — лист не має піти двічі. */
    private val sending = Mutex()

    /**
     * Надіслати один. [send] повертає null, коли лист пішов, або причину
     * відмови: відправлений зникає зі списку, відмовлений лишається з причиною.
     * Null у відповіді — пішов; «gone» — його вже немає (скасовано чи відправлено).
     */
    suspend fun deliver(id: String, send: suspend (Letter) -> String?): String? =
        sending.withLock { deliverLocked(id, send) }

    private suspend fun deliverLocked(id: String, send: suspend (Letter) -> String?): String? {
        val letter = get(id) ?: return GONE
        val why = runCatching { send(letter) }.getOrElse { it.message ?: it.javaClass.simpleName }
        when {
            why != null -> markFailed(letter.id, why)
            letter.repeat.isNotEmpty() && !letter.whenOnline -> synchronized(lock) {
                val again = letter.copy(dueAtMs = nextDue(letter.dueAtMs, letter.repeat, maxOf(letter.dueAtMs, clock())))
                save(all().map { if (it.id == letter.id) again else it })
            }
            else -> remove(letter.id)
        }
        return why
    }

    /** Наступна мить повтору, суворо пізніше за [afterMs], тим самим місцевим часом доби. */
    fun nextDue(dueAtMs: Long, repeat: String, afterMs: Long): Long {
        var at = Instant.ofEpochMilli(dueAtMs).atZone(zone())
        do {
            at = when (repeat) {
                DAILY -> at.plusDays(1)
                WEEKLY -> at.plusWeeks(1)
                MONTHLY -> at.plusMonths(1)
                WEEKDAYS -> {
                    var d = at.plusDays(1)
                    while (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) d = d.plusDays(1)
                    d
                }
                else -> return dueAtMs
            }
        } while (at.toInstant().toEpochMilli() <= afterMs)
        return at.toInstant().toEpochMilli()
    }

    /** Людина озвалась: усі її листи «коли буде в мережі» йдуть зараз. Повертає, скільки пішло. */
    suspend fun fireOnline(conversationId: String, send: suspend (Letter) -> String?): Int =
        sending.withLock {
            all().filter { it.whenOnline && it.failure.isEmpty() && it.conversationId == conversationId }
                .count { deliverLocked(it.id, send) == null }
        }

    /** Чи чекає хтось на цю людину — щоб не смикати диск на кожен її кадр. */
    fun waitsForOnline(conversationId: String): Boolean =
        all().any { it.whenOnline && it.failure.isEmpty() && it.conversationId == conversationId }

    /** Усе, чий час настав. Повертає, скільки пішло. */
    suspend fun fireDue(nowMs: Long, send: suspend (Letter) -> String?): Int =
        sending.withLock { due(nowMs).count { deliverLocked(it.id, send) == null } }

    companion object {
        const val GONE = "gone"
        const val DAILY = "daily"
        const val WEEKLY = "weekly"
        const val MONTHLY = "monthly"
        const val WEEKDAYS = "weekdays"
        val REPEATS = setOf(DAILY, WEEKLY, MONTHLY, WEEKDAYS)
    }
}
