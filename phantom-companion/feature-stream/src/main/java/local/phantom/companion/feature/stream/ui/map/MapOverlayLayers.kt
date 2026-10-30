package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource

// NAV-0 / 500-line rule — the LIVING half of the style-layer engine, split
// out of MapStyleLayers.kt verbatim: the trust halo, the phantom spatial
// layer stack and the precipitation radar. The air-raid alert overlay lives
// in MapAlertLayers.kt (СИРЕНА split). Same package, same `internal` surface.

// ── LEVIATHAN-2 — the trust halo ─────────────────────────────────────────

internal const val TRUST_HALO_SOURCE = "phantom-trust-halo-source"
internal const val TRUST_HALO_ZONE_SOURCE = "phantom-trust-halo-zone-source"
internal const val TRUST_HALO_FILL = "phantom-trust-halo-fill"
internal const val TRUST_HALO_LINE = "phantom-trust-halo-line"
internal const val TRUST_HALO_LINE_DASH = "phantom-trust-halo-line-dash"

/**
 * Обидві полярності вироку зводяться до НІЧНОГО hex — єдиного ключа, проти
 * якого написані і штрих, і альфа.
 *
 * Без цього кроку денний hex не збігся б із жодною гілкою [trustHaloDash] і
 * впав би у гілку «вироку немає»; а денний TRUSTED, ще гірше, лишився б
 * солідом і на всіх інших вироках теж. Це і є ризик, заради якого таблиця
 * пар живе в одному місці ([MapSymbolPalette.TRUST_RING_DAY]).
 */
private fun trustKey(colorHex: String): String =
    MapSymbolPalette.TRUST_RING_NIGHT[colorHex] ?: colorHex

/**
 * The shape half of the verdict. Colour alone cannot carry a claim — the
 * grounds this app renders on are not all knowable (a CUSTOM provider's is
 * not knowable at all), and roughly one man in twelve reads red and amber
 * as the same ink. So the doubt ring says it twice: hue AND edge texture.
 *
 * Keyed on the hex because that hex IS the verdict at the only call site
 * (MapCanvas.kt:1283 hands this function `EwTrail.colorFor(dead, trust)`,
 * and its own KDoc at EwTrail.kt:113 calls the table "shared by the halo").
 * Keying here rather than adding a defaulted `trust` parameter means there
 * is no wiring left to forget — a defaulted one would have drawn every
 * degraded fix as a confident solid ring until someone remembered.
 * [TrustHaloDashTest] fails if EwTrail ever emits a hex this table misses.
 *
 * Null means solid, and solid is reserved: it is the TRUSTED claim.
 *
 * КОЖЕН штрих тут — ЧОТИРИ і більше числа, тире-крапка, і це не оздоблення.
 * Пʼять персон гонтлету прочитали тонку бурштинову дугу як дорогу («за кермом
 * я б поїхав по ній»), а дальтонік не відрізнив її від стежки. Дорога і стежка
 * в цьому стилі — це завжди ОДНА пара «риска-проміжок»; візерунок, у якому
 * довга риска чергується з крапкою, не належить жодній лінійній фічі мапи.
 * Другу половину відповіді несе заливка ([trustZoneAlpha]): по зоні не їдуть.
 */
internal fun trustHaloDash(colorHex: String): Array<Float>? = when (trustKey(colorHex)) {
    "#38BDF8" -> null                                    // TRUSTED
    "#F59E0B" -> arrayOf(6f, 2f, 1f, 2f)                 // DEGRADED
    "#F87171" -> arrayOf(4f, 2f, 1f, 2f, 1f, 2f)         // JAMMED, or dead reckoning
    "#EF4444" -> arrayOf(2f, 2f, 1f, 2f, 1f, 2f, 1f, 2f) // SPOOFED
    // No verdict at all (EwTrail.colorFor's else branch — the EW engine is
    // not running). Not a point on the trusted→spoofed severity axis, so it
    // gets no texture from it: the sparsest mark there is, for absence.
    // Solid was the one option ruled out — solid would claim TRUSTED.
    else -> arrayOf(1f, 2f, 1f, 6f)
}

