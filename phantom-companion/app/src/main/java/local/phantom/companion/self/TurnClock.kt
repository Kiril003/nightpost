package local.phantom.companion.self

/**
 * Годинник ходу PHANTOM на телефоні (L0 плану docs/symbiote/LIVE.md, розділ 3.10).
 *
 * Одне чесне число на хід: від миті, коли питання дійшло до мосту
 * ([Mark.BRIDGE_IN]), до рішення, хто відповідає ([Mark.DECIDED]), до першого,
 * що людина побачила на екрані ([Mark.FIRST_VISIBLE]), до першого слова моделі
 * ([Mark.FIRST_TOKEN]) і до кінця ходу ([Mark.FINAL]). Розбиття — за джерелом
 * відповіді: рефлекс, памʼять, ПК, хмара, офлайн.
 *
 * Час — лише монотонний годинник цього апарата ([System.nanoTime]). Годинник ПК
 * тут не віднімається ніколи: ПК міряє своє своїм (core/turn_clock.py), а
 * перехресні різниці потребують рукостискання зсуву.
 *
 * Процентиль — найближчий ранг, те саме правило, що й на ПК, щоб «p50 телефона»
 * і «p50 ПК» означали одне й те саме.
 *
 * Перша мітка виграє: перший токен один, скільки б разів про нього не сказали.
 * Мітка для незнайомого ходу чи раніша за [Mark.BRIDGE_IN] відкидається — це
 * помилка проводки, а не швидкість.
 */
