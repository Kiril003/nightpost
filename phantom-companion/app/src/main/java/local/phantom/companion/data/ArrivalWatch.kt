package local.phantom.companion.data

/**
 * «Я на місці» без дотику: людина їде до друга чи до точки зустрічі, і коли
 * два виміри поспіль лягли в коло прибуття — друг отримує слова. Рівно один раз.
 * Годинника немає: будять лише виміри, які й так приходять. Три години — і
 * обіцянка гасне, щоб завтрашній проїзд повз не сказав «я на місці» вчорашньому.
 */
object ArrivalWatch {

    const val RADIUS_M = 120.0
    const val LIFE_MS = 3 * 3_600_000L

    data class Goal(
        val lat: Double,
        val lon: Double,
        val peerIds: List<String>,
        val words: String,
        val sinceMs: Long,
        val inside: Int = 0,
    )

    data class Step(val goals: List<Goal>, val fire: List<Goal> = emptyList())

    fun onFix(goals: List<Goal>, lat: Double, lon: Double, accuracyM: Float?, nowMs: Long): Step {
        if (goals.isEmpty()) return Step(goals)
        val fire = ArrayList<Goal>()
        val kept = goals.mapNotNull { g ->
            if (nowMs - g.sinceMs > LIFE_MS) return@mapNotNull null
            val sure = (accuracyM ?: 0f) <= RADIUS_M
            val within = sure && metres(lat, lon, g.lat, g.lon) <= RADIUS_M
            val next = if (within) g.copy(inside = g.inside + 1) else g.copy(inside = 0)
            if (next.inside >= 2) {
                fire += next
                null
            } else {
                next
            }
        }
        return Step(kept, fire)
    }

    private fun metres(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dLat = (bLat - aLat) * 111_320.0
        val dLon = (bLon - aLon) * 111_320.0 * Math.cos(Math.toRadians((aLat + bLat) / 2))
        return Math.hypot(dLat, dLon)
    }
}
