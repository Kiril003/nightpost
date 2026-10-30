package local.phantom.companion.core.net.peer

import kotlin.math.ln
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Відправник: кому, з якою точністю й коли — чиста машина станів без радіо.
 *
 * Рух НЕ будить дріт. Кадри йдуть лише в «слоти»: випадковий розклад
 * (зсунута експонента), спільний для всіх дозволів і незалежний від того,
 * де людина. У кожен слот кожен дозвіл отримує рівно один кадр тієї самої
 * довжини — новий район або той самий. Хто бачить лише час і розмір кадрів
 * (сховок, сусід у Wi-Fi), бачить процес без памʼяті, з якого мить переходу
 * межі не читається: вона не змінює ні частоти, ні розміру, ні кількості.
 *
 * Поза розкладом ідуть лише дії людини, а не рух: перша видача дозволу,
 * зміна точності, відповідь на «де ти?», «стерти».
 *
 * Точна координата живе тут лише в [State.fix], у памʼяті, і в кадр району не
 * потрапляє ніколи: кадр району складається з [PeerWhereGrid.Cell], а не з неї.
 */
object PeerWhereBeacon {

    fun ttlMs(p: PeerWhere.Precision): Long = when (p) {
        PeerWhere.Precision.DISTRICT -> 30 * 60_000L
        PeerWhere.Precision.QUARTER -> 20 * 60_000L
        PeerWhere.Precision.EXACT -> 15 * 60_000L
    }

    /**
     * Середній проміжок між слотами. Ціна — один вихід радіо на слот для всіх
     * дозволів разом (≈0,5–0,8 мА·год на LTE з хвостом стану, оцінка): район
     * 7,5 виходу на годину ≈ 90–145 мА·год на добу, 2–3 % батареї A52.
     */
    fun slotMeanMs(p: PeerWhere.Precision): Long = when (p) {
        PeerWhere.Precision.DISTRICT -> 8 * 60_000L
        PeerWhere.Precision.QUARTER -> 4 * 60_000L
        PeerWhere.Precision.EXACT -> 90_000L
    }

    fun slotFloorMs(p: PeerWhere.Precision): Long = slotMeanMs(p) / 4

    /** Стеля нижча за половину строку: друг не бачить, як точка гасне між слотами. */
    fun slotCapMs(p: PeerWhere.Precision): Long = minOf(2 * slotMeanMs(p), ttlMs(p) / 2)

    const val ASK_GAP_MS = 2 * 60_000L
    const val FIX_MAX_AGE_MS = 5 * 60_000L

    /** Довше за це слот мовчить: повторювати годинний вимір — брехня про «зараз». */
    const val HOLD_MAX_AGE_MS = 2 * 60 * 60_000L
    const val FIX_LEAD_MS = 60_000L
    const val HYSTERESIS = 0.2

    enum class Why { FIRST, FORCED, ASK }

    data class Fix(
        val lat: Double,
        val lon: Double,
        val accuracyM: Int?,
        val atMs: Long,
        val state: PeerWhere.State = PeerWhere.QUIET,
    )

    data class Lane(
        val peerId: String,
        val precision: PeerWhere.Precision,
        val sentCell: PeerWhereGrid.Cell? = null,
        val sentLat: Double = Double.NaN,
        val sentLon: Double = Double.NaN,
        val sentAccuracyM: Int? = null,
        val sentFixAtMs: Long = 0L,
        val sentState: PeerWhere.State = PeerWhere.QUIET,
        val lastSentAtMs: Long = 0L,
        val sentExpiresAtMs: Long = 0L,
        val dueAtMs: Long = 0L,
        val why: Why? = null,
        val answeredAskAtMs: Long = 0L,
        /** Нуль — до вимкнення. Інакше жоден кадр не живе довше, а о цій миті дозвіл сам стирається. */
        val untilMs: Long = 0L,
        /**
         * Привид від цієї людини: до цієї миті вона отримує ті самі слоти з тим самим
         * останнім місцем і його старою миттю — як від телефона, що загубив супутники.
         */
        val hiddenUntilMs: Long = 0L,
    )

