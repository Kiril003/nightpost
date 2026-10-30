package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory

// NAV-0 / 500-line rule — the GROUND half of the style-layer engine, split
// out of MapStyleLayers.kt verbatim: terrain relief, the tactical basemap
// restyle, and the 3D building extrusions. Same package, same `internal`
// surface — callers never moved.

/**
 * Що на мапі продається, а що ні.
 *
 * Обʼємність — рельєф, наші екструзії та Кришталеве місто — це рівень
 * «Особистий». Усе, чим мапа рятує й веде, лишається вільним на будь-якому
 * рівні. Списки не декоративні: MapDepthFirewallTest звіряє з ними реальні
 * виклики в MapCanvas, тож перенести щось із FREE у PAID мовчки не вийде.
 */
internal object MapDepth {

    val PAID_CALLS: Set<String> = setOf(
        "installHillshade",
        "installBuildingExtrusions",
        "crystalFeed.onViewport",
    )

    val FREE_CALLS: Set<String> = setOf(
        // Земля читається на будь-якому рівні. Вода, ліс, кордон і драбина
        // доріг — це те, чим мапа веде; за глибину платять окремо.
        "applyGroundCartography",
        "installGroundBackfill",
        "installGroundFacts",
        "installAlertOverlay",
        "installOperatorPin",
        "installRouteLayer",
        "installManeuverLayer",
        "installTransitLayers",
        "installPhantomLayers",
        "POIOverlay.install",
        "SavedPlacesOverlay.install",
        "VenuePinsOverlay.install",
        "installOfflinePacks",
        "installSearchPin",
        "installTrustHalo",
        // Блокпост попереду — це не глибина мапи, а причина, з якої її взяли
        // в руки. Замок його не бачить і не побачить.
        "installEventMarks",
    )
}

/**
 * Виклики, що фарбують шар і мусять отримати живу полярність мапи.
 *
 * Список не декоративний: MapThemeWiringTest звіряє з ним справжні виклики в
 * MapCanvas. Параметр із дефолтом компілюється мовчки — саме так світла
 * основа цілий день малювалася темною палітрою, і жоден тест не помітив.
 */
internal object MapTheme {

    /** Імʼя живого значення в MapCanvas — воно одне на весь файл. */
    const val LIVE = "mapIsDark"

    val THEMED_CALLS: Set<String> = setOf(
        "installGroundBackfill",
        "installGroundFacts",
        "installHillshade",
        "installNotDownloadedHatch",
        "installRouteLayer",
        "installRouteRegisterLayer",
        "installLaneBand",
        "installPhantomLayers",
        "POIOverlay.install",
        "SavedPlacesOverlay.install",
        "VenuePinsOverlay.install",
        "installBuildingExtrusions",
        "applyGroundCartography",
        "updateRouteLayer",
        "updateAltRoutes",
        "updateRouteRegisterLayer",
        "updatePhantomLayers",
        "installAlertOverlay",
        "installSearchPin",
        "updateSearchPin",
        "installTransitLayers",
        "updateTransitLayers",
        "installPackTransitLayers",
        "updatePackTransitLayers",
        "installEventMarks",
        "updateEventMarks",
        "installPeerFigures",
        "updatePeerFigures",
        "installPeerRoutes",
        "updatePeerRoutes",
        "installPeerWhere",
        "updatePeerWhere",
    )
}

// ── LEVIATHAN-3 — terrain relief (keyless AWS terrarium DEM) ─────────────

internal const val DEM_SOURCE = LOCAL_DEM_SOURCE
internal const val HILLSHADE_LAYER = "phantom-hillshade"

/**
 * Hillshade relief from the public AWS elevation tiles (terrarium encoding,
 * keyless). Inserted directly above the background so every road, label and
 * overlay stays on top — the ground gains body, nothing loses legibility.
 * Capped at z14: past street scale the DEM's 30 m posts read as blur, and
 * the layer bows out.
 *
 * Пакет рельєфу кузні внесено в стиль ще до завантаження ([applyLocalTerrain]),
 * тож джерело з цим іменем УЖЕ тут — і мережевий AWS не створюється зовсім.
 * [demMaxZoom] приходить із пакета: вище його стелі шар гасне, інакше рушій
 * розтягував би останній тайл на весь екран.
 */
