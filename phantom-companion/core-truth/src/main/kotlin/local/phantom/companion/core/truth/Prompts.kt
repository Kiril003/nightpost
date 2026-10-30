package local.phantom.companion.core.truth

import kotlin.math.ln
import kotlin.math.min

private const val HOUR_MS = 3_600_000L

/** [viewersLast24h] — скільки різних людей дивились на факт за добу (агрегат із сервера). */
data class PromptCandidate(val verdict: Verdict, val viewersLast24h: Int)

data class Asked(val key: FactKey, val atMs: Long)

/**
 * Три питання за будь-які 24 години, не частіше ніж раз на 45 хв — людина, яку
 * смикають частіше, вимикає сповіщення назавжди, і тоді не питаємо вже нікого.
 */
data class PromptPolicy(
    val maxPer24h: Int = 3,
    val minGapMs: Long = 45 * 60_000L,
    val minScore: Double = 0.5,
)

/**
 * Живе на пристрої: сервер не дізнається, коли й про що людину питали, і де
 * вона була в ту мить. Питаємо лише про факти в тій самій комірці.
 *
 *   нужда   = 1 для «невідомо», 0.8 для «застаріло», для «ймовірно» —
 *             вік останнього підтвердження в півжиттях / 2 (свіжий факт не питаємо);
 *             +0.5, якщо останнє свідчення суперечить консенсусу
 *   попит   = ln(1 + переглядів за добу)
 *   бал     = важливість виду × нужда × (1 + попит)
 */
class PromptPlanner(private val policy: PromptPolicy = PromptPolicy()) {

    fun score(c: PromptCandidate, nowMs: Long): Double {
        val v = c.verdict
        val kind = v.key.kind
        val need = when (v.state) {
            TruthState.UNKNOWN -> 1.0
            TruthState.STALE -> 0.8
            TruthState.LIKELY -> {
                val age = nowMs - (v.lastSupportAtMs ?: v.newestAtMs ?: nowMs)
                (age.toDouble() / kind.halfLifeMs).coerceIn(0.0, 2.0) / 2.0
            }
        } + if (v.dissent) 0.5 else 0.0
        return kind.importance * need * (1.0 + ln(1.0 + c.viewersLast24h.coerceAtLeast(0)))
    }

    private fun cooldownMs(kind: FactKind): Long = min(kind.halfLifeMs, 12 * HOUR_MS)

    fun next(
        here: CellId,
        candidates: List<PromptCandidate>,
        asked: List<Asked>,
        mine: List<Observation>,
        alertActive: Boolean,
        nowMs: Long,
    ): PromptCandidate? {
        if (alertActive) return null
        val recent = asked.filter { it.atMs in (nowMs - 24 * HOUR_MS + 1)..nowMs }
        if (recent.size >= policy.maxPer24h) return null
        if (recent.any { nowMs - it.atMs < policy.minGapMs }) return null
        return candidates.asSequence()
            .filter { it.verdict.key.place.cell == here }
            .filter { c -> asked.none { it.key == c.verdict.key && nowMs - it.atMs < cooldownMs(c.verdict.key.kind) } }
            .filter { c -> mine.none { it.key == c.verdict.key && nowMs - it.atMs < c.verdict.key.kind.halfLifeMs / 2 } }
            .map { it to score(it, nowMs) }
            .filter { it.second >= policy.minScore }
            .maxByOrNull { it.second }
            ?.first
    }
}