/**
 * Барва кільця — СПОКІЙНЕ чорнило землі, однакове на всі вердикти.
 *
 * **Вирок власника 31.08, після прямого порівняння з Google:** у них коло такого
 * самого розміру читається «десь тут», у нас — «аварія». Причина не в розмірі.
 *
 * Вердикт був закодований на цьому кільці **тричі**: барвою (`#EF4444` при
 * підміні, `#F87171` при глушінні), гучністю ([trustRingAlpha]) і текстурою
 * пунктиру ([trustRingDash]) — і ще раз словом у смузі довіри. Чотири голоси
 * про одне. Повтор у тривожному повідомленні не додає ясності, він **підвищує
 * градус**; Google каже це один раз і нейтрально.
 *
 * **Розподіл після дебату:** кільце несе ОДНУ величину — сумнів (його розмір і
 * гучність). Вердикт лишається смузі, яка вміє сказати його словом. Текстура
 * пунктиру лишається як тихий канал: вона розрізняє, не кричить.
 *
 * **Межа, яку не переступати: спокій — це ВІДТІНОК, не прозорість.** Заміряні
 * 3.0:1 — контраст проти землі, і спокійне чорнило зобовʼязане його тримати.
 * Зробити коло спокійним, знизивши альфу, означало б зламати підлогу
 * читабельності, через яку денна драбина взагалі піднята. Заміряно 01.09:
 * ніч `#C8D2DE` проти `#0C0E13` — **12.6:1**; день `#4A5560` проти `#F6F3EC` —
 * **6.9:1**. Обидва з запасом над підлогою.
 */
internal fun trustRingHex(colorHex: String, isDark: Boolean): String =
    if (isDark) "#C8D2DE" else "#4A5560"

/**
 * Гучність кільця = вага повідомлення, а не величина помилки.
 *
 * Дві драбини, і це виміряно, а не симетрії заради: на світлій землі
 * прозорість купує менше контрасту, ніж на темній. #B45309 на 55 % дає
 * 2.20:1 проти #F6F3EC — рівно той мертвий край, що досі тримався самою
 * заливкою; і барвою цього не полагодити, бо навіть чорне чорнило на 55 %
 * бере 3.0:1. Тому денна половина драбини піднята доти, доки кожен стан,
 * ЯКИЙ ГОВОРИТЬ ЧИП, не бере 3:1 на своїй землі (3.28 / 4.99 / 7.08).
 * Мовчазні — TRUSTED і «вироку немає» — лишаються шепотом навмисне.
 * TrustRingPolarityTest міряє все це щозбірки.
 */
internal fun trustRingAlpha(colorHex: String, isDark: Boolean): Float = when (trustKey(colorHex)) {
    "#38BDF8" -> 0.35f
    "#F59E0B" -> if (isDark) 0.55f else 0.80f
    "#F87171" -> if (isDark) 0.70f else 0.88f
    "#EF4444" -> if (isDark) 0.80f else 0.96f
    else -> 0.45f
}

/** Ширина кільця сумніву та сліду — одна на обидва шари кожного з них. */
private const val TRUST_RING_WIDTH_DP = 1.75f

/**
 * Заливка не міряє похибку — вона каже одне слово: «це площа».
 *
 * Диск тут уже жив і його прибрали: при ±85 м він фарбував третину екрана,
 * тобто що гірші дані, то БІЛЬШЕ бурштину, і оператор знаходив себе пʼятим.
 * Тому заливка вернулась стелею, а не драбиною: частка від гучності кільця,
 * зрізана [TRUST_ZONE_ALPHA_MAX]. Найгучніший денний SPOOFED дав би 0.13 —
 * стеля ріже його до 0.10. Це шепіт, якого вистачає, щоб око побачило нутро
 * і перестало читати межу як смугу дороги; для контрасту вона не робить
 * нічого, і TrustRingPolarityTest далі міряє САМЕ кільце, без неї.
 */
private const val TRUST_ZONE_OF_RING = 0.14f
private const val TRUST_ZONE_ALPHA_MAX = 0.10f

internal fun trustZoneAlpha(colorHex: String, isDark: Boolean): Float =
    (trustRingAlpha(colorHex, isDark) * TRUST_ZONE_OF_RING).coerceAtMost(TRUST_ZONE_ALPHA_MAX)

