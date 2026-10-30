package local.phantom.companion.core.geo.metro

import local.phantom.companion.core.net.map.OverpassElement

/**
 * Overpass QL для підземного простору. Тут — тільки ТЕКСТ запиту; виконує його
 * `OverpassClient` з `:core-net`, який уже вміє дзеркала й фолбек. Дублювати
 * HTTP-клієнт заради ще одного запиту не було б за що заплатити.
 */
object IndoorOverpass {

    /**
     * Усе, що формує граф станції в радіусі [radiusM] метрів навколо точки.
     *
     * `out geom` обовʼязковий: без геометрії лінії шляхів приходять як списки
     * ідентифікаторів вузлів, яких у відповіді немає, і граф нема з чого будувати.
     */
    fun stationQuery(latDeg: Double, lonDeg: Double, radiusM: Int): String {
        require(radiusM in 1..2000) { "радіус $radiusM м не має сенсу для однієї станції" }
        val a = "around:$radiusM,$latDeg,$lonDeg"
        return """
            [out:json][timeout:25];
            (
              node["railway"="subway_entrance"]($a);
              node["highway"="elevator"]($a);
              way["highway"="elevator"]($a);
              way["highway"="steps"]($a);
              way["highway"~"^(footway|corridor|pedestrian|path)${'$'}"]($a);
              way["indoor"~"^(corridor|area|room)${'$'}"]($a);
              way["railway"="platform"]($a);
              way["public_transport"="platform"]($a);
            );
            out geom;
        """.trimIndent()
    }

    /** Лише входи — дешевий запит для підказки «вихід №3 ближче». */
    fun entrancesQuery(latDeg: Double, lonDeg: Double, radiusM: Int): String {
        require(radiusM in 1..2000) { "радіус $radiusM м не має сенсу для однієї станції" }
        return """
            [out:json][timeout:20];
            node["railway"="subway_entrance"](around:$radiusM,$latDeg,$lonDeg);
            out;
        """.trimIndent()
    }
}

/**
 * Збирання [IndoorGraph] з відповіді Overpass.
 *
 * Ключове рішення — ідентичність вершини. Overpass із `out geom` віддає
 * координати, але не ідентифікатори вузлів усередині ліній, тож спільна точка
 * двох шляхів упізнається за координатою, квантованою до 1e-7 градуса (≈1 см) —
 * рівно тієї роздільності, з якою OSM їх і зберігає, тож збіг точний, а не
 * приблизний. До координати додається РІВЕНЬ: дві точки одна над одною на
 * різних поверхах — різні вершини, і зʼєднати їх може лише ліфт, сходи чи
 * ескалатор. Без цього граф злипається у пласку кашу, і маршрут «пройдіть крізь
 * перекриття» виглядає цілком коротким.
 */
class OsmIndoorParser {

    private data class NodeKey(val latE7: Long, val lonE7: Long, val levelE3: Long)

    private val nodesByKey = LinkedHashMap<NodeKey, IndoorNode>()
    private val edges = mutableListOf<IndoorEdge>()
    private var nextId = 1L

    /** Розібрати відповідь і повернути граф. Порожній вхід дає порожній граф, не виняток. */
    fun parse(elements: List<OverpassElement>): IndoorGraph {
        nodesByKey.clear()
        edges.clear()
        nextId = 1L

        // Спершу точкові обʼєкти: входи та ліфти-вузли мають отримати свій
        // ідентифікатор ДО того, як лінії почнуть створювати вершини на тих
        // самих координатах — інакше вхід загубить `ref` і `wheelchair`.
        for (e in elements) if (isNode(e)) parseNode(e)
        for (e in elements) if (isWay(e)) parseWay(e)

        return IndoorGraph(nodesByKey.values.toList(), edges.toList())
    }

    private fun isNode(e: OverpassElement): Boolean =
        e.type == "node" || (e.type == null && e.lat != null && e.geometry == null)

    private fun isWay(e: OverpassElement): Boolean =
        (e.type == "way" || e.type == null) && !e.geometry.isNullOrEmpty()

    private fun parseNode(e: OverpassElement) {
        val tags = e.tags ?: return
        val lat = e.lat ?: return
        val lon = e.lon ?: return
        when {
            tags["railway"] == "subway_entrance" -> {
                val level = levelsOf(tags).first()
                obtainNode(lat, lon, level, IndoorNodeKind.ENTRANCE, tags, e.id)
            }

            tags["highway"] == "elevator" -> {
                // Ліфт-вузол з `level=-2;0` — це шахта, а не точка: вершина на
                // кожному обслуговуваному рівні плюс ребра між сусідніми.
                val levels = levelsOf(tags).sorted()
                val cabin = levels.map { obtainNode(lat, lon, it, IndoorNodeKind.JUNCTION, tags, e.id) }
                for (i in 0 until cabin.size - 1) {
                    edges += IndoorEdge(
                        fromId = cabin[i].id,
                        toId = cabin[i + 1].id,
                        kind = IndoorEdgeKind.ELEVATOR,
                        lengthM = 0.0,
                        wheelchair = Wheelchair.parse(tags["wheelchair"]),
                        osmId = e.id,
                    )
                }
            }
        }
    }