class TurnClock(
    private val nowNanos: () -> Long = System::nanoTime,
    private val keep: Int = 200,
) {

    enum class Mark(val wire: String) {
        BRIDGE_IN("bridge_in"),
        DECIDED("decided"),
        FIRST_VISIBLE("first_visible"),
        FIRST_TOKEN("first_token"),
        FINAL("final"),
    }

    enum class Source(val wire: String) {
        REFLEX("reflex"),
        MEMORY("memory"),
        PC("pc"),
        CLOUD("cloud"),
        OFFLINE("offline"),
        UNKNOWN("unknown"),
    }

    data class Turn(
        val id: String,
        val label: String,
        val marksNs: Map<Mark, Long>,
        val source: Source,
        /** Звідки відомо джерело: `decided` — сказав сам маршрут; інакше — хто й як спостеріг. */
        val basis: String,
        val ok: Boolean?,
        val reason: String,
    ) {
        fun spanMs(from: Mark, to: Mark): Long? {
            val a = marksNs[from] ?: return null
            val b = marksNs[to] ?: return null
            return (b - a) / 1_000_000L
        }
    }

    data class SpanStats(val n: Int, val p50Ms: Long?, val p95Ms: Long?)

    /** Лише закінчені ходи; кожен відрізок — від [Mark.BRIDGE_IN] до мітки. */
    data class Stats(val turns: Int, val failed: Int, val spans: Map<Mark, SpanStats>)

    data class Report(val bySource: Map<Source, Stats>, val all: Stats) {
        fun toJson(): String = buildString {
            append("{\"all\":").append(statsJson(all)).append(",\"by_source\":{")
            bySource.entries.joinTo(this, ",") { (s, st) -> "\"${s.wire}\":${statsJson(st)}" }
            append("}}")
        }

        private fun statsJson(s: Stats): String = buildString {
            append("{\"turns\":").append(s.turns).append(",\"failed\":").append(s.failed).append(",\"spans\":{")
            s.spans.entries.joinTo(this, ",") { (m, sp) ->
                "\"${m.wire}\":{\"n\":${sp.n},\"p50_ms\":${sp.p50Ms ?: "null"},\"p95_ms\":${sp.p95Ms ?: "null"}}"
            }
            append("}}")
        }
    }

    private class Live(
        val id: String,
        val label: String,
        val marks: MutableMap<Mark, Long>,
        var source: Source = Source.UNKNOWN,
        var basis: String = "",
        var ok: Boolean? = null,
        var reason: String = "",
    )

    private val lock = Any()
    private val turns = LinkedHashMap<String, Live>()

    fun begin(id: String, label: String = "", atNs: Long = nowNanos()) {
        synchronized(lock) {
            turns.remove(id)
            turns[id] = Live(id, label, linkedMapOf(Mark.BRIDGE_IN to atNs))
            while (turns.size > keep) turns.remove(turns.keys.first())
        }
    }

    fun mark(id: String, mark: Mark, atNs: Long = nowNanos()): Boolean = synchronized(lock) {
        val t = turns[id] ?: return false
        if (mark == Mark.BRIDGE_IN || mark in t.marks) return false
        if (atNs < t.marks.getValue(Mark.BRIDGE_IN)) return false
        t.marks[mark] = atNs
        true
    }

    /** Маршрут вирішив, хто відповідає: мітка [Mark.DECIDED] і джерело — один раз. */
    fun decide(id: String, source: Source, atNs: Long = nowNanos()): Boolean = synchronized(lock) {
        val t = turns[id] ?: return false
        if (!mark(id, Mark.DECIDED, atNs)) return false
        t.source = source
        t.basis = "decided"
        true
    }

    /** Джерело, назване спостерігачем, — без удаваного моменту рішення. */
    fun attribute(id: String, source: Source, basis: String) {
        synchronized(lock) {
            val t = turns[id] ?: return
            if (t.basis == "decided") return
            t.source = source
            t.basis = basis
        }
    }

    fun finish(id: String, ok: Boolean, reason: String = "", atNs: Long = nowNanos()) {
        synchronized(lock) {
            val t = turns[id] ?: return
            mark(id, Mark.FINAL, atNs)
            t.ok = ok
            t.reason = reason
        }
    }

    fun turn(id: String): Turn? = synchronized(lock) { turns[id]?.let(::frozen) }

    fun turns(): List<Turn> = synchronized(lock) { turns.values.map(::frozen) }

    fun clear() {
        synchronized(lock) { turns.clear() }
    }

    fun report(): Report {
        val done = turns().filter { Mark.FINAL in it.marksNs }
        return Report(
            bySource = done.groupBy { it.source }.mapValues { (_, ts) -> stats(ts) },
            all = stats(done),
        )
    }

    /** Рядок для logcat (тег [TAG]): мс від [Mark.BRIDGE_IN] до кожної мітки, «-» — мітки немає. */
    fun line(id: String): String {
        val t = turn(id) ?: return "turn=$id unknown"
        val spans = SPAN_MARKS.joinToString(" ") { m -> "${m.wire}=${t.spanMs(Mark.BRIDGE_IN, m) ?: "-"}" }
        return "turn=${t.id} label=${t.label} source=${t.source.wire} basis=${t.basis.ifEmpty { "-" }} " +
            "ok=${t.ok ?: "-"} reason=${t.reason.replace(' ', '_')} $spans"
    }

    private fun frozen(t: Live) = Turn(t.id, t.label, LinkedHashMap(t.marks), t.source, t.basis, t.ok, t.reason)

    private fun stats(ts: List<Turn>): Stats = Stats(
        turns = ts.size,
        failed = ts.count { it.ok == false },
        spans = SPAN_MARKS.associateWith { m ->
            val xs = ts.mapNotNull { it.spanMs(Mark.BRIDGE_IN, m) }
            SpanStats(n = xs.size, p50Ms = percentile(xs, 50), p95Ms = percentile(xs, 95))
        },
    )

    companion object {
        const val TAG = "PhantomTurn"

        private val SPAN_MARKS = listOf(Mark.DECIDED, Mark.FIRST_VISIBLE, Mark.FIRST_TOKEN, Mark.FINAL)

        /** Один годинник на процес: міст, маршрути і проба пишуть в один журнал ходів. */
        val process: TurnClock by lazy { TurnClock() }

        /** Найближчий ранг: найменше значення, не менше за [p] % вибірки. */
        fun percentile(values: List<Long>, p: Int): Long? {
            if (values.isEmpty()) return null
            val xs = values.sorted()
            if (p <= 0) return xs.first()
            val rank = Math.ceil(p / 100.0 * xs.size).toInt().coerceIn(1, xs.size)
            return xs[rank - 1]
        }
    }
}