/**
 * The halo — the fused fix's live 1σ doubt, breathing with FixFlux.
 * Installed BEFORE the operator pin so the arrow always flies above it.
 *
 * Слід РЕБ, що жив тут поруч, знято з землі: власник назвав «лінії від
 * точки» розробницькими, а сам рушій лишається пасивним бейджем і ніколи
 * джерелом позиції. Пише крихти він і далі — їх читає журнал РЕБ.
 *
 * Два джерела на одне коло. Межу далі несе LineString — саме цю геометрію
 * бачив пристрій, і міняти її заради заливки означало б поставити на кін
 * САМЕ КІЛЬЦЕ (полігон малює межу інакше, і перевірити це без телефона
 * нічим). Полігон живе окремо і годує тільки заливку: провалиться він —
 * зникне шепіт, а не вирок.
 */
internal fun installTrustHalo(style: Style) {
    if (style.getSource(TRUST_HALO_SOURCE) == null) {
        style.addSource(GeoJsonSource(TRUST_HALO_SOURCE, EMPTY_FC))
    }
    if (style.getSource(TRUST_HALO_ZONE_SOURCE) == null) {
        style.addSource(GeoJsonSource(TRUST_HALO_ZONE_SOURCE, EMPTY_FC))
    }
    // Перша з трьох: межа мусить лягти ПОВЕРХ власного нутра.
    if (style.getLayer(TRUST_HALO_FILL) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.FillLayer(TRUST_HALO_FILL, TRUST_HALO_ZONE_SOURCE)
                .withProperties(
                    PropertyFactory.fillColor(Expression.get("color")),
                    PropertyFactory.fillOpacity(Expression.toNumber(Expression.get("zoneA"))),
                ),
        )
    }
    if (style.getLayer(TRUST_HALO_LINE) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(TRUST_HALO_LINE, TRUST_HALO_SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(TRUST_RING_WIDTH_DP),
                    PropertyFactory.lineOpacity(Expression.toNumber(Expression.get("alpha"))),
                    PropertyFactory.lineBlur(1.2f),
                ),
        )
    }
    // Two rings, one visible at a time, because line-dasharray has no
    // "unset": every other property here can be written back to its default
    // and this one cannot. Visibility-flipping instead is the same trick
    // applyRadarFrames already uses further down this file.
    if (style.getLayer(TRUST_HALO_LINE_DASH) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(TRUST_HALO_LINE_DASH, TRUST_HALO_SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(Expression.get("color")),
                    PropertyFactory.lineWidth(TRUST_RING_WIDTH_DP),
                    PropertyFactory.lineOpacity(Expression.toNumber(Expression.get("alpha"))),
                    // No lineBlur: a blurred 1px dash is a smear, not a texture.
                    PropertyFactory.lineDasharray(arrayOf(4f, 2f)),
                    PropertyFactory.visibility(org.maplibre.android.style.layers.Property.NONE),
                ),
        )
    }
}

/**
 * The doubt circle: a geodesic ring of [radiusM] around the eased dot, in
 * the trust verdict's color. Radius floors at 5 m so a good fix still shows
 * a heartbeat of honesty rather than vanishing.
 *
 * [colorHex] — нічний hex вироку, як його віддає `EwTrail.colorFor`; барву
 * для денної землі бере [trustRingHex], гучність — [trustRingAlpha].
 */
