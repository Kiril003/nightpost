package local.phantom.companion.feature.stream.ui.map.world

import kotlin.math.cos
import kotlin.math.sqrt

/** Один обʼєкт світу, як його бачить розподіл: без малювання, лише факти. */
internal data class WorldObject(
    val id: String,
    val kind: WorldKind,
    val lat: Double,
    val lon: Double,
    val state: WorldState? = null,
    /** Мета маршруту — те, куди людина йде зараз. */
    val target: Boolean = false,
    /** Лежить на коридорі активного маршруту. */
    val onRoute: Boolean = false,
)

/**
 * Рішення для одного обʼєкта на склі. [rank] — ключ сортування шару (менше —
 * важливіше); [opacity] — приглушення (зачинено, старе, не на твоєму шляху);
 * [accent] — бурштин: мета або укриття під тривогою; [breathe] — рух стану.
 */
internal data class WorldPlaced(
    val obj: WorldObject,
    val rank: Int,
    val opacity: Float,
    val accent: Boolean,
    val breathe: Boolean,
)

/**
 * Скільки й що видно. Одна функція на весь світ, чиста, без движка:
 *  1. вид мусить заслужити масштаб ([WorldKind.minZoom]); мета, безпека на
 *     шляху і ворота на шляху — заслуговують завжди;
 *  2. порядок — ранг: безпека > мета > люди > потреби > решта; усередині рангу
 *     спершу те, що на шляху, потім найближче до фокуса;
 *  3. жадібно: обʼєкт стає, лише якщо до кожного вже поставленого не ближче
 *     за [MIN_GAP_DP] на склі — налазити фігурка не має права;
 *  4. стеля кадру за масштабом ([capFor]).
 */
internal fun placeWorld(
    objects: List<WorldObject>,
    zoom: Double,
    focus: Pair<Double, Double>?,
    routeActive: Boolean,
): List<WorldPlaced> {
    val admitted = objects.filter { o ->
        zoom >= o.kind.minZoom || o.target || (o.onRoute && o.kind.tier != WorldTier.NEED)
    }
    val ordered = admitted.sortedWith(
        compareBy<WorldObject>({ tierOf(it).ordinal }, { !it.onRoute }, { focus?.let { f -> metres(f, it) } ?: 0.0 }),
    )
    val gapM = MIN_GAP_DP * local.phantom.companion.feature.stream.ui.map.metersPerDp(focus?.first ?: ordered.firstOrNull()?.lat ?: 0.0, zoom)
    val cap = capFor(zoom)
    val placed = ArrayList<WorldPlaced>()
    for (o in ordered) {
        if (placed.size >= cap) break
        if (placed.any { metres(it.obj.lat to it.obj.lon, o) < gapM }) continue
        val tier = tierOf(o)
        placed += WorldPlaced(
            obj = o,
            rank = tier.ordinal * RANK_STRIDE + placed.size,
            opacity = opacityOf(o, tier, routeActive),
            accent = o.target || o.state == WorldState.ALERT,
            breathe = o.kind.animates && o.state == WorldState.ALERT && zoom >= BREATH_MIN_ZOOM,
        )
    }
    return placed
}

internal fun tierOf(o: WorldObject): WorldTier = when {
    o.kind.tier == WorldTier.SAFETY -> WorldTier.SAFETY
    o.target -> WorldTier.TARGET
    else -> o.kind.tier
}

/** Приглушення множиться: зачинена аптека не на твоєму шляху — ще тихіша. */
internal fun opacityOf(o: WorldObject, tier: WorldTier, routeActive: Boolean): Float {
    var a = 1f
    if (o.state == WorldState.CLOSED || o.state == WorldState.STALE) a *= QUIET
    if (routeActive && !o.onRoute && !o.target && tier != WorldTier.SAFETY) a *= OFF_ROUTE
    return a
}

/** Стеля кадру: далеко — лише найважливіше, зблизька — квартал цілком. */
internal fun capFor(zoom: Double): Int = when {
    zoom < 14.0 -> 8
    zoom < 16.0 -> 16
    else -> 28
}

internal const val MIN_GAP_DP = 40.0
internal const val BREATH_MIN_ZOOM = 15.0
internal const val QUIET = 0.5f
internal const val OFF_ROUTE = 0.55f
private const val RANK_STRIDE = 1000

private const val EARTH_M = 6_371_000.0

private fun metres(a: Pair<Double, Double>, b: WorldObject): Double {
    val dLat = Math.toRadians(b.lat - a.first)
    val dLon = Math.toRadians(b.lon - a.second) * cos(Math.toRadians((a.first + b.lat) / 2))
    return EARTH_M * sqrt(dLat * dLat + dLon * dLon)
}