    data class State(
        val on: Boolean = true,
        val lanes: Map<String, Lane> = emptyMap(),
        val fix: Fix? = null,
        val seq: Int = 0,
        val slotAtMs: Long = 0L,
        /** Привид від усіх одразу, зокрема від тих, кому дозвіл дадуть уже під час нього. */
        val hiddenAllUntilMs: Long = 0L,
        /** Коли кому востаннє слали «стерти»: вимір, старший за нього, приймач не візьме. */
        val erasedAtMs: Map<String, Long> = emptyMap(),
    )

    data class Out(val peerId: String, val body: String)

    data class Step(val state: State, val out: List<Out> = emptyList())

    /** Null — людина не може отримувати положення ([PeerWhere.mayReceive]) або строк уже минув. */
    fun grant(
        state: State,
        peer: PeerIdentity,
        precision: PeerWhere.Precision = PeerWhere.Precision.DISTRICT,
        nowMs: Long,
        random: Random = secure,
        untilMs: Long = 0L,
    ): Step? {
        if (!PeerWhere.mayReceive(peer)) return null
        if (untilMs != 0L && untilMs <= nowMs) return null
        val old = state.lanes[peer.id]
        if (old != null && old.precision == precision) {
            if (old.untilMs == untilMs) return Step(state)
            // Строк скоротили: кадр, що вже лежить на тому кінці, живе довше за нову межу — переслати.
            val shorter = untilMs != 0L && old.sentExpiresAtMs > untilMs
            val lane = if (shorter) old.copy(untilMs = untilMs, dueAtMs = nowMs, why = Why.FORCED) else old.copy(untilMs = untilMs)
            val next = state.copy(lanes = state.lanes + (peer.id to lane))
            return if (shorter) flush(next, nowMs, random) else Step(next)
        }
        // Зміна точності не чекає слоту: на тому кінці досі лежить точніша точка.
        val lane = Lane(
            peerId = peer.id,
            precision = precision,
            dueAtMs = nowMs,
            why = if (old == null) Why.FIRST else Why.FORCED,
            untilMs = untilMs,
            hiddenUntilMs = old?.hiddenUntilMs ?: 0L,
        )
        return flush(state.copy(lanes = state.lanes + (peer.id to lane)), nowMs, random)
    }

    fun revoke(state: State, peerId: String, nowMs: Long): Step {
        if (peerId !in state.lanes) return Step(state)
        val erase = PeerWhere.encode(PeerWhere.Erase(nowMs))!!
        val lanes = state.lanes - peerId
        val slot = if (lanes.isEmpty()) 0L else state.slotAtMs
        return Step(state.copy(lanes = lanes, slotAtMs = slot, erasedAtMs = state.erasedAtMs + (peerId to nowMs)), listOf(Out(peerId, erase)))
    }

    /** «Зникнути»: стерти в усіх, забути дозволи й власну точку, замовкнути. */
    fun vanish(state: State, nowMs: Long): Step {
        val erase = PeerWhere.encode(PeerWhere.Erase(nowMs))!!
        val erased = state.erasedAtMs + state.lanes.keys.associateWith { nowMs }
        return Step(State(on = false, seq = state.seq, erasedAtMs = erased), state.lanes.keys.map { Out(it, erase) })
    }

    fun resume(state: State): State = state.copy(on = true)

    fun forgetPosition(state: State): State = state.copy(
        fix = null,
        lanes = state.lanes.mapValues { (_, l) ->
            l.copy(sentCell = null, sentLat = Double.NaN, sentLon = Double.NaN, sentAccuracyM = null, sentFixAtMs = 0L)
        },
    )

    /** [peerId] null — від усіх. Нуль — вийти з привида. Дріт не дізнається нічого: жодного кадру поза розкладом. */
    fun hide(state: State, peerId: String?, untilMs: Long): State = when (peerId) {
        null -> state.copy(hiddenAllUntilMs = untilMs)
        else -> state.lanes[peerId]?.let { state.copy(lanes = state.lanes + (peerId to it.copy(hiddenUntilMs = untilMs))) } ?: state
    }

    fun hidden(state: State, lane: Lane, nowMs: Long): Boolean =
        maxOf(lane.hiddenUntilMs, state.hiddenAllUntilMs) > nowMs

    /** Дозвіл тримається, лише доки людина досі [PeerWhere.mayReceive]: ключ підмінили — мовчимо. */
    fun prune(state: State, roster: List<PeerIdentity>): State {
        val ok = roster.filter(PeerWhere::mayReceive).map { it.id }.toSet()
        val lanes = state.lanes.filterKeys { it in ok }
        return state.copy(lanes = lanes, slotAtMs = if (lanes.isEmpty()) 0L else state.slotAtMs)
    }

