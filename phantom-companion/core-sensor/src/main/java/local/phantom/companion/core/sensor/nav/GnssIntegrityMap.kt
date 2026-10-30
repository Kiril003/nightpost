package local.phantom.companion.core.sensor.nav

/**
 * HORIZON II.1 — the memory of where the sky turned hostile. Every drive
 * leaves the [CustomLocationEngine]'s trust verdict along the operator's
 * track; this accumulates those verdicts into a **per-tile GNSS-integrity
 * prior** so a future route can be scored on how likely its corridors are to
 * be jammed — the foundation of «маршрут чистого неба».
 *
 * No tech giant carries this, because no tech giant's map assumes the sky is
 * an adversary. The accumulation follows the `poi_gazetteer` doctrine:
 * observations only ever raise or slowly relax a tile's risk, and the map
 * survives process death (snapshot/restore). It NEVER TTL-evicts — a place
 * that was jammed last week is a place worth routing around this week until
 * proven clean.
 *
 * Pure Kotlin, no Android, no clock beyond what the caller passes. Risk is a
 * scalar in [0, 1]: 0 = clean sky observed, 1 = confirmed jamming/spoof.
 */
class GnssIntegrityMap(
    /** ~278 m tiles at Kyiv's latitude — a city block's worth of sky. */
    private val cellDeg: Double = CELL_DEG,
    private val alpha: Double = EWMA_ALPHA,
) {
    /**
     * ПИШЕ ОДИН ПОТІК, ЧИТАЮТЬ ІНШІ — і саме тому не `HashMap`.
     *
     * Годує цю мапу екран мапи, вироком довіри з КОЖНОГО фікса; читають її
     * маршрутизатори на `Dispatchers.IO`. Доки читач був один і рідкий
     * (евакуація), звичайний `HashMap` сходив з рук. Відколи [costFactorAt]
     * стоїть і на головному маршруті ([RouteCaution.EVERYDAY]), читання
     * трапляється на кожній перебудові — а читання `HashMap` посеред чужого
     * resize уміє не лише збрехати, а й зациклитись назавжди.
     *
     * Втрачене оновлення EWMA тут не біда (наступний фікс через секунду
     * поправить), а от зіпсована структура — біда назавжди. Тому саме
     * `ConcurrentHashMap`: ціна нуль, а клас відмов зникає.
     */
    private val risk = java.util.concurrent.ConcurrentHashMap<Long, Float>()
    private val samples = java.util.concurrent.ConcurrentHashMap<Long, Int>()

    val tileCount: Int get() = risk.size

    /**
     * Fold one trust observation at (lat, lon) into its tile. [trustRisk] is
     * the verdict-as-scalar (see [riskOf]); the tile's stored risk is an EWMA,
     * so a single clean pass through a chronically-jammed area nudges but does
     * not absolve it, and vice-versa.
     */
    fun observe(lat: Double, lon: Double, trustRisk: Float) {
        val k = key(lat, lon)
        val prev = risk[k]
        risk[k] = if (prev == null) trustRisk else (prev + (trustRisk - prev) * alpha.toFloat())
        samples[k] = (samples[k] ?: 0) + 1
    }

    /** The learned risk at a point, 0 (clean / never seen) … 1 (jammed). */
    fun riskAt(lat: Double, lon: Double): Float = risk[key(lat, lon)] ?: 0f

    /** How many observations have shaped this tile — confidence in [riskAt]. */
    fun samplesAt(lat: Double, lon: Double): Int = samples[key(lat, lon)] ?: 0

    /**
     * The router's cost multiplier at a point: 1.0 on clean/unseen sky,
     * rising toward 1 + [avoidance] where risk is 1. A segment through a
     * fully-jammed tile costs (1 + avoidance)× its length, so the planner
     * will accept a detour up to that factor longer to keep integrity.
     */
    fun costFactorAt(lat: Double, lon: Double, avoidance: Double): Double =
        1.0 + avoidance * riskAt(lat, lon)

    /** Mean learned risk along a polyline — for comparing route variants. */
    fun meanRiskAlong(points: List<Pair<Double, Double>>): Double {
        if (points.isEmpty()) return 0.0
        return points.sumOf { riskAt(it.first, it.second).toDouble() } / points.size
    }

    /** Flat snapshot for persistence (tile key → risk). Sample counts are
     *  advisory and intentionally not persisted — risk is the durable fact. */
    fun snapshot(): Map<Long, Float> = HashMap(risk)

    fun restore(snapshot: Map<Long, Float>) {
        risk.clear()
        samples.clear()
        risk.putAll(snapshot)
    }

    private fun key(lat: Double, lon: Double): Long {
        val la = Math.floor(lat / cellDeg).toLong()
        val lo = Math.floor(lon / cellDeg).toLong()
        return (la shl 32) xor (lo and 0xFFFFFFFFL)
    }

    companion object {
        const val CELL_DEG = 0.0025
        const val EWMA_ALPHA = 0.25

        /**
         * LocationTrust (+ dead-reckoning) as a routing risk scalar. Spoofing
         * is the worst — a chosen false position is more dangerous than mere
         * silence; jamming and inertial coasting are bad; degraded is a
         * shadow; a trusted sky is clean.
         */
        fun riskOf(trust: LocationTrust?, dead: Boolean): Float = when {
            trust == LocationTrust.SPOOFED -> 1.0f
            dead || trust == LocationTrust.JAMMED -> 0.85f
            trust == LocationTrust.DEGRADED -> 0.4f
            trust == LocationTrust.TRUSTED -> 0.0f
            else -> 0.15f // unknown: a faint prior, not zero
        }
    }
}
