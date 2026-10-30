package local.phantom.companion.core.context.decision

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import local.phantom.companion.core.context.ContextInput
import local.phantom.companion.core.context.ContextSnapshot
import local.phantom.companion.core.context.SystemState

interface DecisionNode {
    suspend fun evaluate(input: ContextInput): NodeVote
}

data class NodeVote(
    val state: SystemState,
    val weight: Float,    // 0..1
    val rationale: String,
)

class DecisionTree(
    private val nodes: List<DecisionNode>,
    /**
     * Скільки ваги челенджер мусить мати ПОНАД чинний стан, щоб його змістити.
     *
     * Без цього телефон на столі перекидав стан кожні 30 секунд. Рахунок був
     * такий: у вихідний TimeOfDay дає ВАРТІ рівно 0.4, а тихе приміщення дає
     * ФОКУСУ рівно 0.4. MotionNode мовчить, поки |a| < 0.3 — і мікровібрації
     * столу ганяли його через цей поріг: вище — ФОКУС 0.7 і виграє, нижче —
     * нічия 0.4:0.4, яку розсудив порядок вставки в мапу. Стрічка власника
     * заповнилась карточками ВАРТА → ФОКУС → ВАРТА без жодної справжньої зміни
     * обставин.
     */
    private val switchMargin: Float = 0.15f,
) {

    suspend fun decide(
        input: ContextInput,
        incumbent: SystemState? = null,
    ): ContextSnapshot = coroutineScope {
        val votes = nodes.map { async { it.evaluate(input) } }.awaitAll()
            .filter { it.weight > 0f }

        if (votes.isEmpty()) {
            return@coroutineScope ContextSnapshot(
                timestampMs = input.nowMs,
                state = SystemState.FOCUS,
                confidence = 0f,
                rationale = emptyList(),
            )
        }

        // Aggregate: sum weights per state
        val scores = mutableMapOf<SystemState, Float>()
        for (vote in votes) {
            scores[vote.state] = (scores[vote.state] ?: 0f) + vote.weight
        }

        val totalScore = scores.values.sum()
        val leader = scores.maxByOrNull { it.value }!!.key
        val winner = when {
            incumbent == null || leader == incumbent -> leader
            (scores[leader] ?: 0f) - (scores[incumbent] ?: 0f) >= switchMargin -> leader
            else -> incumbent
        }
        val winningScore = scores[winner] ?: 0f
        val confidence = if (totalScore > 0f) winningScore / totalScore else 0f

        // Переможцева причина попереду. SystemPulseRepository бере firstOrNull,
        // і поки список ішов у порядку вузлів, кожен перехід — в обидва боки —
        // пояснювався тим самим «вихідний — пасивне спостереження», бо
        // TimeOfDayNode стоїть у списку першим.
        val rationale = votes.sortedByDescending { it.state == winner }.map { it.rationale }

        ContextSnapshot(
            timestampMs = input.nowMs,
            state = winner,
            confidence = confidence,
            rationale = rationale,
        )
    }
}
