package local.phantom.companion.core.truth

import kotlin.math.pow

/**
 * Коло — голоси, які не можна вважати незалежними: один запрошувач, спільна
 * мережа в молодих акаунтів, запрошення між собою. Коло важить як його
 * найсильніший член плюс згасаючий хвіст, тож скільки б ботів не було, коло
 * ніколи не важить більше за 2× свого найсильнішого.
 */
object Independence {
    const val YOUNG_MS = 30L * 24 * 3_600_000
    const val TAIL = 0.5

    fun circleMass(weights: List<Double>): Double =
        weights.sortedDescending().foldIndexed(0.0) { i, acc, w -> acc + w * TAIL.pow(i) }

    // Мережа звʼязує лише молодих: мобільні оператори садять місто за один CGNAT,
    // і давні незалежні люди з однієї адреси інакше злились би в одне коло.
    fun linked(a: Voter, b: Voter, asOfMs: Long): Boolean {
        if (a.invitedBy == b.id || b.invitedBy == a.id) return true
        if (a.invitedBy != null && a.invitedBy == b.invitedBy) return true
        if (a.networkId != null && a.networkId == b.networkId) {
            val young = asOfMs - a.createdAtMs < YOUNG_MS || asOfMs - b.createdAtMs < YOUNG_MS
            if (young) return true
        }
        return false
    }

    /** Корінь кола для кожного голосу; той самий корінь — те саме коло. */
    fun circles(voters: Collection<Voter>, asOfMs: Long): Map<VoterId, VoterId> {
        val list = voters.distinctBy { it.id }
        val parent = HashMap<VoterId, VoterId>()
        list.forEach { parent[it.id] = it.id }
        fun find(x: VoterId): VoterId {
            var r = x
            while (parent[r] != r) r = parent[r]!!
            var c = x
            while (parent[c] != r) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        for (i in list.indices) for (j in i + 1 until list.size) {
            if (linked(list[i], list[j], asOfMs)) {
                val ra = find(list[i].id); val rb = find(list[j].id)
                if (ra != rb) parent[ra] = rb
            }
        }
        return list.associate { it.id to find(it.id) }
    }
}