internal fun installHillshade(style: Style, isDark: Boolean, demMaxZoom: Int = NETWORK_DEM_MAX_ZOOM) {
    if (style.getSource(DEM_SOURCE) == null) {
        val tiles = org.maplibre.android.style.sources.TileSet(
            "2.1.0",
            "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
        ).apply {
            encoding = "terrarium"
            minZoom = 0f
            maxZoom = 13f
        }
        style.addSource(org.maplibre.android.style.sources.RasterDemSource(DEM_SOURCE, tiles, 256))
    }
    if (style.getLayer(HILLSHADE_LAYER) == null) {
        val layer = org.maplibre.android.style.layers.HillshadeLayer(HILLSHADE_LAYER, DEM_SOURCE)
            .withProperties(
                PropertyFactory.hillshadeExaggeration(if (isDark) 0.45f else 0.35f),
                PropertyFactory.hillshadeShadowColor(
                    android.graphics.Color.parseColor(if (isDark) "#05070B" else "#8B8378"),
                ),
                PropertyFactory.hillshadeHighlightColor(
                    android.graphics.Color.parseColor(if (isDark) "#2A3140" else "#FFFFFF"),
                ),
                PropertyFactory.hillshadeAccentColor(
                    android.graphics.Color.parseColor(if (isDark) "#0B0E14" else "#A89F91"),
                ),
                PropertyFactory.hillshadeIlluminationDirection(335f),
            )
        layer.maxZoom = (demMaxZoom + 1).toFloat()
        val base = style.layers.firstOrNull()
        if (base != null) style.addLayerAbove(layer, base.id) else style.addLayer(layer)
    }
}

/** Стеля публічних тайлів AWS; пакет кузні приносить свою. */
internal const val NETWORK_DEM_MAX_ZOOM = 13

// ── Дорожня ієрархія / road hierarchy ───────────────────────────────────

/**
 * Три щаблі мережі, від найгучнішого.
 *
 * Ранг береться з поля `class` векторного тайла, а НЕ з id шару: у стилі
 * OpenFreeMap dark один шар `highway_major_inner` малює trunk, primary,
 * secondary і tertiary разом — за id їх не розрізнити взагалі.
 *
 * trunk стоїть поруч із motorway, а не поруч із tertiary, бо в Україні
 * національна мережа тегована саме `trunk` (16 936 обʼєктів проти 33
 * `motorway`) — щабель, зроблений «як у світі», тут показував би країні
 * порожню карту замість її головних доріг.
 */
internal enum class RoadRank { NATIONAL, REGIONAL, LOCAL }

internal fun roadRankOf(osmClass: String): RoadRank = when (osmClass) {
    "motorway", "trunk" -> RoadRank.NATIONAL
    "primary", "secondary" -> RoadRank.REGIONAL
    else -> RoadRank.LOCAL
}

// Чорнило щабля на темній основі. Виміряно проти кожного ґрунту, який цей
// застосунок кладе під дорогу — див. MapRoadHierarchyTest. Попередній
// єдиний #4E4E57 давав 2.38:1 на rgb(12,12,12), хоч коментар обіцяв ~3.5:1.
internal val ROAD_INK_NATIONAL = 0xFFD6DBE3.toInt()
internal val ROAD_INK_REGIONAL = 0xFF97A0AD.toInt()
internal val ROAD_INK_LOCAL = 0xFF6B7079.toInt()

/** Облямівка лишається чорнилом ПІД серцевиною — саме її злиття з серцевиною
 *  і стирало ієрархію, яку тайли вже везуть. */
internal val ROAD_INK_CASING = 0xFF05070B.toInt()

internal fun roadInk(rank: RoadRank): Int = when (rank) {
    RoadRank.NATIONAL -> ROAD_INK_NATIONAL
    RoadRank.REGIONAL -> ROAD_INK_REGIONAL
    RoadRank.LOCAL -> ROAD_INK_LOCAL
}

/**
 * Друга ознака щабля, бо колір сам по собі не несе нічого під червоним
 * світлофільтром. Множник на власну ширину стилю: upstream дає trunk рівно
 * ту саму ширину, що й tertiary (обидва — `highway_major_inner`), тож без
 * цього національна мережа лишалась би завтовшки з польову дорогу.
 */