internal fun updateTrustHalo(
    style: Style,
    lat: Double,
    lon: Double,
    radiusM: Float,
    colorHex: String,
    isDark: Boolean,
) {
    val source = style.getSourceAs<GeoJsonSource>(TRUST_HALO_SOURCE) ?: return
    val r = radiusM.coerceAtLeast(5f).toDouble()
    val cosLat = kotlin.math.cos(Math.toRadians(lat))
    val ring = (0..48).joinToString(",") { i ->
        val a = 2.0 * Math.PI * i / 48
        val dLat = (r / 111_320.0) * kotlin.math.cos(a)
        val dLon = (r / (111_320.0 * cosLat)) * kotlin.math.sin(a)
        "[${lon + dLon},${lat + dLat}]"
    }
    val ink = trustRingHex(colorHex, isDark)
    val alpha = String.format(java.util.Locale.ROOT, "%.2f", trustRingAlpha(colorHex, isDark))
    val zoneA = String.format(java.util.Locale.ROOT, "%.3f", trustZoneAlpha(colorHex, isDark))
    source.setGeoJson(
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{" +
            "\"color\":\"$ink\",\"alpha\":$alpha}," +
            "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[$ring]}}]}",
    )
    style.getSourceAs<GeoJsonSource>(TRUST_HALO_ZONE_SOURCE)?.setGeoJson(
        "{\"type\":\"FeatureCollection\",\"features\":[{\"type\":\"Feature\",\"properties\":{" +
            "\"color\":\"$ink\",\"zoneA\":$zoneA}," +
            "\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[$ring]]}}]}",
    )
    val dash = trustHaloDash(colorHex)
    style.getLayer(TRUST_HALO_LINE)?.setProperties(
        PropertyFactory.visibility(
            if (dash == null) {
                org.maplibre.android.style.layers.Property.VISIBLE
            } else {
                org.maplibre.android.style.layers.Property.NONE
            },
        ),
    )
    style.getLayer(TRUST_HALO_LINE_DASH)?.setProperties(
        PropertyFactory.visibility(
            if (dash == null) {
                org.maplibre.android.style.layers.Property.NONE
            } else {
                org.maplibre.android.style.layers.Property.VISIBLE
            },
        ),
        PropertyFactory.lineDasharray(dash ?: arrayOf(4f, 2f)),
    )
}

internal fun clearTrustHalo(style: Style) {
    style.getSourceAs<GeoJsonSource>(TRUST_HALO_SOURCE)?.setGeoJson(EMPTY_FC)
    style.getSourceAs<GeoJsonSource>(TRUST_HALO_ZONE_SOURCE)?.setGeoJson(EMPTY_FC)
}

// ── R4.1 — phantom spatial layers on the full map ────────────────────────
//
// Every VISIBLE layer in the SpatialLayerStore paints into three shared,
// data-driven sources so an arbitrary stack of layers (POI, transit,
// cameras, aircraft, hazards) renders with per-feature tint. Layers install
// once (idempotent) and update from a single flow — same discipline as the
// operator pin.

internal const val LAYER_MARKERS_SOURCE = "phantom-layer-markers-source"
internal const val LAYER_MARKERS_DOT = "phantom-layer-markers-dot"
internal const val LAYER_MARKERS_LABEL = "phantom-layer-markers-label"
internal const val LAYER_LINES_SOURCE = "phantom-layer-lines-source"
internal const val LAYER_LINES_LAYER = "phantom-layer-lines-layer"
internal const val LAYER_REGIONS_SOURCE = "phantom-layer-regions-source"
internal const val LAYER_REGIONS_LAYER = "phantom-layer-regions-layer"
internal const val LAYER_REGIONS_OUTLINE = "phantom-layer-regions-outline"
internal const val LAYER_REGIONS_LABEL = "phantom-layer-regions-label"

internal fun installPhantomLayers(style: Style, isDark: Boolean = true) {
    val palette = MapSymbolPalette.resolve(isDark)
    if (style.getSource(LAYER_REGIONS_SOURCE) == null) {
        style.addSource(GeoJsonSource(LAYER_REGIONS_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(LAYER_REGIONS_LAYER) == null) {
        // ATLAS QA fix — 0.18 fill with no outline was invisible on the
        // obsidian basemap: PHANTOM "drew" zones nobody could see. A zone is
        // a verdict; it gets a real wash, a dashed tactical border, and its
        // label written on the ground.
        style.addLayer(
            org.maplibre.android.style.layers.FillLayer(LAYER_REGIONS_LAYER, LAYER_REGIONS_SOURCE)
                .withProperties(
                    PropertyFactory.fillColor(Expression.coalesce(Expression.get("color"), Expression.literal("#F4AF25"))),
                    PropertyFactory.fillOpacity(0.26f),
                ),
        )
    }
    if (style.getLayer(LAYER_REGIONS_OUTLINE) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(LAYER_REGIONS_OUTLINE, LAYER_REGIONS_SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(Expression.coalesce(Expression.get("color"), Expression.literal("#F4AF25"))),
                    PropertyFactory.lineWidth(2.2f),
                    PropertyFactory.lineOpacity(0.95f),
                    PropertyFactory.lineDasharray(arrayOf(2.2f, 1.4f)),
                    PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                ),
        )
    }
    if (style.getLayer(LAYER_REGIONS_LABEL) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(LAYER_REGIONS_LABEL, LAYER_REGIONS_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.coalesce(Expression.get("label"), Expression.literal(""))),
                    PropertyFactory.textFont(MAP_TEXT_FONT),
                    PropertyFactory.textSize(11.5f),
                    PropertyFactory.textColor(palette.textInk),
                    PropertyFactory.textHaloColor(palette.textHalo),
                    PropertyFactory.textHaloWidth(1.4f),
                    // Stays CENTER, unlike every point label: this one names an
                    // AREA and sits on the area's centroid. Anchoring it TOP
                    // would hang a zone's name below its own middle.
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(true),
                ),
        )
    }
    if (style.getSource(LAYER_LINES_SOURCE) == null) {
        style.addSource(GeoJsonSource(LAYER_LINES_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(LAYER_LINES_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.LineLayer(LAYER_LINES_LAYER, LAYER_LINES_SOURCE)
                .withProperties(
                    PropertyFactory.lineColor(Expression.coalesce(Expression.get("color"), Expression.literal("#F4AF25"))),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
                    PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
                    PropertyFactory.lineOpacity(0.85f),
                ),
        )
    }
    if (style.getSource(LAYER_MARKERS_SOURCE) == null) {
        style.addSource(GeoJsonSource(LAYER_MARKERS_SOURCE, EMPTY_FC))
    }
    if (style.getLayer(LAYER_MARKERS_DOT) == null) {
        style.addLayer(
            CircleLayer(LAYER_MARKERS_DOT, LAYER_MARKERS_SOURCE).withProperties(
                PropertyFactory.circleColor(Expression.coalesce(Expression.get("color"), Expression.literal("#F4AF25"))),
                PropertyFactory.circleOpacity(0.95f),
                PropertyFactory.circleRadius(6.5f),
                // A layer marker's fill comes from the payload, so this ring is
                // the ONLY contrast the app can promise. White gave 1.09:1 on
                // the light basemap — none at all.
                PropertyFactory.circleStrokeColor(palette.separator),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
    }
    if (style.getLayer(LAYER_MARKERS_LABEL) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(LAYER_MARKERS_LABEL, LAYER_MARKERS_SOURCE)
                .withProperties(
                    PropertyFactory.textField(Expression.coalesce(Expression.get("label"), Expression.literal(""))),
                    PropertyFactory.textFont(MAP_TEXT_FONT),
                    PropertyFactory.textSize(10.5f),
                    PropertyFactory.textColor(palette.textInk),
                    PropertyFactory.textHaloColor(palette.textHalo),
                    PropertyFactory.textHaloWidth(1.3f),
                    // Anchored TOP, like the POI and saved-place labels. With
                    // no anchor MapLibre centres the text, so the same offset
                    // number produced a different gap here than everywhere
                    // else — half a line height further out.
                    PropertyFactory.textAnchor(org.maplibre.android.style.layers.Property.TEXT_ANCHOR_TOP),
                    PropertyFactory.textOffset(arrayOf(0f, 1.15f)),
                    PropertyFactory.textAllowOverlap(false),
                    PropertyFactory.textOptional(true),
                ),
        )
    }
}

internal fun updatePhantomLayers(
    style: Style,
    layers: List<local.phantom.companion.core.data.spatial.SpatialLayer>,
    isDark: Boolean = true,
) {
    installPhantomLayers(style, isDark)
    style.getSourceAs<GeoJsonSource>(LAYER_REGIONS_SOURCE)?.setGeoJson(buildLayerRegionsGeoJson(layers))
    style.getSourceAs<GeoJsonSource>(LAYER_LINES_SOURCE)?.setGeoJson(buildLayerLinesGeoJson(layers))
    style.getSourceAs<GeoJsonSource>(LAYER_MARKERS_SOURCE)?.setGeoJson(buildLayerMarkersGeoJson(layers))
}

// ── R4.x live overlays: precipitation radar ──────────────────────────────────

internal const val RADAR_SOURCE = "phantom-radar-source"
internal const val RADAR_LAYER = "phantom-radar-layer"

/**
 * LEVIATHAN-4 — the living radar: one raster source+layer per reel frame,
 * installed once, animated by flipping visibility. No source churn per tick
 * means no flicker once a frame's tiles are cached; the first loop loads
 * progressively, exactly like every radar console ever built.
 */
internal fun applyRadarFrames(style: Style, frames: List<RadarOverlay>, activeIndex: Int, visible: Boolean) {
    // Frame identity is the RainViewer timestamp, so a refreshed reel
    // naturally sheds layers that fell off the window's edge.
    val wanted = if (visible) frames.map { "$RADAR_LAYER-t${it.frameEpochSec}" }.toSet() else emptySet()
    style.layers
        .filter { it.id.startsWith("$RADAR_LAYER-t") && it.id !in wanted }
        .forEach { stale ->
            val suffix = stale.id.removePrefix("$RADAR_LAYER-t")
            style.removeLayer(stale)
            style.getSource("$RADAR_SOURCE-t$suffix")?.let { style.removeSource(it) }
        }
    if (!visible || frames.isEmpty()) return

    frames.forEachIndexed { idx, frame ->
        val srcId = "$RADAR_SOURCE-t${frame.frameEpochSec}"
        val layerId = "$RADAR_LAYER-t${frame.frameEpochSec}"
        if (style.getSource(srcId) == null) {
            val tileSet = org.maplibre.android.style.sources.TileSet("2.1.0", frame.urlTemplate).apply {
                minZoom = 0f
                maxZoom = 10f
            }
            style.addSource(org.maplibre.android.style.sources.RasterSource(srcId, tileSet, 256))
        }
        if (style.getLayer(layerId) == null) {
            val layer = org.maplibre.android.style.layers.RasterLayer(layerId, srcId)
                .withProperties(
                    PropertyFactory.rasterOpacity(0.55f),
                    // Fade-in is the enemy of a frame flip: it reads as pulse.
                    PropertyFactory.rasterFadeDuration(0f),
                )
            when {
                style.getLayer(ALERT_FILL_LAYER) != null -> style.addLayerBelow(layer, ALERT_FILL_LAYER)
                style.getLayer(OPERATOR_ARROW_LAYER) != null -> style.addLayerBelow(layer, OPERATOR_ARROW_LAYER)
                else -> style.addLayer(layer)
            }
        }
        style.getLayer(layerId)?.setProperties(
            PropertyFactory.visibility(
                if (idx == activeIndex) {
                    org.maplibre.android.style.layers.Property.VISIBLE
                } else {
                    org.maplibre.android.style.layers.Property.NONE
                },
            ),
        )
    }
}

/** Add/replace/remove the RainViewer radar raster overlay (below the pins). */
internal fun applyRadar(style: Style, overlay: RadarOverlay?, visible: Boolean) {
    style.getLayer(RADAR_LAYER)?.let { style.removeLayer(it) }
    style.getSource(RADAR_SOURCE)?.let { style.removeSource(it) }
    if (!visible || overlay == null) return
    val tileSet = org.maplibre.android.style.sources.TileSet("2.1.0", overlay.urlTemplate).apply {
        // RainViewer radar tiles only exist to ~z10; beyond that the server
        // returns a "Zoom Level Not Supported" placeholder image. Cap the
        // source so MapLibre over-zooms the last real tile instead.
        minZoom = 0f
        maxZoom = 10f
    }
    style.addSource(org.maplibre.android.style.sources.RasterSource(RADAR_SOURCE, tileSet, 256))
    val layer = org.maplibre.android.style.layers.RasterLayer(RADAR_LAYER, RADAR_SOURCE)
        .withProperties(PropertyFactory.rasterOpacity(0.55f))
    when {
        style.getLayer(ALERT_FILL_LAYER) != null -> style.addLayerBelow(layer, ALERT_FILL_LAYER)
        style.getLayer(OPERATOR_ARROW_LAYER) != null -> style.addLayerBelow(layer, OPERATOR_ARROW_LAYER)
        else -> style.addLayer(layer)
    }
}
