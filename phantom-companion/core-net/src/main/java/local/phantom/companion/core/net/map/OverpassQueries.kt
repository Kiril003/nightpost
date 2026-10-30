package local.phantom.companion.core.net.map

/**
 * Побудова Overpass QL окремо від транспорту — щоб форму запиту можна було
 * довести тестом, не піднімаючи сокет. Клієнт лишається тонким: дзеркала,
 * бюджет, розбір відповіді.
 */
internal object OverpassQueries {

    /**
     * Стеля вершин у клаузі `around:`.
     *
     * Публічні інстанси віддають 504 на багатовершинних клаузах, а маршрут
     * через місто — це легко 500 точок полілінії. Значення збігається з тим,
     * що вже роками працює у `fetchAlong`.
     */
    const val MAX_AROUND_VERTICES: Int = 48

    /** Рівномірне прорідження полілінії до [maxSamples] точок. */
    fun samplePolyline(
        points: List<Pair<Double, Double>>,
        maxSamples: Int = MAX_AROUND_VERTICES,
    ): List<Pair<Double, Double>> {
        if (maxSamples <= 0) return emptyList()
        if (points.size <= maxSamples) return points
        val step = points.size.toDouble() / maxSamples
        return (0 until maxSamples).map { i -> points[(i * step).toInt()] }
    }

    /** Прорідена полілінія як список координат для `around:`. */
    fun aroundCoords(points: List<Pair<Double, Double>>): String =
        samplePolyline(points).joinToString(",") { (lat, lon) -> "$lat,$lon" }

    /**
     * Що мапа питає в світу для видимої коробки.
     *
     * **15.09 — рядок `historic` тут зʼявився вперше, і це вада, а не
     * доповнення.** Власник спитав «а історичні памʼятки?», і відповідь
     * знайшлась саме тут: їх не було в запиті ЖОДНОГО РАЗУ. Тобто замок,
     * памʼятник, руїни й меморіал не могли зʼявитись на мапі ніколи й ніде —
     * не тому, що даних немає (в OSM вони є скрізь), а тому, що їх ніхто не
     * просив. Мовчазна порожнеча, яку неможливо відрізнити від «тут нічого
     * немає».
     *
     * Поруч дописано `man_made` (вежі/маяки як орієнтири) і `tourism=artwork`:
     * памʼятний знак у OSM так само часто лежить під ним, і брати одне без
     * іншого означало б показувати половину памʼяток і називати це всіма.
     */
    fun pois(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        val box = "$minLat,$minLon,$maxLat,$maxLon"
        return """
            [out:json][timeout:15];
            (
              node[amenity~"cafe|restaurant|bar|pub|fast_food|pharmacy|bank|atm|bureau_de_change|parking|hospital|clinic|dentist|veterinary|fuel|charging_station|post_office|parcel_locker|police|fire_station|townhall|courthouse|library|marketplace|place_of_worship|fountain|drinking_water|shelter|taxi|bus_station"]($box);
              node[shop~"supermarket|convenience|bakery|chemist|hairdresser|clothes|shoes|electronics|mobile_phone|optician|books|stationery|hardware|car_repair|car_parts|car_wash|pet|jewelry|florist|mall|department_store"]($box);
              node[tourism~"hotel|hostel|guest_house|motel|museum|gallery|viewpoint|attraction|information|artwork"]($box);
              node[historic~"monument|memorial|castle|ruins|archaeological_site|wayside_cross|wayside_shrine|city_gate|fort|tower|manor|church|building|boundary_stone|milestone"]($box);
              node[man_made~"tower|lighthouse|obelisk|watermill|windmill"]($box);
              node[leisure~"park|playground|sports_centre|fitness_centre|pitch|swimming_pool|garden|stadium"]($box);
              node[highway~"bus_stop|speed_camera|platform"]($box);
              node[public_transport~"platform|stop_position|station"]($box);
              node[barrier~"lift_gate|gate|bollard|toll_booth|block|sliding_gate|swing_gate"]($box);
              node[entrance]($box);
              node[railway~"station|halt|subway_entrance|tram_stop"]($box);
            );
            out;
        """.trimIndent()
    }

