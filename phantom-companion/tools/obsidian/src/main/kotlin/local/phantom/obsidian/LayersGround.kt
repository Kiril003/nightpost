package local.phantom.obsidian

/**
 * Земля: тло, угіддя, зони, вода, аеродроми, будівлі.
 *
 * Порядок у списку — це і є порядок малювання, і він не випадковий: житлова
 * зона лягає ПІД ліс і поле (район не має ховати гай, який у ньому росте),
 * вода — над усією сушею, будівлі — під дорогами (як у Apple/Google; чужий
 * Liberty кладе їх зверху, і в щільному кварталі будинки з'їдають мережу).
 *
 * Мінзуми — це «нема шуму на дрібному масштабі»: на z4 світ має складатися з
 * води, суші, лісу й кордонів, і більше ні з чого.
 */
internal fun groundLayers(ink: CartoInk): List<Json.Obj> = buildList {

    add(
        layer(
            id = ID_GROUND,
            type = "background",
            paint = jobj("background-color" to jstr(ink.ground)),
        ),
    )

    add(zone("obsidian-landuse-residential", 8, ink.zoneResidential, "residential", "suburb", "neighbourhood", "quarter"))
    add(zone("obsidian-landuse-civic", 12, ink.zoneCivic, "hospital", "school", "university", "college", "kindergarten", "stadium", "cemetery"))
    add(zone("obsidian-landuse-industrial", 10, ink.zoneIndustrial, "industrial", "commercial", "retail", "garages", "quarry", "railway"))

    add(
        layer(
            id = "obsidian-park",
            type = "fill",
            sourceLayer = SL_PARK,
            minzoom = 7,
            paint = jobj("fill-color" to jstr(ink.landPark), "fill-opacity" to jnum(1)),
        ),
    )

    add(cover("obsidian-landcover-wood", 4, ink.landForest, "wood", "forest"))
    add(cover("obsidian-landcover-grass", 7, ink.landGrass, "grass", "meadow"))
    // Україна — країна полів. У чужому стилі шару для farmland немає взагалі,
    // і саме тому знімок села виходив бежевою пусткою з однією дорогою.
    add(cover("obsidian-landcover-farmland", 6, ink.landFarmland, "farmland"))
    add(cover("obsidian-landcover-rock", 7, ink.landRock, "rock"))
    add(cover("obsidian-landcover-sand", 6, ink.landSand, "sand"))
    add(cover("obsidian-landcover-ice", 2, ink.landIce, "ice"))
    add(cover("obsidian-landcover-wetland", 8, ink.landWetland, "wetland"))
    add(
        layer(
            id = "obsidian-landcover-wetland-edge",
            type = "line",
            sourceLayer = SL_LANDCOVER,
            minzoom = 10,
            filter = classIn("wetland"),
            paint = jobj(
                "line-color" to jstr(ink.wetlandHatch),
                "line-dasharray" to dashRamp(2.0, 3.6, listOf(13.0, 16.0)) { curveAt(WETLAND_EDGE, it) },
                "line-width" to zoomRamp(1.2, WETLAND_EDGE),
            ),
        ),
    )

    addAll(waterLayers(ink))
    addAll(aerowayLayers(ink))
    addAll(buildingLayers(ink))
}

// Криві ширин пунктирних ліній названі навмисно: [dashRamp] рахує штрих із тієї
// самої кривої, тож пунктир не роз'їдеться з лінією, коли поправлять одну з них.
private val WETLAND_EDGE = listOf<Pair<Number, Number>>(10 to 0.6, 16 to 1.4)
private val INTERMITTENT_WIDTH = listOf<Pair<Number, Number>>(9 to 0.5, 14 to 1.4, 18 to 3.6)

private fun zone(id: String, minzoom: Int, color: String, vararg classes: String) = layer(
    id = id,
    type = "fill",
    sourceLayer = SL_LANDUSE,
    minzoom = minzoom,
    filter = classIn(*classes),
    paint = jobj("fill-color" to jstr(color), "fill-opacity" to jnum(1)),
)

/**
 * Непрозорість рівно 1 — і це не косметика.
 *
 * Чужий стиль малює ліс на 0.4, траву на 0.3: числа підібрані під бежеве тло,
 * де 40 % зеленого ще читаються. Над нічною землею це означає, що ліс проходить
 * дві п'ятих шляху від чорного до зеленого, тобто його нема. Земля має свій
 * колір на повну; напівпрозорість лишається тому, що лежить ПОВЕРХ неї.
 */
private fun cover(id: String, minzoom: Int, color: String, vararg classes: String) = layer(
    id = id,
    type = "fill",
    sourceLayer = SL_LANDCOVER,
    minzoom = minzoom,
    filter = classIn(*classes),
    paint = jobj("fill-color" to jstr(color), "fill-opacity" to jnum(1)),
)