    fun onFix(state: State, fix: Fix, nowMs: Long, random: Random = secure): Step {
        if (!state.on) return Step(state)
        return flush(state.copy(fix = fix), nowMs, random)
    }

    fun onAsk(state: State, peerId: String, nowMs: Long, random: Random = secure): Step {
        val lane = state.lanes[peerId]
        if (!state.on || lane == null) return Step(state)
        if (lane.answeredAskAtMs > 0L && nowMs - lane.answeredAskAtMs < ASK_GAP_MS) return Step(state)
        val due = if (lane.dueAtMs in 1..nowMs) lane else lane.copy(dueAtMs = nowMs, why = Why.ASK)
        return flush(state.copy(lanes = state.lanes + (peerId to due)), nowMs, random)
    }

    fun onTick(state: State, nowMs: Long, random: Random = secure): Step {
        if (!state.on) return Step(state)
        return flush(state, nowMs, random)
    }

    /** Null — будити нема чого. Дія людини без виміру будить не годинник, а [onFix]. */
    fun nextWakeMs(state: State, nowMs: Long): Long? {
        if (!state.on || state.lanes.isEmpty()) return null
        val ends = state.lanes.values.map { it.untilMs }.filter { it > 0L }
        val fix = state.fix?.takeIf { fresh(it, nowMs) }
        val urgent = state.lanes.values.mapNotNull { lane ->
            val canSend = fix != null && usable(state, lane, fix)
            lane.dueAtMs.takeIf { it > 0L && (canSend || it > nowMs || hidden(state, lane, nowMs)) }
        }
        val saying = state.lanes.values.any { hasSaying(it, state.fix.takeUnless { _ -> hidden(state, it, nowMs) }, nowMs) }
        val unhide = (state.lanes.values.map { it.hiddenUntilMs } + state.hiddenAllUntilMs).filter { it > nowMs }
        val slot = state.slotAtMs.takeIf { it > 0L && saying }
        return (urgent + ends + unhide + listOfNotNull(slot)).minOrNull()
    }

    /**
     * Вимір потрібен лише перед слотом або під дію людини — і лише коли
     * тримане застаріло. Решту часу GNSS не питають.
     */
    fun wantsFix(state: State, nowMs: Long): Boolean {
        if (!state.on || state.lanes.isEmpty()) return false
        if (fresh(state.fix, nowMs)) return false
        if (state.lanes.values.any { it.dueAtMs in 1..nowMs }) return true
        return state.slotAtMs > 0L && state.slotAtMs - nowMs in -FIX_MAX_AGE_MS..FIX_LEAD_MS
    }

    private fun hasSaying(lane: Lane, fix: Fix?, nowMs: Long): Boolean =
        fresh(fix, nowMs) || (lane.sentFixAtMs > 0L && nowMs - lane.sentFixAtMs <= HOLD_MAX_AGE_MS)

    private fun finest(state: State): PeerWhere.Precision =
        state.lanes.values.map { it.precision }.maxBy { it.code }

    internal fun nextGapMs(p: PeerWhere.Precision, random: Random): Long {
        val floor = slotFloorMs(p)
        val tail = -ln(1.0 - random.nextDouble()) * (slotMeanMs(p) - floor)
        return minOf(floor + tail.toLong(), slotCapMs(p))
    }

    private fun sticky(sent: PeerWhereGrid.Cell?, fix: Fix, level: PeerWhereGrid.Level): PeerWhereGrid.Cell {
        val now = PeerWhereGrid.cellOf(fix.lat, fix.lon, level)
        if (sent == null || now == sent) return now
        return if (PeerWhereGrid.escaped(sent, fix.lat, fix.lon, HYSTERESIS * level.sizeM)) now else sent
    }

    private fun usable(state: State, lane: Lane, fix: Fix): Boolean = fix.atMs > (state.erasedAtMs[lane.peerId] ?: 0L)

    private fun fresh(fix: Fix?, nowMs: Long): Boolean =
        fix != null && nowMs - fix.atMs in 0..FIX_MAX_AGE_MS