internal fun roadWidthScale(rank: RoadRank): Float = when (rank) {
    RoadRank.NATIONAL -> 1.5f
    RoadRank.REGIONAL -> 1.15f
    RoadRank.LOCAL -> 1f
}

// Списки токенів імен шарів («road»/«highway»/«path» проти
// «pier»/«rail»/«ferry»/«aerialway») зникли разом із перефарбом за іменем:
// причал і залізниця тепер відрізняються від дороги полем `class` у тайлі, а
// не тим, що чужий шар зветься road_major_rail.

/** Порожнє джерело-позначка: ширина МНОЖИТЬСЯ на власну ширину стилю, тож
 *  другий прохід по тому самому Style склав би множники. */
internal const val TACTICAL_ROADS_MARK = "phantom-tactical-roads-applied"

internal fun roadRankExpression(value: (RoadRank) -> Expression): Expression {
    val stops = listOf("motorway", "trunk", "primary", "secondary").flatMap {
        listOf(Expression.literal(it), value(roadRankOf(it)))
    }
    val parts = listOf(Expression.get("class")) + stops + listOf(value(RoadRank.LOCAL))
    return Expression.match(*parts.toTypedArray())
}

// ── Phase A2 — tactical restyle + 3D building extrusions ────────────────

// Тактичний перефарб жив тут і замінений на [applyGroundCartography]. Він
// вирішував колір за підрядком в імені чужого шару («water», «park»,
// «landuse»), лишав денну основу стоковою і фарбував воду, ліс та поле майже
// в колір землі — саме те, через що мапа читалася як недобудована. Ранг
// дороги, його чорнило й множник ширини нікуди не поділися: вони виміряні
// (MapRoadHierarchyTest) і тепер читаються новим проходом.

/** Множить власну ширину стилю на множник щабля. Zoom-крива upstream — це
 *  його картографія, ми її не переписуємо; якщо SDK не віддає базу назад,
 *  ширина лишається як була, а щабель несе саме чорнило. */
internal fun scaleRoadWidth(
    layer: org.maplibre.android.style.layers.LineLayer,
    scale: Expression,
) {
    val current = layer.lineWidth
    val base = when {
        current.isExpression -> current.expression
        else -> (current.value as? Number)?.let { Expression.literal(it) }
    } ?: return
    layer.setProperties(PropertyFactory.lineWidth(Expression.product(base, scale)))
}

internal fun installBuildingExtrusions(style: Style, isDark: Boolean) {
    if (style.getLayer(BUILDING_EXTRUSION_LAYER) == null) {
        // OpenFreeMap/MapTiler name their vector source "openmaptiles",
        // Mapbox-classic styles "composite" — a hardcoded "composite" here
        // meant the extrusions NEVER rendered on the default provider.
        // Resolve whatever vector source the active style actually carries.
        val vectorSourceId = style.sources
            .firstOrNull { it is org.maplibre.android.style.sources.VectorSource }
            ?.id ?: return
        val layer = org.maplibre.android.style.layers.FillExtrusionLayer(BUILDING_EXTRUSION_LAYER, vectorSourceId)
        layer.withSourceLayer(BUILDING_SOURCE_LAYER)
        // OpenMapTiles flags outlines superseded by building:part features
        // with hide_3d — rendering them too doubles up walls inside complex
        // buildings (visible z-fighting when pitched).
        layer.setFilter(
            Expression.neq(
                Expression.coalesce(Expression.get("hide_3d"), Expression.literal(false)),
                Expression.literal(true)
            )
        )
        layer.setProperties(
            PropertyFactory.fillExtrusionVerticalGradient(false),
            PropertyFactory.fillExtrusionHeight(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(15, 0),
                    Expression.stop(16, Expression.get("render_height"))
                )
            ),
            PropertyFactory.fillExtrusionBase(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(15, 0),
                    Expression.stop(16, Expression.get("render_min_height"))
                )
            ),
            PropertyFactory.fillExtrusionOpacity(
                Expression.interpolate(
                    Expression.linear(),
                    Expression.zoom(),
                    Expression.stop(15, 0.92f),
                    Expression.stop(17.5, 0.45f)
                )
            ),
        )
        // Insert below the base style's first symbol layer so street/place
        // names stay readable above the building volumes.
        val firstSymbol = style.layers
            .firstOrNull { it is org.maplibre.android.style.layers.SymbolLayer }?.id
        if (firstSymbol != null) style.addLayerBelow(layer, firstSymbol) else style.addLayer(layer)
    }
    applyBuildingTheme(style, isDark)
}