private fun waterLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-water",
        type = "fill",
        sourceLayer = SL_WATER,
        filter = neq(get("intermittent"), 1),
        paint = jobj(
            "fill-color" to jarr(
                jstr("match"), get("class"),
                jarr(jstr("ocean")), jstr(ink.waterDeep),
                jstr(ink.water),
            ),
            "fill-opacity" to jnum(1),
        ),
    ),
    // «Ця синя пляма — справді вода?» Пересихаючий ставок несе intermittent=1 і
    // не має права виглядати як озеро.
    layer(
        id = "obsidian-water-intermittent",
        type = "fill",
        sourceLayer = SL_WATER,
        minzoom = 6,
        filter = eq(get("intermittent"), 1),
        paint = jobj("fill-color" to jstr(ink.waterIntermittent), "fill-opacity" to jnum(0.72)),
    ),
    // Глибина без градієнта-в-заливці, якого style-spec не має: світліший обід
    // уздовж берега дає око «мілко тут, глибоко там» одним шаром.
    layer(
        id = "obsidian-water-shore",
        type = "line",
        sourceLayer = SL_WATER,
        minzoom = 10,
        paint = jobj(
            "line-color" to jstr(ink.waterShore),
            "line-opacity" to jnum(0.55),
            "line-width" to zoomRamp(1.3, listOf(10 to 0.4, 14 to 1.0, 18 to 2.4)),
        ),
    ),
    layer(
        id = "obsidian-waterway",
        type = "line",
        sourceLayer = SL_WATERWAY,
        minzoom = 7,
        filter = all(neq(get("intermittent"), 1), classIn("river", "canal", "stream", "ditch", "drain")),
        layout = jobj("line-cap" to jstr("round"), "line-join" to jstr("round")),
        paint = jobj(
            "line-color" to jstr(ink.waterway),
            // Річка й струмок мають різні ширини, але `interpolate` мусить
            // лишатись зовні: вираз зуму, схований у `match`, style-spec
            // відкидає разом з усім шаром.
            "line-width" to interpolate(
                1.35,
                zoom(),
                listOf<Pair<Number, Pair<Double, Double>>>(
                    7.0 to (0.6 to 0.0),
                    11.5 to (1.68 to 0.0),
                    12.0 to (1.8 to 0.5),
                    16.0 to (5.0 to 2.0),
                    20.0 to (14.0 to 6.0),
                ).map { (z, w) ->
                    z to jarr(
                        jstr("match"), get("class"),
                        jarr(jstr("river"), jstr("canal")), jnum(w.first),
                        jnum(w.second),
                    )
                },
            ),
        ),
    ),
    layer(
        id = "obsidian-waterway-intermittent",
        type = "line",
        sourceLayer = SL_WATERWAY,
        minzoom = 9,
        filter = eq(get("intermittent"), 1),
        paint = jobj(
            "line-color" to jstr(ink.waterway),
            "line-opacity" to jnum(0.7),
            "line-dasharray" to dashRamp(4.0, 3.4, listOf(12.0, 16.0)) { curveAt(INTERMITTENT_WIDTH, it) },
            "line-width" to zoomRamp(1.3, INTERMITTENT_WIDTH),
        ),
    ),
)

private fun aerowayLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-aeroway-area",
        type = "fill",
        sourceLayer = SL_AEROWAY,
        minzoom = 10,
        filter = all(eq(geometryType(), "Polygon"), classIn("apron", "runway", "taxiway", "helipad")),
        paint = jobj("fill-color" to jstr(ink.apron), "fill-opacity" to jnum(1)),
    ),
    layer(
        id = "obsidian-aeroway-runway",
        type = "line",
        sourceLayer = SL_AEROWAY,
        minzoom = 9,
        filter = all(eq(geometryType(), "LineString"), classIn("runway")),
        paint = jobj(
            "line-color" to jstr(ink.runway),
            "line-width" to zoomRamp(1.4, listOf(9 to 1.0, 12 to 3.0, 16 to 14.0, 20 to 60.0)),
        ),
    ),
    layer(
        id = "obsidian-aeroway-taxiway",
        type = "line",
        sourceLayer = SL_AEROWAY,
        minzoom = 11,
        filter = all(eq(geometryType(), "LineString"), classIn("taxiway")),
        paint = jobj(
            "line-color" to jstr(ink.taxiway),
            "line-width" to zoomRamp(1.4, listOf(11 to 0.5, 14 to 1.6, 18 to 8.0)),
        ),
    ),
)

/**
 * Плаский слід будівлі + зсунута тінь + ребро.
 *
 * Об'єм (fill-extrusion) сюди свідомо не входить: він лишається платною
 * контрибуцією `installBuildingExtrusions`, яка вставляється під перший
 * символьний шар цього ж стилю.
 */
private fun buildingLayers(ink: CartoInk): List<Json.Obj> = listOf(
    layer(
        id = "obsidian-building-shadow",
        type = "fill",
        sourceLayer = SL_BUILDING,
        minzoom = 14,
        paint = jobj(
            "fill-color" to jstr(ink.buildingShadow),
            "fill-translate" to jarr(jnum(1.5), jnum(1.5)),
            "fill-translate-anchor" to jstr("viewport"),
            "fill-opacity" to zoomRamp(null, listOf(14 to 0, 15.5 to 1)),
        ),
    ),
    layer(
        id = ID_BUILDING,
        type = "fill",
        sourceLayer = SL_BUILDING,
        minzoom = 13,
        paint = jobj(
            "fill-color" to jstr(ink.building),
            "fill-opacity" to zoomRamp(null, listOf(13 to 0, 14.5 to 1)),
        ),
    ),
    layer(
        id = "obsidian-building-edge",
        type = "line",
        sourceLayer = SL_BUILDING,
        minzoom = 16,
        paint = jobj(
            "line-color" to jstr(ink.buildingEdge),
            "line-opacity" to zoomRamp(null, listOf(16 to 0, 17 to 0.8)),
            "line-width" to zoomRamp(1.2, listOf(16 to 0.4, 20 to 1.2)),
        ),
    ),
)