    private fun frameFor(lane: Lane, fix: Fix?, repeat: Boolean, expires: Long, seq: Int): Pair<PeerWhere.Position, Lane>? {
        val level = lane.precision.level
        return when {
            fix != null && level == null -> PeerWhere.Point(fix.lat, fix.lon, fix.accuracyM, fix.atMs, expires, seq, fix.state) to
                lane.copy(sentLat = fix.lat, sentLon = fix.lon, sentAccuracyM = fix.accuracyM, sentCell = null, sentFixAtMs = fix.atMs, sentState = fix.state)
            fix != null && level != null -> {
                val cell = sticky(lane.sentCell?.takeIf { it.level == level }, fix, level)
                val areaState = fix.state.copy(spot = PeerWhere.Spot.NONE)
                PeerWhere.Area(cell, fix.atMs, expires, seq, areaState) to
                    lane.copy(sentCell = cell, sentLat = Double.NaN, sentLon = Double.NaN, sentAccuracyM = null, sentFixAtMs = fix.atMs, sentState = areaState)
            }
            // Пропущений слот теж був би знаком, тож без свіжого виміру слот повторює сказане з його миттю виміру.
            !repeat -> null
            lane.sentCell != null -> PeerWhere.Area(lane.sentCell, lane.sentFixAtMs, expires, seq, lane.sentState) to lane
            !lane.sentLat.isNaN() ->
                PeerWhere.Point(lane.sentLat, lane.sentLon, lane.sentAccuracyM, lane.sentFixAtMs, expires, seq, lane.sentState) to lane
            else -> null
        }
    }

    private fun flush(state: State, nowMs: Long, random: Random): Step {
        if (!state.on) return Step(state)
        val lapsed = state.lanes.values.filter { it.untilMs in 1..nowMs }.map { it.peerId }
        if (lapsed.isNotEmpty()) {
            val erase = PeerWhere.encode(PeerWhere.Erase(nowMs))!!
            val erased = state.erasedAtMs + lapsed.associateWith { nowMs }
            val rest = flush(state.copy(lanes = state.lanes - lapsed.toSet(), erasedAtMs = erased), nowMs, random)
            return rest.copy(out = lapsed.map { Out(it, erase) } + rest.out)
        }
        if (state.lanes.isEmpty()) return Step(state.copy(slotAtMs = 0L))
        val slot = state.slotAtMs in 1..nowMs
        val fix = state.fix?.takeIf { fresh(it, nowMs) }
        var seq = state.seq
        val out = ArrayList<Out>()
        val lanes = state.lanes.mapValues { (_, lane) ->
            val urgent = lane.dueAtMs in 1..nowMs
            if (!urgent && !slot) return@mapValues lane
            val ghost = hidden(state, lane, nowMs)
            // Привид не відповідає позачергово новим місцем: лише сказане раніше, а без нього — мовчить,
            // і черга гаситься, інакше годинник будився б щосекунди.
            val repeat = (slot || ghost) && (ghost || !urgent) && hasSaying(lane, null, nowMs)
            val expires = (nowMs + ttlMs(lane.precision)).let { if (lane.untilMs > 0L) minOf(it, lane.untilMs) else it }
            val quiet = if (ghost) lane.copy(dueAtMs = 0L, why = null) else lane
            val framed = frameFor(lane, fix.takeUnless { ghost }?.takeIf { usable(state, lane, it) }, repeat, expires, seq + 1)
            if (framed == null) {
                // Точність змінили під час привида: старшу точку на тому кінці стерти, нової не давати.
                if (ghost && urgent && lane.why == Why.FORCED) out.add(Out(lane.peerId, PeerWhere.encode(PeerWhere.Erase(nowMs))!!))
                return@mapValues quiet
            }
            val (frame, sent) = framed
            val body = PeerWhere.encode(frame) ?: return@mapValues lane
            seq += 1
            out.add(Out(lane.peerId, body))
            sent.copy(
                lastSentAtMs = nowMs,
                sentExpiresAtMs = expires,
                dueAtMs = 0L,
                why = null,
                answeredAskAtMs = if (lane.why == Why.ASK && urgent) nowMs else lane.answeredAskAtMs,
            )
        }
        val slotAt = if (slot || state.slotAtMs == 0L) nowMs + nextGapMs(finest(state), random) else state.slotAtMs
        return Step(state.copy(lanes = lanes, seq = seq, slotAtMs = slotAt), out)
    }

    private val secure: Random by lazy { java.security.SecureRandom().asKotlinRandom() }
}
