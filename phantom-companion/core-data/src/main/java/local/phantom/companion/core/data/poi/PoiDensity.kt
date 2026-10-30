package local.phantom.companion.core.data.poi

import kotlin.math.cos

/**
 * How many pins a viewport may carry, and which ones survive when there are
 * more. Pure: no DAO, no network, no Android — see PoiDensityTest.
 */
object PoiDensity {

    /** Wider view, fewer pins: a z13 sweep is a district, not a street. */
    fun capForZoom(zoom: Double): Int = when {
        zoom < 14.0 -> 25
        zoom < 15.0 -> 40
        zoom < 16.0 -> 60
        else -> 90
    }

    /** 0 лишається першим, 2 вибуває першим. Ярус — [PlaceTiers], не свій список. */
    fun salience(poi: PoiEntity): Int = PlaceTiers.of(poi).ordinal

    /**
     * Піни на скло: безіменні геть, дублі зшито, а понад [cap] — кадр, який
     * відповідає «ЩО ТУТ Є», а не «де двадцята за близькістю аптека».
     *
     * ЧОМУ НЕ «ВІДСОРТУВАТИ Й ВЗЯТИ ПЕРШІ N». Саме це тут і стояло, і воно
     * ламається не на порядку важливості, а на самому «взяти N»: у корпусі
     * країни роди розподілені важким хвостом, тож перші N — це завжди
     * НАЙЧАСТІШИЙ рід. Заміряно 17.09 на 300 найщільніших кадрах:
     *
     *   z13 (стеля 25): на склі 5 різних родів, найбільша купа одного роду 12,
     *                   води — 0 із 3 917 наявних у тих кадрах;
     *   z14 (стеля 40): 7 родів, купа 17, води 0 із 1 350.
     *
     * Обидві половини скарги власника в одному числі: двадцять однакових
     * дисків (засмічення) і жодного джерела (порожнеча). Переставити ярус
     * місцями цього НЕ лікує — з правильним ярусом виходить 20 аптек замість
     * 15 АЗС, і вода видна в 60% кадрів замість 100%.
     *
     * ЗАКОН: кадр набирається КОЛОМ по родах — спершу найближче з кожного
     * роду, потім друге найближче з кожного, і так поки є стеля. Роди йдуть за
     * ярусом, а в межах ярусу — за близькістю свого найближчого. Тож перше
     * коло і є відповіддю «що тут є»: одна аптека, одне джерело, одна лікарня,
     * одна АЗС. Те саме вимірювання після зміни: z13 — 25 родів, купа 1, вода
     * видна в 275 кадрах із 275; z14 — 39 родів, купа 2, 235 із 235. Жоден
     * критичний РІД, наявний у кадрі, більше не зникає: 100% проти 28%.
     *
     * Порядок ВИДАЧІ лишається «доречніше спершу» (ярус, далі близькість) — на
     * нього спирається `POIOverlay.labelCarriers`, обираючи з однойменних
     * близнюків носія підпису. Колом набирається СКЛАД кадру, не його порядок.
     */
    fun rank(pois: List<PoiEntity>, centerLat: Double, centerLon: Double, cap: Int): List<PoiEntity> {
        if (cap <= 0) return emptyList()
        // Тут сходяться всі шляхи, якими шпилька потрапляє на мапу — кеш,
        // диск, жива відповідь, — тож зшивати дублі треба саме тут, а не в
        // кожному з них. Раніше стояв `distinctBy { it.id }`, і одна аптека
        // малювалась двома шпильками: у довідника id виведений з `ref`, у
        // живої відповіді — сирий номер OSM, тобто два різні ключі на одне
        // місце. [OfflinePoiSearch.collapsePinsByRef] звужує за самим `ref`, а
        // де його нема — за id, як і раніше.
        val named = OfflinePoiSearch.collapsePinsByRef(
            pois.filter {
                it.name.isNotBlank() &&
                    !it.name.equals("unknown", ignoreCase = true) &&
                    !PlaceTiers.hasOwnLayer(it)
            },
        )
        if (named.size <= cap) return named

        val byKind = LinkedHashMap<String, MutableList<PoiEntity>>()
        for (poi in named) byKind.getOrPut(PlaceTiers.kindOf(poi)) { ArrayList() } += poi
        val queues = byKind.values
            .onEach { it.sortBy { poi -> squareDistance(poi, centerLat, centerLon) } }
            .sortedWith(
                compareBy(
                    { salience(it.first()) },
                    { squareDistance(it.first(), centerLat, centerLon) },
                ),
            )

        val picked = ArrayList<PoiEntity>(cap)
        var round = 0
        // Найдовша черга — стеля обходів: далі брати вже нема звідки, і без
        // цієї межі цикл крутився б, поки `picked` добере стелю, якої може
        // не бути (родів менше, ніж стеля).
        val deepest = queues.maxOf { it.size }
        while (picked.size < cap && round < deepest) {
            for (queue in queues) {
                if (round < queue.size) {
                    picked += queue[round]
                    if (picked.size == cap) break
                }
            }
            round++
        }
        return picked.sortedWith(
            compareBy({ salience(it) }, { squareDistance(it, centerLat, centerLon) }),
        )
    }

    private fun squareDistance(poi: PoiEntity, lat: Double, lon: Double): Double {
        val dLat = poi.lat - lat
        val dLon = (poi.lon - lon) * cos(Math.toRadians(lat))
        return dLat * dLat + dLon * dLon
    }
}
