package local.phantom.companion.core.truth

import kotlin.math.pow

/**
 * Довіра до факту.
 *
 *   вага свідчення  w = репутація × вік ключа × присутність × 2^(−вік свідчення / півжиття)
 *   підтримка показу S_r = Σ по колах circleMass(w членів кола, що кажуть r)
 *   ймовірність     P_r = (S_r + 1/k) / (ΣS + 1)        k — кількість показів виду
 *
 * Апріорна маса 1 розмазана порівну: одне свідчення новачка (0.125) дає 0.56 —
 * «невідомо», одне свідчення зрілої людини на місці (1.0) дає 0.75 — «ймовірно».
 * Усі свідчення одного факту згасають з однаковим множником, тож без нових
 * голосів P лідера лише сповзає до 1/k і ніколи не росте — згасання монотонне.
 * Застаріле — коли наймолодшому свідченню понад 2 півжиття (важить ≤ ¼).
 */
object Truth {
    const val PRIOR_MASS = 1.0
    const val LIKELY_AT = 0.7
    const val STALE_AFTER_HALF_LIVES = 2.0
    const val PRESENCE_SLACK_MS = 15 * 60_000L

    fun decay(kind: FactKind, ageMs: Long): Double =
        0.5.pow(ageMs.coerceAtLeast(0).toDouble() / kind.halfLifeMs)

    fun presenceFactor(obs: Observation): Double =
        if (obs.presence?.covers(obs.key.place.cell, obs.atMs, PRESENCE_SLACK_MS) == true) 1.0 else obs.key.kind.remoteFactor

    /** Остання думка кожної людини; раніші вона сама відкликала. */
    fun latestPerVoter(key: FactKey, observations: List<Observation>, nowMs: Long): List<Observation> =
        observations.asSequence()
            .filter { it.key == key && it.atMs <= nowMs }
            .groupBy { it.voter }
            .map { (_, list) -> list.maxBy { it.atMs } }

    /**
     * [reputation] — знімок ваги людини на момент читання. Коло рахується на мить
     * наймолодшого свідчення, а не на [nowMs], щоб старіння ключів не піднімало
     * вагу факту заднім числом.
     */
    fun verdict(
        key: FactKey,
        observations: List<Observation>,
        voters: Map<VoterId, Voter>,
        reputation: (VoterId) -> Double,
        nowMs: Long,
    ): Verdict {
        val kind = key.kind
        val obs = latestPerVoter(key, observations, nowMs)
        if (obs.isEmpty()) {
            return Verdict(key, TruthState.UNKNOWN, null, 1.0 / kind.readings.size, 0.0, 0, null, null, false)
        }
        val newest = obs.maxBy { it.atMs }
        val circles = Independence.circles(obs.map { voters[it.voter] ?: Voter(it.voter, it.atMs) }, newest.atMs)

        fun weight(o: Observation): Double =
            reputation(o.voter) * Reputation.ageFactor(voters[o.voter], o.atMs) * presenceFactor(o) * decay(kind, nowMs - o.atMs)

        val support = kind.readings.associateWith { r ->
            obs.filter { it.reading == r }
                .groupBy { circles[it.voter] ?: it.voter }
                .values.sumOf { members -> Independence.circleMass(members.map(::weight)) }
        }
        val mass = support.values.sum()
        val top = kind.readings.maxWith(
            compareBy<Reading> { support.getValue(it) }.thenBy { r -> obs.filter { it.reading == r }.maxOfOrNull { it.atMs } ?: Long.MIN_VALUE },
        )
        val p = (support.getValue(top) + PRIOR_MASS / kind.readings.size) / (mass + PRIOR_MASS)
        val stale = nowMs - newest.atMs > STALE_AFTER_HALF_LIVES * kind.halfLifeMs
        val state = when {
            stale -> TruthState.STALE
            p >= LIKELY_AT -> TruthState.LIKELY
            else -> TruthState.UNKNOWN
        }
        val horizon = (STALE_AFTER_HALF_LIVES * kind.halfLifeMs).toLong()
        val backers = obs.filter { it.reading == top && nowMs - it.atMs <= horizon }
        return Verdict(
            key = key,
            state = state,
            reading = top,
            probability = p,
            mass = mass,
            supporters = backers.size,
            lastSupportAtMs = backers.maxOfOrNull { it.atMs } ?: obs.filter { it.reading == top }.maxOfOrNull { it.atMs },
            newestAtMs = newest.atMs,
            dissent = !Settlement.agrees(kind, newest.reading, top),
            contested = kind.readings.any { !Settlement.agrees(kind, it, top) && support.getValue(it) > 0.0 },
        )
    }
}
