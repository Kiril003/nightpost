package local.phantom.companion.self

import java.time.Instant
import java.time.ZoneId

/**
 * Одна черга приходів PHANTOM і одне правило: прийти, лише коли слово варте
 * того, що людину відірвали. Кожен прихід несе «чому я прийшов»; скільки
 * приходів на день — вирішує сама людина тим, як на них відповідає.
 */
class Initiative(
    private val store: Store,
    private val commitments: CommitmentStore? = null,
    private val dueWord: (Long?, Long, ZoneId) -> String = { dueMs, nowMs, z ->
        CommitmentSpeech.whenWord(dueMs, nowMs, z, english = false)
    },
    private val onDueCommitment: ((Commitment, Visit) -> Unit)? = null,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    constructor(
        store: Store,
        zone: () -> ZoneId,
    ) : this(
        store = store,
        commitments = null,
        zone = zone,
    )

    enum class Kind {
        /** Урок чи повторення — можна відкласти, можна вимкнути. */
        LESSON,

        /** Нагадування, яке людина поставила сама: приходить у свій час завжди. */
        REMINDER,

        /** Обіцянка власника людині, час якої настав. */
        COMMITMENT,
    }

    data class Visit(
        val id: String,
        val kind: Kind,
        /** Один прихід на ключ: нове повторення C# замінює старе. */
        val key: String,
        val body: String,
        val why: String,
        val dueAtMs: Long,
    )

    data class State(
        val queue: List<Visit> = emptyList(),
        val budget: Int = START_BUDGET,
        val day: Long = -1,
        val usedToday: Int = 0,
        /** Приходи, що чекають відповіді. */
        val waiting: List<Came> = emptyList(),
        val muted: Set<Kind> = emptySet(),
        /** Ідентифікатори обіцянок, про які вже нагадали (один раз на обіцянку). */
        val announcedCommitments: Set<String> = emptySet(),
    )

    data class Came(val visit: Visit, val atMs: Long)

    interface Store {
        fun load(): State
        fun save(state: State)
    }

    sealed interface Decision {
        data class Come(val visit: Visit) : Decision
        /** Є що сказати, але не зараз; спитати знову о [atMs]. */
        data class Wait(val atMs: Long) : Decision
        data object Nothing : Decision
    }

    data class Moment(val nowMs: Long, val onTheRoad: Boolean)

    fun offer(visit: Visit) = edit { s ->
        if (visit.kind in s.muted) s
        else s.copy(queue = s.queue.filterNot { it.key == visit.key } + visit)
    }

    fun withdraw(key: String) = edit { s -> s.copy(queue = s.queue.filterNot { it.key == key }) }

    fun state(): State = store.load()

    /** Що робити зараз. Прийшов — одразу вважається, що чекає відповіді. */
    fun decide(m: Moment): Decision {
        var s = syncCommitments(rollover(store.load(), m.nowMs), m.nowMs)
        val due = s.queue.filter { it.dueAtMs <= m.nowMs && it.kind !in s.muted }.sortedBy { it.dueAtMs }
        val futureQueue = s.queue.filter { it.dueAtMs > m.nowMs }.minOfOrNull { it.dueAtMs }
        val futureCommitment = commitments?.listOpen()
            ?.mapNotNull { c -> c.dueMs?.takeIf { it > m.nowMs && c.id !in s.announcedCommitments } }
            ?.minOrNull()
        val later = listOfNotNull(futureQueue, futureCommitment).minOrNull()
        if (due.isEmpty()) {
            store.save(s)
            return later?.let { Decision.Wait(it) } ?: Decision.Nothing
        }
        due.firstOrNull { it.kind == Kind.REMINDER || it.kind == Kind.COMMITMENT }?.let { r ->
            val commitmentId = r.key.removePrefix(COMMITMENT_KEY_PREFIX).takeIf { r.kind == Kind.COMMITMENT }
            val liveCommitment = commitmentId?.let { commitments?.get(it) }
            if (r.kind == Kind.COMMITMENT && commitments != null && liveCommitment?.state != CommitmentState.OPEN) {
                store.save(s.copy(queue = s.queue - r))
                return later?.let { Decision.Wait(it) } ?: Decision.Nothing
            }
            val nextAnnounced = if (commitmentId != null) s.announcedCommitments + commitmentId else s.announcedCommitments
            store.save(s.copy(queue = s.queue - r, announcedCommitments = nextAnnounced))
            if (liveCommitment != null) {
                onDueCommitment?.invoke(liveCommitment, r)
            }
            return Decision.Come(r)
        }
        val quietUntil = quietUntil(m.nowMs)
        val hold = when {
            quietUntil != null -> quietUntil
            m.onTheRoad -> m.nowMs + ROAD_RECHECK_MS
            s.usedToday >= s.budget -> nextMorning(m.nowMs)
            else -> null
        }
        if (hold != null) {
            store.save(s)
            return Decision.Wait(hold)
        }
        val v = due.first()
        s = s.copy(queue = s.queue - v, usedToday = s.usedToday + 1, waiting = s.waiting + Came(v, m.nowMs))
        store.save(s)
        return Decision.Come(v)
    }

    /** Людина відгукнулась (написала PHANTOM або відкрила прихід) — він приходив не дарма. */
    fun answered() = edit { s ->
        if (s.waiting.isEmpty()) s
        else s.copy(budget = (s.budget + 1).coerceAtMost(MAX_BUDGET), waiting = emptyList())
    }

    /** Змахнула без слова. */
    fun ignored(id: String) = edit { s ->
        if (s.waiting.none { it.visit.id == id }) s
        else s.copy(budget = (s.budget - 1).coerceAtLeast(MIN_BUDGET), waiting = s.waiting.filterNot { it.visit.id == id })
    }

    /** «Не зараз» — той самий прихід через кілька годин; бюджет не карається і не росте. */
    fun notNow(visit: Visit, nowMs: Long) = edit { s ->
        s.copy(
            waiting = s.waiting.filterNot { it.visit.id == visit.id },
            queue = s.queue.filterNot { it.key == visit.key } + visit.copy(dueAtMs = nowMs + NOT_NOW_MS),
        )
    }

    /** «Так не роби» — цей рід приходів вимкнено, черга від нього очищена. */
    fun dontDoThis(kind: Kind) = edit { s ->
        if (kind == Kind.REMINDER || kind == Kind.COMMITMENT) s
        else s.copy(muted = s.muted + kind, queue = s.queue.filterNot { it.kind == kind }, waiting = emptyList())
    }

    private fun syncCommitments(s: State, nowMs: Long): State {
        val cStore = commitments ?: return s
        val z = zone()
        var nextQueue = s.queue.filterNot { v ->
            if (v.kind != Kind.COMMITMENT) {
                false
            } else {
                val cId = v.key.removePrefix(COMMITMENT_KEY_PREFIX)
                cStore.get(cId)?.state != CommitmentState.OPEN
            }
        }
        val dueCommitments = cStore.dueOpen(nowMs).filter { it.id !in s.announcedCommitments }
        for (c in dueCommitments) {
            val key = COMMITMENT_KEY_PREFIX + c.id
            if (nextQueue.any { it.key == key }) continue
            val word = dueWord(c.dueMs, nowMs, z)
            val line = CommitmentSpeech.dueLine(c.personName, c.what, word)
            val visit = Visit(
                id = "cmt-" + c.id,
                kind = Kind.COMMITMENT,
                key = key,
                body = line,
                why = "",
                dueAtMs = c.dueMs ?: nowMs,
            )
            nextQueue = nextQueue + visit
        }
        return if (nextQueue == s.queue) s else s.copy(queue = nextQueue)
    }

    fun unmute(kind: Kind) = edit { s -> s.copy(muted = s.muted - kind) }

    fun lastWaiting(): Visit? = store.load().waiting.maxByOrNull { it.atMs }?.visit

    private fun rollover(s: State, nowMs: Long): State {
        val today = day(nowMs)
        if (s.day == today) return s
        // Прихід, на який не відповіли до кінця дня, — змахнутий.
        val stale = s.waiting.count { nowMs - it.atMs >= IGNORED_AFTER_MS }
        return s.copy(
            day = today,
            usedToday = 0,
            budget = (s.budget - stale).coerceAtLeast(MIN_BUDGET),
            waiting = s.waiting.filter { nowMs - it.atMs < IGNORED_AFTER_MS },
        )
    }

    private fun day(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(zone()).toLocalDate().toEpochDay()

    private fun quietUntil(ms: Long): Long? {
        val t = Instant.ofEpochMilli(ms).atZone(zone())
        return when {
            t.hour >= NIGHT_FROM -> t.toLocalDate().plusDays(1).atTime(MORNING, 0).atZone(zone()).toInstant().toEpochMilli()
            t.hour < MORNING -> t.toLocalDate().atTime(MORNING, 0).atZone(zone()).toInstant().toEpochMilli()
            else -> null
        }
    }

    private fun nextMorning(ms: Long): Long {
        val t = Instant.ofEpochMilli(ms).atZone(zone())
        return t.toLocalDate().plusDays(1).atTime(MORNING, 0).atZone(zone()).toInstant().toEpochMilli()
    }

    private inline fun edit(f: (State) -> State) {
        store.save(f(store.load()))
    }

    companion object {
        const val START_BUDGET = 2
        const val MIN_BUDGET = 1
        const val MAX_BUDGET = 5
        const val NIGHT_FROM = 22
        const val MORNING = 8
        const val NOT_NOW_MS = 3 * 3_600_000L
        const val ROAD_RECHECK_MS = 20 * 60_000L
        const val IGNORED_AFTER_MS = 24 * 3_600_000L
        const val COMMITMENT_KEY_PREFIX = "commitment."
    }
}
