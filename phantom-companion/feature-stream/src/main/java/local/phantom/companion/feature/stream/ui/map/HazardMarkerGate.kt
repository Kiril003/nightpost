package local.phantom.companion.feature.stream.ui.map

import local.phantom.companion.core.geo.cameras.HazardType
import local.phantom.companion.core.geo.cameras.SafetyHazard

/**
 * ЩО З ЗАГРОЗ ЗАСЛУГОВУЄ НА ЗНАЧОК — чиста функція над зумом і маршрутом.
 *
 * Привід: над спальним кварталом на z15 екран накривало кількадесят коричневих
 * «Воріт». Кожні з них — правда з OSM (`barrier=gate` на вʼїзді у двір), і
 * жодні з них не були відповіддю на питання, яке оператор ставив мапі. Правда,
 * якої стільки, що крізь неї не видно міста, працює як брехня.
 *
 * Тому шлагбаум і ворота — це клас, який мусить заслужити місце:
 *  · зблизька ([BARRIER_MIN_ZOOM]) — видно двір, значок доречний;
 *  · АБО біля коридору активного маршруту ([ROUTE_CORRIDOR_M]) — тоді ворота
 *    це те, у що оператор зараз упреться, на будь-якому зумі;
 *  · один значок на скупчення ([CLUSTER_M]) — пʼятеро воріт одного двору це
 *    одні ворота;
 *  · і не більше [MAX_BARRIERS] на екран, найближчі до фокуса.
 *
 * Камери швидкості й ручні мітки (патруль, блокпост, ДТП) сюди НЕ входять:
 *  їх одиниці, вони тактичні, і вони лишаються видимими як були.
 *
 * Проріджується САМЕ клас воріт ([HazardType.GATE]), не всі барʼєри. Поки
 * ворота й блокпост були однією армою, це правило зрізало з екрана справжні
 * КПП разом із дворовими шлагбаумами — тобто ховало те, заради чого воно й
 * писалось.
 */
internal object HazardGate {

    /** Зблизька видно сам двір — тоді й ворота у ньому мають сенс. */
    const val BARRIER_MIN_ZOOM = 16.5

    /** Ширина коридору, у якому шлагбаум — це перешкода, а не пейзаж. */
    const val ROUTE_CORRIDOR_M = 150.0

    /** Ближче за це — одні й ті самі ворота, показані двічі. */
    const val CLUSTER_M = 30.0

    /** Стеля значків-воріт на екран. */
    const val MAX_BARRIERS = 12
}

/**
 * [zoom] — поточний зум; [corridor] — точки активного маршруту (порожньо, коли
 * ніхто нікуди не веде); [focus] — від чого рахувати «найближчі»: оператор,
 * інакше центр вьюпорта. Порядок операцій не косметичний: спершу сортуємо за
 * відстанню, потім проріджуємо — інакше представником скупчення став би
 * випадковий його член, а не найближчий.
 */
internal fun gateHazards(
    hazards: List<SafetyHazard>,
    zoom: Double,
    corridor: List<Pair<Double, Double>> = emptyList(),
    focus: Pair<Double, Double>? = null,
): List<SafetyHazard> {
    val (barriers, tactical) = hazards.partition { it.type == HazardType.GATE }
    if (barriers.isEmpty()) return tactical

    val closeEnough = zoom >= HazardGate.BARRIER_MIN_ZOOM
    val relevant = if (closeEnough) barriers else barriers.filter { onCorridor(it, corridor) }
    if (relevant.isEmpty()) return tactical

    val ordered = focus?.let { f ->
        relevant.sortedBy { haversineMeters(it.lat to it.lon, f) }
    } ?: relevant

    val kept = ArrayList<SafetyHazard>(HazardGate.MAX_BARRIERS)
    for (h in ordered) {
        if (kept.size >= HazardGate.MAX_BARRIERS) break
        val duplicate = kept.any {
            haversineMeters(it.lat to it.lon, h.lat to h.lon) < HazardGate.CLUSTER_M
        }
        if (!duplicate) kept += h
    }
    return tactical + kept
}

/**
 * Прорідити маршрут до «хребта» — вузлів не ближче [HazardGate.ROUTE_CORRIDOR_M]
 * один до одного.
 *
 * Маршрут через місто везе тисячі вершин (на закруті — кожні кілька метрів), і
 * перевіряти кожні ворота проти кожної вершини означало б мільйони гаверсинів
 * на одну зупинку жесту. Крок хребта дорівнює радіусу коридору, тож смуга
 * лишається суцільною.
 */
internal fun corridorSpine(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
    if (points.isEmpty()) return points
    val spine = ArrayList<Pair<Double, Double>>()
    var last: Pair<Double, Double>? = null
    for (p in points) {
        if (last == null || haversineMeters(last, p) >= HazardGate.ROUTE_CORRIDOR_M) {
            spine += p
            last = p
        }
    }
    // Хвіст маршруту — теж коридор; без нього останні метри лишились би сліпими.
    points.last().takeIf { it != spine.lastOrNull() }?.let { spine += it }
    return spine
}

private fun onCorridor(h: SafetyHazard, corridor: List<Pair<Double, Double>>): Boolean {
    // Градусна відсічка перед гаверсином: 150 м — це завжди менше за 0.002°
    // широти, тож майже кожен вузол відпадає на двох відніманнях.
    val maxDeg = HazardGate.ROUTE_CORRIDOR_M / 111_320.0 * 1.5
    return corridor.any {
        kotlin.math.abs(it.first - h.lat) <= maxDeg &&
            haversineMeters(it, h.lat to h.lon) <= HazardGate.ROUTE_CORRIDOR_M
    }
}
