package local.phantom.companion.core.sensor.nav

/**
 * Коридор, уже піднятий у памʼять, — щоб перебудова не платила за нього вдруге.
 *
 * Заміряно 29.08 (`RoadMeshDecodeSpeedTest`, JVM, 3000 доріг): розбір рядків із
 * бази 24 мс + побудова графа 53 мс = **77 мс підготовки проти 24 мс самого
 * пошуку**. Плюс до цього — запит до SQLite, який тут не рахований. А
 * `offlineMeshRoute` будував граф НАНОВО на кожен виклик, зокрема на кожну
 * перебудову при сході з маршруту, хоча коридор при цьому майже той самий.
 *
 * Тобто оптимізувати A* не мало сенсу: три чверті ціни лежали не в ньому.
 *
 * Правила влучання свідомо суворі:
 *  - **вміщення, не збіг**: граф, зібраний на більший прямокутник, законно
 *    обслуговує менший — зайві дороги коштують трохи пошуку, але не правди;
 *  - **ревізія сховища**: щойно в мережу лягли нові тайли, старий граф їх не
 *    знає, і віддавати його означало б вести людину по вчорашній карті;
 *  - **вік**: навіть без записів кеш не живе вічно.
 */
class CorridorGraphCache(
    private val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private class Entry(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
        val revision: Long,
        val atMs: Long,
        val graph: RoadGraph,
    )

    @Volatile
    private var entry: Entry? = null

    /** Скільки разів коридор віддано з памʼяті — для чесного звіту, не для UI. */
    @Volatile
    var hits: Long = 0L
        private set

    @Volatile
    var misses: Long = 0L
        private set

    /**
     * Граф для цього прямокутника: із памʼяті, якщо можна, інакше [load].
     * null від [load] не кешується — «не знаю» не має ставати відповіддю.
     */
    fun graphFor(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
        revision: Long,
        load: () -> RoadGraph?,
    ): RoadGraph? {
        val e = entry
        val now = nowMs()
        if (e != null &&
            e.revision == revision &&
            now - e.atMs <= maxAgeMs &&
            minLat >= e.minLat && minLon >= e.minLon &&
            maxLat <= e.maxLat && maxLon <= e.maxLon
        ) {
            hits++
            return e.graph
        }
        misses++
        val fresh = load() ?: return null
        entry = Entry(minLat, minLon, maxLat, maxLon, revision, now, fresh)
        return fresh
    }

    /** Забути все — після імпорту пакета доріг чи очищення сховища. */
    fun clear() {
        entry = null
    }

    private companion object {
        /**
         * Пʼять хвилин. Довша поїздка все одно вийде за межі прямокутника й
         * забере свіжий граф; коротша — не мусить платити за це двічі.
         */
        const val DEFAULT_MAX_AGE_MS = 5 * 60_000L
    }
}