/**
 * Colour and light for the building volumes. Split out of the installer
 * because that one early-returns once the layer exists: a theme flip left
 * obsidian buildings standing on a light basemap, swallowing every label.
 */
internal fun applyBuildingTheme(style: Style, isDark: Boolean) {
    val layer = style.getLayer(BUILDING_EXTRUSION_LAYER)
        as? org.maplibre.android.style.layers.FillExtrusionLayer ?: return
    layer.setProperties(
        // Один тон на обʼєм: MapLibre фарбує екструзію одним кольором, стіни темнить світло.
        PropertyFactory.fillExtrusionColor(MapTokens.roof(isDark)),
        // Рентген для внутрішніх коридорів жив ТУТ і топив дах до 0.10 на z19 —
        // тобто саме там, де оператор дивиться на будинки, місто ставало
        // плоским. Прозорість під план поверху більше не потрібна: тир
        // BUILDING_EXTRUSION має `forbids INDOOR_PRESENT` і гасить обʼєм цілком,
        // щойно план зʼявився. Лишається легка втрата щільності вглиб, щоб
        // підписи й вхід у двір читались крізь стіну.
        PropertyFactory.fillExtrusionOpacity(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(15.0, 0f),
                Expression.stop(15.5, 0.45f),
                Expression.stop(16.0, if (isDark) 0.90f else 0.88f),
                Expression.stop(17.5, if (isDark) 0.88f else 0.85f),
                Expression.stop(19.0, if (isDark) 0.82f else 0.80f),
            )
        )
    )
    style.light?.let { light ->
        light.setAnchor(org.maplibre.android.style.layers.Property.ANCHOR_MAP)
        light.setColor(android.graphics.Color.WHITE)
        light.setIntensity(if (isDark) 0.55f else 0.30f)
        light.setPosition(org.maplibre.android.style.light.Position(1.15f, 315f, 30f))
    }
}

/**
 * Гасить ОБʼЄМ, і тільки обʼєм.
 *
 * Раніше прохід ішов по `source-layer` = building і клав разом із екструзією
 * плаский слід Обсидіану — його рідні `obsidian-building*`. Тобто на вільній
 * ліцензії (тир вимагає TERRAIN_3D) або щойно зʼявлявся план поверху
 * (`forbids INDOOR_PRESENT`) місто зникало з мапи цілком: земля без жодного
 * будинку. Слід будівлі — це ґрунт стилю, а не платна глибина; його не ховає
 * ні ліцензія, ні режим приміщення.
 *
 * Чужий `building-3d` лишається мертвим назавжди ([hideVendorVolumes]) —
 * «показати обʼєм» його не воскрешає.
 */
internal fun setBuildingExtrusionsVisible(style: Style, visible: Boolean) {
    val visibility = if (visible) {
        org.maplibre.android.style.layers.Property.VISIBLE
    } else {
        org.maplibre.android.style.layers.Property.NONE
    }
    var touched = 0
    for (layer in style.layers) {
        if (layer !is org.maplibre.android.style.layers.FillExtrusionLayer) continue
        if (layer.sourceLayer != BUILDING_SOURCE_LAYER) continue
        if (layer.id == VENDOR_BUILDING_3D) continue
        layer.setProperties(PropertyFactory.visibility(visibility))
        touched++
    }
    android.util.Log.i("MapBuildings", "extrusions ${if (visible) "shown" else "hidden"}: $touched layer(s)")
}

/** The OpenMapTiles source layer every building — theirs and ours — is drawn from. */
internal const val BUILDING_SOURCE_LAYER = "building"

internal const val BUILDING_EXTRUSION_LAYER = "phantom-buildings-3d"

internal const val TACTICAL_OBSIDIAN_HEX = "#1F1F24"