    /**
     * Пошук за назвою. Повертає `null`, якщо після очищення від
     * регулярно-значущих символів від запиту нічого не лишилось — інакше
     * порожній шаблон збігся б з усім у радіусі.
     */
    fun byName(name: String, lat: Double, lon: Double, radiusMeters: Int): String? {
        val esc = name.trim()
            .replace("\\", " ")
            .replace("\"", " ")
            .replace(Regex("[\\[\\](){}^$]"), " ")
            .trim()
        if (esc.isBlank()) return null
        val around = "(around:$radiusMeters,$lat,$lon)"
        return """
            [out:json][timeout:20];
            (
              nwr["name"~"$esc",i]$around;
              nwr["brand"~"$esc",i]$around;
              nwr["operator"~"$esc",i]$around;
            );
            out center 100;
        """.trimIndent()
    }

    /**
     * Розбір «або» по ключах живе ТУТ, а не в котромусь із викликачів.
     *
     * `node[a=b|c=d]` — не «або-або»: Overpass відповідає HTTP 400
     * `parse error: ']' expected - '|' found`, і на склі це виглядає як
     * «Джерело мовчить», тобто як обрив мережі. Кожен викликач, що складав
     * такий рядок сам, отримував мертвий чіп без жодної скарги.
     */
    fun around(lat: Double, lon: Double, radiusMeters: Int, selector: String): String {
        val around = "(around:$radiusMeters,$lat,$lon)"
        val clauses = TagSelector.parts(selector).ifEmpty { listOf(selector) }
            .joinToString(separator = "\n              ") { "node[$it]$around;\n              way[$it]$around;" }
        return """
            [out:json][timeout:20];
            (
              $clauses
            );
            out center 80;
        """.trimIndent()
    }

    /** Кілька селекторів одним запитом — один похід у мережу замість N. */
    fun aroundAny(
        lat: Double,
        lon: Double,
        radiusMeters: Int,
        selectors: List<String>,
        limit: Int,
    ): String {
        val clauses = selectors.joinToString(separator = "") { sel ->
            "node[$sel](around:$radiusMeters,$lat,$lon);way[$sel](around:$radiusMeters,$lat,$lon);"
        }
        return "[out:json][timeout:20];($clauses);out center $limit;"
    }

    fun roads(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String {
        val selector = "highway~\"^(motorway|motorway_link|trunk|trunk_link|primary|primary_link|" +
            "secondary|secondary_link|tertiary|tertiary_link|unclassified|residential|" +
            "living_street|service|pedestrian|footway|path|cycleway|track)$\""
        return """
            [out:json][timeout:20];
            way[$selector]($minLat,$minLon,$maxLat,$maxLon);
            out geom;
        """.trimIndent()
    }

    fun along(polyline: List<Pair<Double, Double>>, radiusMeters: Int, selector: String): String {
        val coords = aroundCoords(polyline)
        val around = "(around:$radiusMeters,$coords)"
        val clauses = TagSelector.parts(selector).ifEmpty { listOf(selector) }
            .joinToString(separator = "\n              ") { "node[$it]$around;\n              way[$it]$around;" }
        return """
            [out:json][timeout:20];
            (
              $clauses
            );
            out center 60;
        """.trimIndent()
    }

    fun buildings(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double): String = """
        [out:json][timeout:20];
        way[building]($minLat,$minLon,$maxLat,$maxLon);
        out geom 1500;
    """.trimIndent()

    /** Стеля вузлів в одному `node(id:…)` — довший запит дзеркала відбивають. */
    const val MAX_BARRIER_NODES: Int = 24

    /**
     * Шляхи крізь вузли: `node(id:…) → way(bn:…)[highway] → out geom`.
     *
     * `bn` (by node) — рекурсія «вгору» від вузла до шляхів, що його містять.
     * `out geom` дає і вершини, і їхні id, тож вузол знаходиться у списку
     * точно, а не за збігом координат.
     */
    fun waysThroughNodes(nodeIds: List<Long>): String {
        val ids = nodeIds.distinct().take(MAX_BARRIER_NODES).joinToString(",")
        return """
            [out:json][timeout:20];
            node(id:$ids)->.b;
            way(bn.b)[highway];
            out geom 120;
        """.trimIndent()
    }

    fun corridor(
        polyline: List<Pair<Double, Double>>,
        wayRadiusMeters: Int,
        cameraRadiusMeters: Int,
    ): String {
        val coords = aroundCoords(polyline)
        return """
            [out:json][timeout:25];
            (
              way[highway](around:$wayRadiusMeters,$coords);
              node["highway"="speed_camera"](around:$cameraRadiusMeters,$coords);
              node[barrier~"lift_gate|gate|bollard|toll_booth|block|sliding_gate"](around:$cameraRadiusMeters,$coords);
            );
            out geom 600;
        """.trimIndent()
    }
}
