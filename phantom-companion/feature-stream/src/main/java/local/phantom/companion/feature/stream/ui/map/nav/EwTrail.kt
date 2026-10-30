package local.phantom.companion.feature.stream.ui.map.nav

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import local.phantom.companion.core.sensor.nav.LocationTrust

/**
 * LEVIATHAN-2 — the EW topology of the operator's own track: a breadcrumb
 * trail where every walked stretch remembers whether the sky over it was
 * live or hostile. A drive through contested ground leaves a literal map of
 * where the sky turned, drawn from the device's own experience and nothing
 * else.
 *
 * Двомовний навмисне. Пʼять відтінків вироку робили слід осцилографом:
 * вирок фляпає секунда-до-секунди, тож сусідні крихти виходили різних
 * барв, а поруч із бурштиновою точкою це руйнувало її одиничність. І
 * «TRUSTED проти DEGRADED учора» не є рішенням, яке хтось ухвалює;
 * «тут небо було вороже» — є.
 *
 * Pure state + GeoJSON building; no Android, no MapLibre. The map renders
 * [geoJson] through two LineLayers filtered on `sky`.
 */
class EwTrail(
    private val maxPoints: Int = MAX_POINTS,
    private val minStepM: Double = MIN_STEP_M,
) {
    private data class Crumb(val lat: Double, val lon: Double, val hostile: Boolean)

    private val crumbs = ArrayDeque<Crumb>()
    private var lastBuildMs = 0L

    private val _geoJson = MutableStateFlow(EMPTY)
    val geoJson: StateFlow<String> = _geoJson.asStateFlow()

    fun clear() {
        crumbs.clear()
        _geoJson.value = EMPTY
    }

    /**
     * One pumped fix. Rebuilds the GeoJSON at most once per [REBUILD_MIN_MS]
     * — the trail is history, not animation.
     *
     * Крихта лягає ЛИШЕ в русі, і це головна правка: телефон, що лежить
     * нерухомо під ±85 м шуму GNSS, зсувався >2 м щосекунди й сипав крихти
     * на місці — «подряпини» на знімку 17:14 є цим шумом, не шляхом. Тому
     * два гейти замість одного:
     *
     *  - швидкість ≥ [MOVING_FLOOR_MPS] — той самий поріг, що віддає курс
     *    шеврону, тож рухом тут і там зветься одне й те саме;
     *  - крок ≥ max([minStepM], [STEP_SIGMA_SHARE] × точності) — щільність
     *    сліду не має бути дрібнішою за власну похибку: при ±85 м це ~30 м.
     *
     * [speedMps] null — це не доказ руху, і крихти не буде: невідома
     * швидкість мовчить, а не свідчить.
     */
    fun onFix(
        lat: Double,
        lon: Double,
        dead: Boolean,
        trust: LocationTrust?,
        nowMs: Long,
        speedMps: Float?,
        accuracyM: Float?,
    ) {
        if ((speedMps ?: 0f) < MOVING_FLOOR_MPS) return
        val step = maxOf(minStepM, (accuracyM?.toDouble() ?: 0.0) * STEP_SIGMA_SHARE)
        val last = crumbs.lastOrNull()
        if (last != null && haversineM(last.lat, last.lon, lat, lon) < step) return
        crumbs.addLast(Crumb(lat, lon, hostileSky(dead, trust)))
        while (crumbs.size > maxPoints) crumbs.removeFirst()
        rebuild(nowMs)
    }

    private fun rebuild(nowMs: Long) {
        if (nowMs - lastBuildMs < REBUILD_MIN_MS) return
        lastBuildMs = nowMs
        if (crumbs.size < 2) {
            _geoJson.value = EMPTY
            return
        }
        val n = crumbs.size
        val sb = StringBuilder(n * 96)
        sb.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var first = true
        var prev: Crumb? = null
        crumbs.forEachIndexed { i, c ->
            val p = prev
            prev = c
            if (p == null) return@forEachIndexed
            // P0-C (field capture 2026-07-21) — a step no walk or drive could
            // have made between two observed fixes is a reacquisition snap or a
            // resume-from-background jump, NOT a path the operator travelled.
            // Drawing it as a straight line across the map was the alarming
            // «100 m blue line» the owner photographed. Break the trail here:
            // c still anchors the next segment, but nothing connects to it.
            if (haversineM(p.lat, p.lon, c.lat, c.lon) > MAX_SEGMENT_M) return@forEachIndexed
            // Age fade: the oldest segment barely whispers, the newest is firm.
            val alpha = MIN_ALPHA + (MAX_ALPHA - MIN_ALPHA) * (i.toFloat() / (n - 1))
            if (!first) sb.append(',')
            first = false
            sb.append("{\"type\":\"Feature\",\"properties\":{\"").append(SKY_KEY).append("\":\"")
                .append(if (c.hostile) SKY_HOSTILE else SKY_CLEAR)
                .append("\",\"alpha\":")
                .append(String.format(java.util.Locale.ROOT, "%.2f", alpha))
                .append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[")
                .append(p.lon).append(',').append(p.lat).append("],[")
                .append(c.lon).append(',').append(c.lat).append("]]}}")
        }
        sb.append("]}")
        _geoJson.value = sb.toString()
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
        return 2 * r * Math.asin(Math.sqrt(a.coerceIn(0.0, 1.0)))
    }

    companion object {
        const val EMPTY = """{"type":"FeatureCollection","features":[]}"""

        /**
         * Небо на дроті. Шар сліду, що читав ці рядки, знято з мапи — лишився
         * журнал РЕБ. Написання зафіксоване тут, щоб наступний читач крихт
         * брав його звідси, а не вигадував удруге.
         */
        const val SKY_KEY = "sky"
        const val SKY_CLEAR = "clear"
        const val SKY_HOSTILE = "hostile"

        /**
         * The verdict palette — trust, spoken in color. Кільце і чип живляться
         * звідси; слід — уже НІ, він двомовний (див. [hostileSky]).
         */
        fun colorFor(dead: Boolean, trust: LocationTrust?): String = when {
            trust == LocationTrust.SPOOFED -> "#EF4444"
            dead || trust == LocationTrust.JAMMED -> "#F87171"
            trust == LocationTrust.DEGRADED -> "#F59E0B"
            trust == LocationTrust.TRUSTED -> "#38BDF8"
            else -> "#64748B"
        }

        /**
         * Вороже небо: глушіння, підміна або хід по інерції. DEGRADED сюди НЕ
         * входить — слабка точність є станом приладу, а не заходом у зону, і
         * фарбувати нею пів маршруту означало б знецінити корал там, де він
         * справді щось каже.
         */
        fun hostileSky(dead: Boolean, trust: LocationTrust?): Boolean =
            dead || trust == LocationTrust.JAMMED || trust == LocationTrust.SPOOFED

        private const val MAX_POINTS = 600
        private const val MIN_STEP_M = 2.0

        /**
         * Поріг руху — той самий, що віддає курс шеврону
         * (`HEADING_FLOOR_MPS`, MapViewModel.kt).
         */
        const val MOVING_FLOOR_MPS = 0.9f

        /** Крок крихти не дрібніший за третину власної похибки фікса. */
        const val STEP_SIGMA_SHARE = 0.35

        /**
         * Above this, a single crumb-to-crumb step is a teleport (a
         * reacquisition snap, a resume from background), not a travelled path,
         * and the trail breaks rather than drawing a line the operator never
         * walked. Set generously — well past even highway speed across a
         * multi-fix gap — so only genuine discontinuities break the trail.
         */
        private const val MAX_SEGMENT_M = 120.0
        private const val REBUILD_MIN_MS = 1_500L
        // Слід — нитка позаду, а не другий сюжет: читається, коли його
        // шукають, і ніколи не сперечається з точкою за увагу.
        private const val MIN_ALPHA = 0.08f
        private const val MAX_ALPHA = 0.50f
    }
}
