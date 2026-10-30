package local.phantom.companion.core.truth

import kotlin.math.abs
import kotlin.math.pow

private const val DAY_MS = 24L * 3_600_000

/** Скільки свідчень людини підтвердили й спростували інші. Обидва лічильники згасають разом. */
data class Standing(val confirmed: Double = 0.0, val refuted: Double = 0.0, val asOfMs: Long = 0L) {
    fun at(nowMs: Long): Standing {
        if (nowMs <= asOfMs) return this
        val f = 0.5.pow((nowMs - asOfMs).toDouble() / Reputation.HALF_LIFE_MS)
        return Standing(confirmed * f, refuted * f, nowMs)
    }
}

/**
 * Вага = суміш «новачка» і заслуженої довіри. Заслужена довіра — стеля ×
 * квадрат частки підтверджених (Лаплас: 1 з 1 не дає 100 %). Квадрат не для
 * краси: з лінійною часткою перше ж спростування ПІДНІМАЛО вагу над новачком
 * (половина стелі 1.5 > 0.5). Змішування йде за досвідом, тож пʼять
 * підтверджень — ще не вершина.
 *
 * Числа: новачок 0.5, стеля 3.0 — найзаслуженіша людина важить як шість
 * новачків, але не більше; ніхто не перекричить шістьох незалежних. Спростований
 * 10 разів без жодного підтвердження важить ~0.14, 20 разів — ~0.04.
 * Згасання 180 діб: заслуга і ганьба забуваються за сезони, не за тижні.
 * Свіжий ключ важить чверть від своєї ваги, за 14 діб доростає до повної —
 * ботоферма мусить витримати ключі два тижні, перш ніж вони почнуть важити.
 */
object Reputation {
    const val NEWCOMER = 0.5
    const val CEILING = 3.0
    const val MATURITY_SCALE = 5.0
    const val HALF_LIFE_MS = 180 * DAY_MS
    const val AGE_RAMP_MS = 14 * DAY_MS
    const val AGE_FLOOR = 0.25

    fun weight(s: Standing): Double {
        val n = s.confirmed + s.refuted
        val score = (s.confirmed + 1.0) / (n + 2.0)
        val maturity = 1.0 - 0.5.pow(n / MATURITY_SCALE)
        return NEWCOMER * (1.0 - maturity) + CEILING * score * score * maturity
    }

    fun ageFactor(voter: Voter?, atMs: Long): Double {
        if (voter == null) return AGE_FLOOR
        val t = ((atMs - voter.createdAtMs).toDouble() / AGE_RAMP_MS).coerceIn(0.0, 1.0)
        return AGE_FLOOR + (1.0 - AGE_FLOOR) * t
    }
}

enum class Outcome { OPEN, CONFIRMED, REFUTED }

/**
 * Свідчення перевіряється тим, що казали ІНШІ кола приблизно тоді ж (±½
 * півжиття): світло, яке ввімкнули за три години, не робить брехуном того, хто
 * бачив темряву. Рахуються кола, а не вага, — інакше заслужені звіряли б самі
 * себе, і стара змова ставала б непорушною.
 */
object Settlement {
    const val MIN_CIRCLES = 2

    fun agrees(kind: FactKind, a: Reading, b: Reading): Boolean =
        if (kind == FactKind.QUEUE) abs(kind.readings.indexOf(a) - kind.readings.indexOf(b)) <= 1 else a == b

    fun judge(obs: Observation, all: List<Observation>, circleOf: (VoterId) -> VoterId, nowMs: Long): Outcome {
        val window = obs.key.kind.halfLifeMs / 2
        if (nowMs < obs.atMs + window) return Outcome.OPEN
        val own = circleOf(obs.voter)
        val near = all.filter {
            it.key == obs.key && it.voter != obs.voter &&
                abs(it.atMs - obs.atMs) <= window && circleOf(it.voter) != own
        }
        val agree = near.filter { agrees(obs.key.kind, it.reading, obs.reading) }.map { circleOf(it.voter) }.toSet().size
        val disagree = near.filterNot { agrees(obs.key.kind, it.reading, obs.reading) }.map { circleOf(it.voter) }.toSet().size
        return when {
            agree >= MIN_CIRCLES && agree > disagree -> Outcome.CONFIRMED
            disagree >= MIN_CIRCLES && disagree >= 2 * agree -> Outcome.REFUTED
            else -> Outcome.OPEN
        }
    }
}

class ReputationLedger {
    private val standings = HashMap<VoterId, Standing>()
    private val settled = HashSet<Observation>()

    fun standing(voter: VoterId, nowMs: Long): Standing = (standings[voter] ?: Standing(asOfMs = nowMs)).at(nowMs)

    fun weight(voter: VoterId, nowMs: Long): Double = Reputation.weight(standing(voter, nowMs))

    /** Кожне свідчення судиться рівно раз — коли закрилось його вікно. */
    fun settle(all: List<Observation>, voters: Map<VoterId, Voter>, nowMs: Long): List<Pair<Observation, Outcome>> {
        val circles = Independence.circles(all.map { voters[it.voter] ?: Voter(it.voter, it.atMs) }, nowMs)
        val circleOf = { v: VoterId -> circles[v] ?: v }
        val out = ArrayList<Pair<Observation, Outcome>>()
        for (obs in all) {
            if (obs in settled || nowMs < obs.atMs + obs.key.kind.halfLifeMs / 2) continue
            val o = Settlement.judge(obs, all, circleOf, nowMs)
            settled += obs
            val s = standing(obs.voter, nowMs)
            standings[obs.voter] = when (o) {
                Outcome.CONFIRMED -> s.copy(confirmed = s.confirmed + 1)
                Outcome.REFUTED -> s.copy(refuted = s.refuted + 1)
                Outcome.OPEN -> s
            }
            out += obs to o
        }
        return out
    }
}