    private fun parseWay(e: OverpassElement) {
        val geom = e.geometry ?: return
        if (geom.size < 2) return
        val tags = e.tags ?: emptyMap()

        val isPlatform = tags["railway"] == "platform" || tags["public_transport"] == "platform"
        val closed = geom.size > 3 && geom.first().lat == geom.last().lat && geom.first().lon == geom.last().lon
        if (isPlatform && closed) {
            parsePlatformPolygon(e, tags)
            return
        }

        val kind = when {
            tags["highway"] == "elevator" -> IndoorEdgeKind.ELEVATOR
            tags["highway"] == "steps" && tags["conveying"] != null -> IndoorEdgeKind.ESCALATOR
            tags["highway"] == "steps" -> IndoorEdgeKind.STAIRS
            isPlatform -> IndoorEdgeKind.PLATFORM_ACCESS
            else -> IndoorEdgeKind.WALKWAY
        }

        val levels = levelsOf(tags)
        val startLevel = levels.first()
        val endLevel = levels.last()
        val wheelchair = Wheelchair.parse(tags["wheelchair"])
        val conveying = Conveying.parse(tags["conveying"])
        val oneway = tags["oneway"] == "yes" || tags["oneway"] == "1"

        // Довжини сегментів рахуються заздалегідь: рівень уздовж прольоту
        // інтерполюється саме по пройденому шляху, а не по номеру вершини —
        // інакше довгий пологий вихід і короткий крутий проліт отримали б
        // однаковий профіль.
        val segM = DoubleArray(geom.size - 1) { i ->
            IndoorGraph.distanceM(geom[i].lat, geom[i].lon, geom[i + 1].lat, geom[i + 1].lon)
        }
        val total = segM.sum()
        var walked = 0.0

        var prev = obtainNode(geom[0].lat, geom[0].lon, startLevel, nodeKindFor(kind), tags, e.id)
        for (i in 1 until geom.size) {
            walked += segM[i - 1]
            val f = if (total > 0.0) walked / total else 1.0
            val level = startLevel + (endLevel - startLevel) * f
            val next = obtainNode(geom[i].lat, geom[i].lon, level, nodeKindFor(kind), tags, e.id)
            if (next.id != prev.id) {
                edges += IndoorEdge(
                    fromId = prev.id,
                    toId = next.id,
                    kind = kind,
                    lengthM = segM[i - 1],
                    wheelchair = wheelchair,
                    conveying = conveying,
                    oneway = oneway,
                    osmId = e.id,
                )
            }
            prev = next
        }
    }

    /**
     * Платформа-полігон стає однією вершиною в центрі та ребрами до її ж
     * контуру. Маршрут «на платформу» має закінчуватись у точці, куди можна
     * прийти, а не на довільній вершині обведення.
     */
    private fun parsePlatformPolygon(e: OverpassElement, tags: Map<String, String>) {
        val geom = e.geometry ?: return
        val ring = geom.dropLast(1)
        if (ring.isEmpty()) return
        val level = levelsOf(tags).first()
        val cLat = ring.sumOf { it.lat } / ring.size
        val cLon = ring.sumOf { it.lon } / ring.size
        val centre = obtainNode(cLat, cLon, level, IndoorNodeKind.PLATFORM, tags, e.id)

        for (p in ring) {
            val v = obtainNode(p.lat, p.lon, level, IndoorNodeKind.JUNCTION, tags, e.id)
            if (v.id == centre.id) continue
            edges += IndoorEdge(
                fromId = v.id,
                toId = centre.id,
                kind = IndoorEdgeKind.PLATFORM_ACCESS,
                lengthM = IndoorGraph.distanceM(p.lat, p.lon, cLat, cLon),
                wheelchair = Wheelchair.parse(tags["wheelchair"]),
                osmId = e.id,
            )
        }
    }

    private fun nodeKindFor(kind: IndoorEdgeKind): IndoorNodeKind = when (kind) {
        IndoorEdgeKind.PLATFORM_ACCESS -> IndoorNodeKind.JUNCTION
        IndoorEdgeKind.WALKWAY -> IndoorNodeKind.CONCOURSE
        else -> IndoorNodeKind.JUNCTION
    }

    private fun obtainNode(
        lat: Double,
        lon: Double,
        level: Double,
        kind: IndoorNodeKind,
        tags: Map<String, String>,
        osmId: Long,
    ): IndoorNode {
        val key = NodeKey(
            latE7 = Math.round(lat * 1e7),
            lonE7 = Math.round(lon * 1e7),
            levelE3 = Math.round(level * 1e3),
        )
        nodesByKey[key]?.let { return it }
        val node = IndoorNode(
            id = nextId++,
            latDeg = lat,
            lonDeg = lon,
            level = level,
            kind = kind,
            ref = tags["ref"],
            name = tags["name"],
            wheelchair = Wheelchair.parse(tags["wheelchair"]),
            osmId = osmId,
        )
        nodesByKey[key] = node
        return node
    }

    /**
     * Рівні обʼєкта за тегами.
     *
     * `level` має пріоритет над `layer`: `layer` описує порядок перетину
     * геометрії на площині, а не поверх, і плутати їх — класична помилка, від
     * якої перехід опиняється на одному рівні з платформою. Якщо `level`
     * відсутній, беремо `layer` — для `tunnel=yes` це найкраще наближення, яке
     * взагалі є в даних.
     *
     * Підтримано: одне значення (`-1`), дробове (`-1.5`), перелік через `;`
     * (`-2;0` — ліфт або проліт між рівнями). Діапазон через дефіс (`-2--1`)
     * свідомо НЕ підтримано: він неоднозначний з відʼємними числами, і краще
     * повернути перший розібраний рівень, ніж вгадати неправильно.
     */
    internal fun levelsOf(tags: Map<String, String>): List<Double> {
        val raw = tags["level"] ?: tags["layer"]
        val parsed = raw
            ?.split(';')
            ?.mapNotNull { it.trim().toDoubleOrNull() }
            ?.takeIf { it.isNotEmpty() }
        if (parsed != null) return parsed
        // Тунель без жодного тегу рівня — це підземний обʼєкт, і нуль (вулиця)
        // для нього неправильний за визначенням.
        return if (tags["tunnel"] == "yes") listOf(-1.0) else listOf(0.0)
    }
}
