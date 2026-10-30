package local.phantom.companion.feature.stream.ui.map

import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.maps.Style
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.widget.MapPayload

// NAV-0 — the map's style-layer engine, extracted verbatim from the
// MapScreen monolith: every install/update/apply helper that writes
// MapLibre sources & layers, the shared layer/source id constants, and
// the `mapStyleStep` isolation wrapper (one broken style step must never
// blank the rest of the map — see the second M-I01 device test).
//
// 500-line rule — this file keeps the CORE (operator pin, search pin,
// route polyline, shared ids, helpers); the ground half lives in
// MapTerrainLayers.kt, the living overlays in MapOverlayLayers.kt.

/** R4.1 — a phantom-layer marker the operator tapped on the full map. */
internal data class TappedLayerMarker(
    val lat: Double,
    val lon: Double,
    val label: String,
    val detail: String?,
    val glyph: String?,
    val layerTitle: String?,
)

internal fun org.maplibre.geojson.Feature.getStringOr(key: String): String =
    if (hasProperty(key)) runCatching { getProperty(key).asString }.getOrDefault("") else ""

/**
 * Числова властивість — числом.
 *
 * Через [getStringOr] id місця читався текстом і йшов у `toLongOrNull`, а це
 * тримається лише доти, доки JSON-число віддається рівно як «123»: варто
 * джерелу записати його дробовим — і розбір мовчки дає null, id підмінявся
 * хешем ТОЧКИ ПАЛЬЦЯ, а перейменування й видалення адресували інший рядок.
 * Тут число читається як число, а [fallback] лишається явним.
 */
internal fun org.maplibre.geojson.Feature.getLongOr(key: String, fallback: Long): Long {
    if (!hasProperty(key)) return fallback
    val raw = runCatching { getProperty(key) }.getOrNull() ?: return fallback
    return runCatching { raw.asLong }.recoverCatching { raw.asString.trim().toDouble().toLong() }
        .getOrDefault(fallback)
}

/** Rough centroid of a persisted layer's geometry — for chip focus. */
internal fun layerCentroid(
    layer: local.phantom.companion.core.data.spatial.SpatialLayer,
): Pair<Double, Double>? {
    val pts = buildList {
        layer.spec.markers.forEach { add(it.lat to it.lon) }
        layer.spec.regions.forEach { add(it.lat to it.lon) }
        layer.spec.lines.forEach { l -> l.points.forEach { add(it.lat to it.lon) } }
    }
    if (pts.isEmpty()) return null
    return pts.sumOf { it.first } / pts.size to pts.sumOf { it.second } / pts.size
}

// ── M-B01 / M-E01 / M-A01 helpers ────────────────────────────────────────

internal fun fmtDistance(m: Double): String =
    when {
        m >= 100_000 -> MapText.s(R.string.mapl_g_km_fmt_int).ifEmpty { "%.0f km" }.format(m / 1000.0)
        m >= 1000 -> MapText.s(R.string.mapl_g_km_fmt_dec).ifEmpty { "%.1f km" }.format(m / 1000.0)
        else -> MapText.s(R.string.mapl_g_m_fmt, m.toInt()).ifEmpty { "${m.toInt()} m" }
    }

internal fun fmtDuration(s: Double): String {
    val min = (s / 60.0).toInt().coerceAtLeast(1)
    return if (min >= 60) {
        MapText.s(R.string.mapl_g_hr_min_fmt, min / 60, min % 60).ifEmpty { "${min / 60} h ${min % 60} min" }
    } else {
        MapText.s(R.string.mapl_g_min_fmt, min).ifEmpty { "$min min" }
    }
}

// The two above bake Ukrainian units into the number; an English build still
// reads «км» on every route and every ETA. These take the units from
// resources — same numbers, same rounding, translated. The old signatures
// stay: files this lot does not own still call them.

/**
 * Десятина зникає на сотнях кілометрів — і це не косметика.
 *
 * Заміряно 31.08 на смузі масштабу: при відсуванні мапи рядок доходив до
 * «20000.0 км», не вміщався в платівку (`PLATE_MAX` 176 dp, з якої на текст
 * лишається менше третини) і **втрачав ОДИНИЦЮ**, лишаючи на екрані голе
 * «20000.0». Число без одиниці не означає нічого — гірше за відсутнє, бо
 * виглядає як показник.
 *
 * Десята частка кілометра має сенс на маршруті («3,2 км»), і не має жодного
 * на двадцяти тисячах. Прибрати її на великих числах — це і чесніше, і
 * коротше на два знаки, тобто одиниця перестає бути тим, чим жертвують.
 */
internal fun fmtDistance(res: android.content.res.Resources, m: Double): String =
    when {
        m >= 100_000 -> "%.0f %s".format(m / 1000.0, res.getString(R.string.map_unit_km))
        m >= 1000 -> "%.1f %s".format(m / 1000.0, res.getString(R.string.map_unit_km))
        else -> "${m.toInt()} ${res.getString(R.string.map_unit_m)}"
    }

internal fun fmtDuration(res: android.content.res.Resources, s: Double): String {
    val min = (s / 60.0).toInt().coerceAtLeast(1)
    val minute = res.getString(R.string.map_unit_min)
    return if (min >= 60) {
        "${min / 60} ${res.getString(R.string.map_unit_hour)} ${min % 60} $minute"
    } else {
        "$min $minute"
    }
}

/**
 * Стилі, яким картинку вже віддано. Слабкі ключі: стиль живе своїм життям і
 * має право згинути, а знімати з нього прапорець нікому.
 *
 * `style.getImage(id)` як перевірка «чи вона вже там» коштує не там, де
 * здається: ця ручка тягне картинку з нативного шару назад у Java-Bitmap —
 * копія на десятки кілобайт, на головному потоці, при кожному завантаженні
 * стилю, тобто на кожній зміні підложки й теми (той самий рахунок, який уже
 * оплатив POIOverlay.kt:270-281).
 */
private val styleImages: MutableMap<Style, MutableSet<String>> = java.util.WeakHashMap()

private fun Style.addImageOnce(id: String, bitmap: () -> android.graphics.Bitmap) {
    if (styleImages.getOrPut(this) { mutableSetOf() }.add(id)) addImage(id, bitmap())
}

/** Normalized (x,y) pairs → a closed path inside [pad] .. [pad]+[content]. */
private fun pathOf(norm: FloatArray, pad: Float, content: Float): android.graphics.Path =
    android.graphics.Path().apply {
        for (i in norm.indices step 2) {
            val x = pad + norm[i] * content
            val y = pad + norm[i + 1] * content
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

/**
 * The operator puck, in the grammar every operator already reads fluently
 * (QA: «GPS point … unlike all other maps … terrible»):
 *
 *  - an INK DISC with a bone hairline, an amber ring and a solid amber dot —
 *    the position, always there, with no pulse and no glow;
 *  - a translucent COMPASS CONE at rest — where the *device* faces, drawn
 *    as soft as the compass is unsure ([CompassFidelity.restAlpha]);
 *  - a hard NOTCH on the rim beside it, so «куди я дивлюсь» survives a
 *    windscreen at noon, where the cone alone did not;
 *  - the CHEVRON only in believable motion — where the *travel* points.
 *
 * The old pin was one chevron, always, rotating with whatever bearing it
 * was handed — an arrow that spun at a standstill and never looked like a
 * position. Which glyph shows is a per-fix property (`arrowA`/`coneA`), so
 * the swap costs one setGeoJson, no layer surgery.
 */
internal fun installOperatorPin(style: Style, context: android.content.Context) {
    if (style.getSource(OPERATOR_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(OPERATOR_SOURCE_ID, EMPTY_FC))
    }
    style.addImageOnce("operator-arrow") { createArrowBitmap(context) }
    style.addImageOnce(OPERATOR_NOTCH_IMAGE) { createNotchBitmap(context) }
    // Шість конусів — три сектори на дві полярності — реєструються тут, разом,
    // бо шар, що спитає картинку, якої ще нема, малює порожньо і мовчить.
    // Вибір іде властивістю фікса, тож сам пін полярності не має (див. вирок
    // MapMarkerContrastTest: «the puck must not have one»).
    CompassFidelity.entries.filter { it.sectorDeg > 0f }.forEach { fidelity ->
        listOf(true, false).forEach { night ->
            style.addImageOnce(coneImageId(fidelity, night)) {
                createConeBitmap(
                    context,
                    fidelity.sectorDeg,
                    MapSymbolPalette.resolve(night).separator,
                    fidelity.reachDp,
                )
            }
        }
    }
    if (style.getLayer(OPERATOR_CONE_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(OPERATOR_CONE_LAYER, OPERATOR_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(Expression.get("coneImg")),
                PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("cone"))),
                PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("coneA"))),
                // Apex at the bitmap centre + CENTER anchor: whichever point
                // MapLibre pivots a rotated quad about (unsourceable from this
                // tree — it lives in the native .so), apex and pivot coincide,
                // so the question cannot matter.
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }
    // Чорнильний диск — підкладка, на якій бурштин читається на будь-якій
    // землі (8:1), і він сам читається на світлій. Кістяний кант — його край на
    // темній землі й на даху 3D-будинку: без краю пляма читалась як наліпка.
    if (style.getLayer(OPERATOR_HALO_LAYER) == null) {
        style.addLayer(
            CircleLayer(OPERATOR_HALO_LAYER, OPERATOR_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(MapSymbolPalette.PUCK_RIM),
                PropertyFactory.circleRadius(PUCK_HALO_R_DP),
                PropertyFactory.circleOpacity(Expression.toNumber(Expression.get("dotA"))),
                PropertyFactory.circleStrokeColor(MapSymbolPalette.PUCK_HALO),
                PropertyFactory.circleStrokeWidth(PUCK_EDGE_W_DP),
                PropertyFactory.circleStrokeOpacity(Expression.toNumber(Expression.get("dotA"))),
                PropertyFactory.circlePitchAlignment(org.maplibre.android.style.layers.Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
    }
    if (style.getLayer(OPERATOR_RING_LAYER) == null) {
        style.addLayer(
            CircleLayer(OPERATOR_RING_LAYER, OPERATOR_SOURCE_ID).withProperties(
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleRadius(PUCK_RING_R_DP),
                PropertyFactory.circleStrokeColor(MapSymbolPalette.PUCK_CORE),
                PropertyFactory.circleStrokeWidth(PUCK_RING_W_DP),
                PropertyFactory.circleStrokeOpacity(Expression.toNumber(Expression.get("dotA"))),
                PropertyFactory.circlePitchAlignment(org.maplibre.android.style.layers.Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
    }
    // Серцебиття точки жило тут між гало і точкою. Воно писало в GeoJSON-джерело
    // кожні 50 мс на кожен фікс — ~12 записів на секунду, і кожен коштує цілого
    // кадру MapLibre: через дві години SKIN 42.4 °C, тротлення 3. Будь-яке
    // повернення пульсу малюється НАД мапою засобами Compose, а не шаром рушія.
    if (style.getLayer(OPERATOR_DOT_LAYER) == null) {
        style.addLayer(
            CircleLayer(OPERATOR_DOT_LAYER, OPERATOR_SOURCE_ID).withProperties(
                PropertyFactory.circleColor(MapSymbolPalette.PUCK_CORE),
                PropertyFactory.circleRadius(PUCK_CORE_R_DP),
                // The dot yields to the chevron in motion — two glyphs at
                // once would read as two operators.
                PropertyFactory.circleOpacity(Expression.toNumber(Expression.get("dotA"))),
                PropertyFactory.circlePitchAlignment(org.maplibre.android.style.layers.Property.CIRCLE_PITCH_ALIGNMENT_MAP),
            ),
        )
    }
    if (style.getLayer(OPERATOR_ARROW_LAYER) == null) {
        val layer = org.maplibre.android.style.layers.SymbolLayer(OPERATOR_ARROW_LAYER, OPERATOR_SOURCE_ID).withProperties(
            PropertyFactory.iconImage("operator-arrow"),
            PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
            PropertyFactory.iconRotate(Expression.get("bearing")),
            PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("arrowA"))),
            // Explicit, both of them: a puck lies ON the ground, and `auto`
            // resolves to something this tree cannot state.
            PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
            PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        style.addLayer(layer)
    }
    // ОСТАННЯ, вище за шеврон. Вирок гонтлету: «пак без стрілки напрямку в
    // спокої» — конус це говорив, але прозоро, і засічка є тверда мітка курсу
    // на ободі. Нижче шеврона їй не місце: `installRouteLayer` вставляє
    // стрічку маршруту саме під нього, тож на зупинці маршрут накрив би
    // єдиний різкий знак напрямку. З шевроном вони не сперечаються — у русі
    // `notchA` нуль, у спокої нуль в `arrowA`.
    if (style.getLayer(OPERATOR_NOTCH_LAYER) == null) {
        style.addLayer(
            org.maplibre.android.style.layers.SymbolLayer(OPERATOR_NOTCH_LAYER, OPERATOR_SOURCE_ID).withProperties(
                PropertyFactory.iconImage(OPERATOR_NOTCH_IMAGE),
                PropertyFactory.iconRotationAlignment(org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconPitchAlignment(org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP),
                PropertyFactory.iconRotate(Expression.toNumber(Expression.get("cone"))),
                PropertyFactory.iconOpacity(Expression.toNumber(Expression.get("notchA"))),
                PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }
}

internal fun updateOperatorPin(
    style: Style,
    fix: LocationFix?,
    sensorBearing: Float,
    compass: CompassFidelity,
    isDark: Boolean,
) {
    if (fix == null) {
        style.getSourceAs<GeoJsonSource>(OPERATOR_SOURCE_ID)?.setGeoJson(EMPTY_FC)
        return
    }
    val moving = (fix.speedMps ?: 0f) > 1.0f
    updateOperatorPinAt(
        style, fix.lat, fix.lon, fix.bearing ?: sensorBearing, sensorBearing, moving, compass, isDark,
    )
}

/** ATLAS-1 — the eased write path: FixFlux hands raw coordinates per frame.
 *  [courseBearing] rotates the chevron (travel), [compassBearing] the rest
 *  cone (device facing); [moving] decides which of the two is on screen.
 *
 *  [compass] відкриває сектор рівно настільки, наскільки пристрій знає
 *  напрям, і закриває конус зовсім на [CompassFidelity.UNRELIABLE]: точка
 *  без конуса і без засічки є чесне «не знаю, куди ти дивишся». */
internal fun updateOperatorPinAt(
    style: Style,
    lat: Double,
    lon: Double,
    courseBearing: Float,
    compassBearing: Float,
    motionAlpha: Float,
    compass: CompassFidelity,
    isDark: Boolean,
) {
    val source = style.getSourceAs<GeoJsonSource>(OPERATOR_SOURCE_ID) ?: return
    val aA = motionAlpha.coerceIn(0f, 1f)
    val dA = (1f - aA).coerceIn(0f, 1f)
    val knowsFacing = compass != CompassFidelity.UNRELIABLE
    val coneA = if (knowsFacing) dA * compass.restAlpha else 0f
    val notchA = if (knowsFacing) dA else 0f
    val coneImg = coneImageId(compass.orWidest(), isDark)
    source.setGeoJson(
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{"bearing":$courseBearing,"cone":$compassBearing,"arrowA":$aA,"coneA":$coneA,"notchA":$notchA,"dotA":$dA,"coneImg":"$coneImg"},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}]}""",
    )
}

internal fun updateOperatorPinAt(
    style: Style,
    lat: Double,
    lon: Double,
    courseBearing: Float,
    compassBearing: Float,
    moving: Boolean,
    compass: CompassFidelity,
    isDark: Boolean,
) {
    updateOperatorPinAt(
        style = style,
        lat = lat,
        lon = lon,
        courseBearing = courseBearing,
        compassBearing = compassBearing,
        motionAlpha = if (moving) 1f else 0f,
        compass = compass,
        isDark = isDark,
    )
}

internal fun hexOf(color: Int): String =
    String.format(java.util.Locale.ROOT, "#%06X", color and 0xFFFFFF)

/**
 * Наскільки пристрій вірить власному компасу — і, отже, наскільки широкий
 * сектор має право намалювати. Ширина конуса Є похибкою компаса, сказаною
 * чесно; досі вона була постійні 49.6° при будь-якій точності, а сама
 * точність приходила в `onAccuracyChanged` і викидалась.
 */
internal enum class CompassFidelity(
    val sectorDeg: Float,
    /**
     * Гучність і досяг конуса в спокої. Вузький сектор — це заявка, у якій
     * пристрій ВПЕВНЕНИЙ, тож вона має право говорити голосно й тягтись далі;
     * широкий шепоче, бо сам зміст його — «десь тут». Постійні 0.55 на всі
     * стани й були вироком гонтлету «пак без стрілки напрямку в спокої»:
     * найкращий компас телефона малювався так само блідо, як найгірший.
     */
    val restAlpha: Float,
    val reachDp: Float,
) {
    HIGH(30f, 0.88f, 58f),
    MEDIUM(50f, 0.72f, 48f),
    LOW(80f, 0.55f, 38f),

    /** Напрям, якого пристрій не знає, не малюється зовсім. */
    UNRELIABLE(0f, 0f, 0f),
    ;

    /** Картинка для UNRELIABLE усе одно потрібна — її ховає альфа, не її брак. */
    fun orWidest(): CompassFidelity = if (sectorDeg > 0f) this else LOW

    companion object {
        /**
         * [status] — значення `SensorManager.SENSOR_STATUS_*`. Прилад, що не
         * озвався жодного разу, лишається на MEDIUM (див. початкове значення
         * у стані сцени): це рівно той сектор, який мапа малювала завжди,
         * тож мовчання платформи не забирає конус, а лише не звужує його.
         */
        fun fromSensorStatus(status: Int): CompassFidelity = when (status) {
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> HIGH
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> MEDIUM
            android.hardware.SensorManager.SENSOR_STATUS_ACCURACY_LOW -> LOW
            else -> UNRELIABLE
        }
    }
}

internal fun coneImageId(fidelity: CompassFidelity, isDark: Boolean): String =
    "operator-cone-${fidelity.name.lowercase()}-${if (isDark) "night" else "day"}"

/** Ø30dp разом: точка мусить вигравати у власного кола сумніву. Штрих кільця —
 *  1,75, як у гліфів дизайн-системи. */
internal const val PUCK_HALO_R_DP = 15f
internal const val PUCK_EDGE_W_DP = 1.5f
internal const val PUCK_RING_R_DP = 9.5f
internal const val PUCK_RING_W_DP = 1.75f
internal const val PUCK_CORE_R_DP = 4.5f

/** Чорнильна смуга від краю точки до зовнішнього краю бурштинового кільця —
 *  звідти починається засічка курсу. */
internal const val PUCK_RIM_W_DP = 6f

internal const val OPERATOR_NOTCH_IMAGE = "operator-notch"

/** Засічка виходить за світлий обід рівно настільки, щоб її було видно збоку. */
private const val NOTCH_OVERHANG_DP = 1.4f
private const val NOTCH_HALF_WIDTH_DP = 3.2f

/**
 * Тверда мітка курсу на ободі пака: чорнильний клин від краю ядра назовні,
 * зі світлим кантом, щоб він читався і там, де темна земля зʼїдає чорнило.
 * Вістря дивиться ВГОРУ, як конус, і крутиться тим самим `cone`-курсом.
 */
internal fun createNotchBitmap(context: android.content.Context): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val rIn = (PUCK_CORE_R_DP + PUCK_RIM_W_DP) * density
    val rOut = (PUCK_HALO_R_DP + NOTCH_OVERHANG_DP) * density
    val halfWidth = NOTCH_HALF_WIDTH_DP * density
    val haloW = 1.6f * density
    val size = ((rOut + haloW) * 2f + 2f * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val c = size / 2f
    val path = android.graphics.Path().apply {
        moveTo(c - halfWidth, c - rIn)
        lineTo(c, c - rOut)
        lineTo(c + halfWidth, c - rIn)
        close()
    }
    canvas.drawPath(
        path,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MapSymbolPalette.PUCK_HALO
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = haloW * 2f
            strokeJoin = android.graphics.Paint.Join.ROUND
        },
    )
    canvas.drawPath(
        path,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MapSymbolPalette.PUCK_RIM
            style = android.graphics.Paint.Style.FILL
        },
    )
    return bitmap
}

/**
 * The chevron, normalized to the bitmap's CONTENT box: tip, bottom-right,
 * inner vertex, bottom-left. Every y sits 0.0667 above the shape it replaced
 * so the shoelace centroid lands on exactly 0.5 — the symbol rotates about
 * ICON_ANCHOR_CENTER, and a shape whose mass sat 2.13dp off that point
 * orbited it instead of turning on the spot (owner: «люфтять»).
 * MapSymbolContrastTest asserts the centroid; do not nudge a vertex alone.
 */
internal val OPERATOR_CHEVRON_PATH = floatArrayOf(
    0.5f, 0.0333f,
    0.9f, 0.8333f,
    0.5f, 0.6333f,
    0.1f, 0.8333f,
)

/** The navigation chevron — travel direction, shown only in motion. */
internal fun createArrowBitmap(context: android.content.Context): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    // 40dp of bitmap for 32dp of chevron: the 4dp round-cap outline used to
    // be sliced off by the bitmap edge it was drawn against.
    val size = (40 * density).toInt()
    val pad = 4f * density
    val content = 32f * density
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)

    // Same two edges as the dot, in the same order: the wide light halo first,
    // the ink rim over it, the amber last. 7dp of halo puts 3.5dp outside the
    // path — inside the 4dp pad, so nothing is clipped by the bitmap edge.
    val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSymbolPalette.PUCK_HALO
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 7f * density
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val rimPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSymbolPalette.PUCK_RIM
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 3.4f * density
        strokeJoin = android.graphics.Paint.Join.ROUND
        strokeCap = android.graphics.Paint.Cap.ROUND
    }
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = MapSymbolPalette.PUCK_CORE
        style = android.graphics.Paint.Style.FILL
    }

    val path = pathOf(OPERATOR_CHEVRON_PATH, pad, content)
    canvas.drawPath(path, haloPaint)
    canvas.drawPath(path, rimPaint)
    canvas.drawPath(path, paint)
    return bitmap
}

/**
 * The rest-state compass beam: a soft sector fading with distance, apex at
 * the EXACT CENTRE of the bitmap (which is where the dot is), opening UP.
 * Deliberately diffuse — an indoor compass is a rumour, and a hard edge would
 * promise too much.
 *
 * The bottom half is deliberately empty so the apex can sit on the bitmap
 * centre and be anchored CENTER. The cap and the wing tips used to be cut off
 * by the bitmap edge they were drawn against.
 *
 * [sectorDeg] — повний розхил, тобто заявлена похибка компаса.
 * [ink] — чорнило ПОЛЯРНОСТІ ЗЕМЛІ, ніколи не бурштин: бурштином на цій мапі
 * говорить сам оператор, і другий бурштиновий знак поруч із ним читався як
 * пляма, а не як окреме «куди дивиться пристрій». На бежевій землі
 * #EAB308 брав ~1.3:1 — тобто вдень надворі конуса не було взагалі.
 */
internal fun createConeBitmap(
    context: android.content.Context,
    sectorDeg: Float,
    ink: Int,
    reachDp: Float = CONE_REACH_DP,
): android.graphics.Bitmap {
    val density = context.resources.displayMetrics.density
    val halfWidthDp = reachDp * kotlin.math.tan(Math.toRadians(sectorDeg / 2.0)).toFloat()
    val w = ((halfWidthDp * 2f + 8f) * density).toInt().coerceAtLeast(1)
    val h = ((reachDp + 6f) * 2f * density).toInt().coerceAtLeast(1)
    val bitmap = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val cx = w / 2f
    val apexY = h / 2f
    val reach = reachDp * density
    val halfWidth = halfWidthDp * density
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.FILL
        shader = android.graphics.RadialGradient(
            // Далі за досяг: кінчики крил лежать на гіпотенузі, і радіус,
            // рівний досягу, малював би їх повністю прозорими.
            cx, apexY, kotlin.math.hypot(halfWidth, reach) + 2f * density,
            intArrayOf(
                android.graphics.Color.argb(
                    CONE_APEX_ALPHA_255,
                    android.graphics.Color.red(ink),
                    android.graphics.Color.green(ink),
                    android.graphics.Color.blue(ink),
                ),
                android.graphics.Color.argb(
                    0,
                    android.graphics.Color.red(ink),
                    android.graphics.Color.green(ink),
                    android.graphics.Color.blue(ink),
                ),
            ),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    val path = android.graphics.Path().apply {
        moveTo(cx, apexY)
        lineTo(cx - halfWidth, apexY - reach)
        quadTo(cx, apexY - reach - 3.6f * density, cx + halfWidth, apexY - reach)
        close()
    }
    canvas.drawPath(path, paint)
    return bitmap
}

/** Досяг за замовчуванням; живі конуси беруть свій з [CompassFidelity.reachDp]. */
internal const val CONE_REACH_DP = 44f

/**
 * 176/255 у вістрі, далі згасання. На склі його ще множить
 * [CompassFidelity.restAlpha]: 0.61 при HIGH, 0.38 при LOW — тобто пік
 * лишився тим самим виміряним числом, а рухається тепер шар, не картинка.
 */
private const val CONE_APEX_ALPHA_255 = 176

/** MAP-2 — install the search-result pin layer; idempotent.
 *  [isDark] carries no default, for the reason written over installRouteLayer. */
internal fun installSearchPin(style: Style, isDark: Boolean) {
    val palette = MapSymbolPalette.resolve(isDark)
    if (style.getSource(SEARCH_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(SEARCH_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(SEARCH_LAYER) == null) {
        style.addLayer(
            CircleLayer(SEARCH_LAYER, SEARCH_SOURCE_ID).withProperties(
                // HOLLOW. The operator's puck is the only filled dot on this
                // map; a filled salmon disc beside a filled amber one differed
                // by hue alone, which is nothing under a red filter or to a
                // colour-blind crew member. The ring itself carries the ground
                // contrast, so it is the ring that changes with the basemap.
                PropertyFactory.circleOpacity(0f),
                PropertyFactory.circleRadius(7f),
                PropertyFactory.circleStrokeColor(palette.searchCore),
                PropertyFactory.circleStrokeWidth(4.5f),
            ),
        )
    }
}

internal fun updateSearchPin(style: Style, point: Pair<Double, Double>?, isDark: Boolean) {
    installSearchPin(style, isDark)
    val source = style.getSourceAs<GeoJsonSource>(SEARCH_SOURCE_ID) ?: return
    if (point == null) {
        source.setGeoJson(EMPTY_FC)
        return
    }
    val (lat, lon) = point
    source.setGeoJson(
        """{"type":"FeatureCollection","features":[{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[$lon,$lat]}}]}""",
    )
}

internal fun providerLabel(provider: TileProviderId): String = when (provider) {
    TileProviderId.OBSIDIAN -> "obsidian"
    TileProviderId.OPENFREEMAP -> "openfreemap"
    TileProviderId.MAPTILER -> "maptiler"
    TileProviderId.STADIA -> "stadia"
    TileProviderId.CUSTOM -> "custom"
}

/**
 * MAP-4 — MapLibre-free bbox. Keeps :app's compile classpath clean of
 * MapLibre types so the navigation graph can wire the save-offline
 * callback without depending on the SDK directly.
 */
data class OfflineBounds(
    val northLat: Double,
    val eastLon: Double,
    val southLat: Double,
    val westLon: Double,
)

/** MAP-8 — install the route polyline layer; idempotent.
 *  [isDark] carries NO default on purpose: it defaulted to `true` for one day
 *  and every call site silently took the dark palette onto the light basemap. */
internal fun installRouteLayer(
    style: Style,
    isDark: Boolean,
    context: android.content.Context? = null,
) {
    if (context != null) style.addImageOnce(ROUTE_ARROW_IMAGE) { createRouteArrowBitmap(context, isDark) }
    if (style.getSource(ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID, EMPTY_FC))
    }
    // Casing first, so the core draws on top of it.
    if (style.getLayer(ROUTE_CASING_LAYER_ID) == null) {
        val casing = org.maplibre.android.style.layers.LineLayer(
            ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID,
        )
        casing.setFilter(Expression.neq(Expression.get("isDashed"), Expression.literal(true)))
        casing.setProperties(
            PropertyFactory.lineColor(MapTokens.routeHalo(isDark)),
            PropertyFactory.lineWidth(routeWidthExpression(ROUTE_CASING_EXTRA)),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
        )
        if (style.getLayer(OPERATOR_ARROW_LAYER) != null) {
            style.addLayerBelow(casing, OPERATOR_ARROW_LAYER)
        } else {
            style.addLayer(casing)
        }
    }
    if (style.getLayer(ROUTE_LAYER_ID) == null) {
        val layer = org.maplibre.android.style.layers.LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID)
        layer.setFilter(Expression.neq(Expression.get("isDashed"), Expression.literal(true)))
        layer.setProperties(
            PropertyFactory.lineColor(
                Expression.coalesce(
                    Expression.get("color"),
                    Expression.literal(routeCoreColor(isDark)),
                ),
            ),
            PropertyFactory.lineWidth(
                Expression.coalesce(
                    Expression.get("width"),
                    routeWidthExpression(0f),
                ),
            ),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineOpacity(
                Expression.coalesce(
                    Expression.get("opacity"),
                    Expression.literal(1f),
                ),
            ),
        )
        if (style.getLayer(OPERATOR_ARROW_LAYER) != null) {
            style.addLayerBelow(layer, OPERATOR_ARROW_LAYER)
        } else {
            style.addLayer(layer)
        }
    }
    // High-contrast dashed layer for pedestrian walkways, transit walks, and indoor passages
    if (style.getLayer(ROUTE_DASHED_LAYER_ID) == null) {
        val dashed = org.maplibre.android.style.layers.LineLayer(
            ROUTE_DASHED_LAYER_ID, ROUTE_SOURCE_ID,
        )
        dashed.setFilter(Expression.eq(Expression.get("isDashed"), Expression.literal(true)))
        dashed.setProperties(
            PropertyFactory.lineColor(
                Expression.coalesce(
                    Expression.get("color"),
                    Expression.literal(MapTokens.routeInk(isDark)),
                ),
            ),
            PropertyFactory.lineWidth(
                Expression.coalesce(
                    Expression.get("width"),
                    Expression.literal(5f),
                ),
            ),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineOpacity(
                Expression.coalesce(
                    Expression.get("opacity"),
                    Expression.literal(1f),
                ),
            ),
        )
        if (style.getLayer(OPERATOR_ARROW_LAYER) != null) {
            style.addLayerBelow(dashed, OPERATOR_ARROW_LAYER)
        } else {
            style.addLayer(dashed)
        }
    }
    // Direction chevrons riding the line: which WAY the route runs, without a
    // second glance. A bare ribbon reads the same forwards and backwards.
    if (style.getLayer(ROUTE_ARROWS_LAYER_ID) == null) {
        val arrows = org.maplibre.android.style.layers.SymbolLayer(
            ROUTE_ARROWS_LAYER_ID, ROUTE_SOURCE_ID,
        )
        arrows.setFilter(Expression.neq(Expression.get("isDashed"), Expression.literal(true)))
        arrows.setProperties(
            PropertyFactory.symbolPlacement(
                org.maplibre.android.style.layers.Property.SYMBOL_PLACEMENT_LINE,
            ),
            PropertyFactory.symbolSpacing(ROUTE_ARROW_SPACING),
            PropertyFactory.iconImage(ROUTE_ARROW_IMAGE),
            PropertyFactory.iconSize(0.7f),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            // Both MAP: a chevron that stays screen-flat while the line lies on
            // a tilted ground plane is what makes the two look unrelated.
            PropertyFactory.iconRotationAlignment(
                org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP,
            ),
            PropertyFactory.iconPitchAlignment(
                org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP,
            ),
            // A STEP, not a ramp. LINE-placed chevrons re-lay themselves along
            // the line on every zoom change — that crawl is the price of
            // symbol-placement and we pay it; fading them through the same
            // zoom range made them crawl AND dissolve at once, which is what
            // read as the line coming apart.
            PropertyFactory.iconOpacity(
                Expression.step(
                    Expression.zoom(), Expression.literal(0f),
                    Expression.stop(14f, 0.9f),
                ),
            ),
        )
        style.addLayerAbove(arrows, ROUTE_LAYER_ID)
    }
}

/**
 * ux-map-design.md §2.3/§5.2 — the ribbon's per-segment grading, additive
 * on top of [installRouteLayer]'s core line rather than replacing any of
 * its properties. Kept as its own source/layer instead of touching
 * [MapPayload.PolylineData] (a `@Serializable` type shared with the chat
 * MapWidget — far outside this feature's territory) or [ROUTE_SOURCE_ID]
 * (whose GeoJSON `buildPolylinesGeoJson` already builds with empty
 * properties for every other caller — transit, evacuation, phantom layers
 * — that this change must not touch).
 *
 * The overlay only ever carries GUESSED/REMEMBERED runs (design-critic.md
 * §S5's per-segment half; see [RouteRegisterProfile.degradedSegments]) — a
 * route with no register data published at all draws nothing here, and the
 * base [ROUTE_LAYER_ID] underneath already reads as solid/confident, so
 * "nothing drawn" is the correct rendering for "nothing known", not a bug.
 */
internal fun installRouteRegisterLayer(style: Style, isDark: Boolean) {
    if (style.getSource(ROUTE_REGISTER_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ROUTE_REGISTER_SOURCE_ID, EMPTY_FC))
    }
    // One layer per register, not one layer switching colour on a property:
    // line-dasharray is not data-driven in MapLibre, and the TEXTURE is half
    // the claim — colour alone is gone under a red filter or low sun.
    listOf(
        ROUTE_REGISTER_LAYER_ID to GroundRegister.GUESSED,
        ROUTE_REGISTER_REMEMBERED_LAYER_ID to GroundRegister.REMEMBERED,
    ).forEach { (layerId, register) ->
        if (style.getLayer(layerId) != null) return@forEach
        val layer = org.maplibre.android.style.layers.LineLayer(
            layerId, ROUTE_REGISTER_SOURCE_ID,
        )
        layer.setProperties(
            PropertyFactory.lineColor(register.coreColor(isDark)),
            // Thinner than the core line it rides (routeWidthExpression(0f)
            // is the core's own width) — an accent, not a replacement.
            PropertyFactory.lineWidth(routeWidthExpression(-2f)),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            // OPAQUE — same self-crossing lesson installRouteLayer already
            // paid for (see its own comment): alpha blending at a junction
            // stacks into blotches, so the signal has to be colour + dash,
            // never transparency.
            PropertyFactory.lineOpacity(1f),
        )
        register.edge().dashArray()?.let { dash ->
            layer.setProperties(PropertyFactory.lineDasharray(dash.toTypedArray()))
        }
        layer.setFilter(
            Expression.eq(Expression.get("register"), Expression.literal(register.wireName())),
        )
        if (style.getLayer(ROUTE_LAYER_ID) != null) {
            style.addLayerAbove(layer, ROUTE_LAYER_ID)
        } else {
            style.addLayer(layer)
        }
    }
}

/** The register's name on the wire, written by [updateRouteRegisterLayer] and
 *  read back by the layer filter — one spelling, one place. */
internal fun GroundRegister.wireName(): String = name.lowercase()

/** [segments] are (points, register) pairs — see [RouteRegisterProfile.degradedSegments]. */
internal fun updateRouteRegisterLayer(
    style: Style,
    segments: List<Pair<List<Pair<Double, Double>>, GroundRegister>>,
    isDark: Boolean,
) {
    installRouteRegisterLayer(style, isDark)
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_REGISTER_SOURCE_ID) ?: return
    if (segments.isEmpty()) {
        source.setGeoJson(EMPTY_FC)
        return
    }
    val features = segments.joinToString(",") { (points, register) ->
        val coords = points.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        // Its own name, not a two-way collapse: a register with no layer of
        // its own now draws NOTHING, where the old default painted it in
        // GUESSED's amber and claimed something nobody computed.
        val reg = register.wireName()
        """{"type":"Feature","properties":{"register":"$reg"},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }
    source.setGeoJson("""{"type":"FeatureCollection","features":[$features]}""")
}

/**
 * The routes NOT taken. Drawn under the active line in a muted tone so the
 * operator can see what he is choosing between — a variant list with nothing
 * on the map is a choice made blind.
 */
internal fun installAltRouteLayer(style: Style, isDark: Boolean) {
    val palette = MapSymbolPalette.resolve(isDark)
    if (style.getSource(ALT_ROUTE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ALT_ROUTE_SOURCE_ID, EMPTY_FC))
    }
    if (style.getSource(ALT_LABEL_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ALT_LABEL_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(ALT_LABEL_LAYER_ID) == null) {
        val labels = org.maplibre.android.style.layers.SymbolLayer(
            ALT_LABEL_LAYER_ID, ALT_LABEL_SOURCE_ID,
        )
        labels.setProperties(
            PropertyFactory.textField(Expression.get("label")),
            // Explicit stack: the style default 404s on OpenFreeMap and the
            // label silently never renders.
            PropertyFactory.textFont(MAP_TEXT_FONT),
            PropertyFactory.textSize(13f),
            // Ink on its own halo, both flipping with polarity: white on a
            // pale slate halo is the same washed-out pairing on a light
            // basemap that the plate rule exists to kill.
            PropertyFactory.textColor(palette.textInk),
            PropertyFactory.textHaloColor(palette.textHalo),
            PropertyFactory.textHaloWidth(2.2f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
            PropertyFactory.textPadding(2f),
        )
        style.addLayer(labels)
    }
    // The muted slate core alone measures 1.07:1 over the REGIONAL road ink
    // and 1.54:1 over the NATIONAL one (MapRoadHierarchyTest) — over the old
    // single grey it was 2.74:1, and brightening the roads is what took that
    // away. So the alternative becomes the same dual-edge object the chosen
    // route already is: ink casing under it, contrast carried by the pair.
    // Solid under a dashed core on purpose — MapLibre dash units are multiples
    // of the LINE'S OWN width, so a casing dashed with the same array draws
    // longer dashes than the core it is supposed to sit under.
    if (style.getLayer(ALT_ROUTE_CASING_LAYER_ID) == null) {
        val casing = org.maplibre.android.style.layers.LineLayer(
            ALT_ROUTE_CASING_LAYER_ID, ALT_ROUTE_SOURCE_ID,
        )
        casing.setProperties(
            PropertyFactory.lineColor(palette.routeCasing),
            PropertyFactory.lineWidth(routeWidthExpression(ALT_ROUTE_CASING_EXTRA)),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineOpacity(1f),
        )
        if (style.getLayer(ROUTE_CASING_LAYER_ID) != null) {
            style.addLayerBelow(casing, ROUTE_CASING_LAYER_ID)
        } else {
            style.addLayer(casing)
        }
    }
    if (style.getLayer(ALT_ROUTE_LAYER_ID) == null) {
        val layer = org.maplibre.android.style.layers.LineLayer(
            ALT_ROUTE_LAYER_ID, ALT_ROUTE_SOURCE_ID,
        )
        layer.setProperties(
            // The muting lives in the colour's own alpha and NOWHERE else —
            // a second opacity here would mean the contrast the palette test
            // measures is not the contrast the operator gets.
            PropertyFactory.lineColor(palette.altRoute),
            PropertyFactory.lineWidth(routeWidthExpression(0f)),
            PropertyFactory.lineJoin(org.maplibre.android.style.layers.Property.LINE_JOIN_ROUND),
            PropertyFactory.lineCap(org.maplibre.android.style.layers.Property.LINE_CAP_ROUND),
            PropertyFactory.lineDasharray(arrayOf(2.2f, 1.6f)),
        )
        // Below the casing, so the chosen route always reads as the top one.
        if (style.getLayer(ROUTE_CASING_LAYER_ID) != null) {
            style.addLayerBelow(layer, ROUTE_CASING_LAYER_ID)
        } else {
            style.addLayer(layer)
        }
    }
}

/**
 * Feed the alternatives layer: the lines, plus a label carrying each one's cost.
 * A grey line with no number says nothing — the operator cannot tell which row
 * of the compare sheet it is, so it may as well not be drawn.
 */
internal fun updateAltRoutes(
    style: Style,
    alts: List<Triple<List<Pair<Double, Double>>, String, Int>>,
    isDark: Boolean,
) {
    installAltRouteLayer(style, isDark)
    val lineSrc = style.getSourceAs<GeoJsonSource>(ALT_ROUTE_SOURCE_ID)
    val labelSrc = style.getSourceAs<GeoJsonSource>(ALT_LABEL_SOURCE_ID)
    val usable = alts.filter { it.first.size >= 2 }
    if (usable.isEmpty()) {
        lineSrc?.setGeoJson(EMPTY_FC)
        labelSrc?.setGeoJson(EMPTY_FC)
        return
    }
    val lines = usable.joinToString(",") { (pts, _, idx) ->
        val coords = pts.joinToString(",") { (lat, lon) -> "[$lon,$lat]" }
        """{"type":"Feature","properties":{"idx":$idx},"geometry":{"type":"LineString","coordinates":[$coords]}}"""
    }
    lineSrc?.setGeoJson("""{"type":"FeatureCollection","features":[$lines]}""")

    // Anchored a third of the way along, not at the midpoint: two variants that
    // share most of their length would stack their labels on top of each other.
    val labels = usable.mapIndexed { n, (pts, text, idx) ->
        val at = pts[(pts.size * (n + 1) / (usable.size + 1)).coerceIn(0, pts.size - 1)]
        val safe = text.replace("\"", "")
        """{"type":"Feature","properties":{"label":"$safe","idx":$idx},"geometry":{"type":"Point","coordinates":[${at.second},${at.first}]}}"""
    }.joinToString(",")
    labelSrc?.setGeoJson("""{"type":"FeatureCollection","features":[$labels]}""")
}

/**
 * Where the operator will have to DO something, marked on the line itself.
 *
 * Turn markers are their own source, not the route's: the route line is
 * trimmed behind the operator as he drives, and a maneuver must not vanish
 * because the polyline under it was re-cut.
 */
internal fun installManeuverLayer(style: Style, context: android.content.Context? = null) {
    if (context != null) {
        style.addImageOnce(MANEUVER_TURN_IMAGE) { createManeuverTurnBitmap(context) }
        style.addImageOnce(MANEUVER_RING_IMAGE) { createManeuverRingBitmap(context) }
    }
    if (style.getSource(MANEUVER_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(MANEUVER_SOURCE_ID, EMPTY_FC))
    }
    if (style.getLayer(MANEUVER_GLYPH_LAYER_ID) != null) return

    val glyph = org.maplibre.android.style.layers.SymbolLayer(
        MANEUVER_GLYPH_LAYER_ID, MANEUVER_SOURCE_ID,
    )
    glyph.setProperties(
        PropertyFactory.iconImage(
            Expression.switchCase(
                Expression.get("roundabout"), Expression.literal(MANEUVER_RING_IMAGE),
                // No bearing from the router means no direction we can source
                // — and a symbol that cannot say where must not point. It
                // falls back to the non-directional disc.
                Expression.has("bearing"), Expression.literal(MANEUVER_TURN_IMAGE),
                Expression.literal(MANEUVER_RING_IMAGE),
            ),
        ),
        // MAP alignment makes icon-rotate a COMPASS BEARING; the value MUST be
        // bearingAfter, never the turn delta — feeding the delta is why the
        // arrows swept the glass on every heading change (owner: «люфтять»).
        PropertyFactory.iconRotate(Expression.get("bearing")),
        PropertyFactory.iconRotationAlignment(
            org.maplibre.android.style.layers.Property.ICON_ROTATION_ALIGNMENT_MAP,
        ),
        PropertyFactory.iconPitchAlignment(
            org.maplibre.android.style.layers.Property.ICON_PITCH_ALIGNMENT_MAP,
        ),
        PropertyFactory.iconAnchor(org.maplibre.android.style.layers.Property.ICON_ANCHOR_CENTER),
        // Маркери РОЗРІДЖУЮТЬСЯ. Раніше кожен ставився безумовно, і на міському
        // маршруті 10–40 дисків злипались у чорну гусінь уздовж лінії — саме
        // те, що власник назвав «кружки незрозумілого призначення». Тепер
        // маркер, який не має власного місця на склі, поступається; хто саме
        // лишається, вирішує [ManeuverPins.Pin.sortKey]: кільце й різкий
        // поворот — це рішення, плавний зʼїзд лінія показує й без значка.
        // icon-allow-overlap мусить бути літералом: вираз від даних MapLibre
        // мовчки відкидає разом із властивістю. Наступний маневр не поступається
        // не через overlap, а через `sort` = -1 — він перший у черзі розкладки,
        // тож витіснити його нікому.
        PropertyFactory.iconAllowOverlap(false),
        PropertyFactory.iconIgnorePlacement(false),
        PropertyFactory.iconPadding(4f),
        PropertyFactory.symbolSortKey(Expression.get("sort")),
        // Below this zoom the turns crowd into a caterpillar of arrows.
        // Решта планy лишається блідою: маршрут показує, що попереду ще сім
        // поворотів, і не претендує на увагу нарівні з тим, що зараз.
        // zoom() мусить лишатись прямим входом ЗОВНІШНЬОГО interpolate; та сама
        // крива, помножена на вибір за даними, — це zoom, схований усередині
        // `*`, і MapLibre відкидає властивість цілком. Тож розгалуження за
        // `active` переїхало у виходи зупинок.
        PropertyFactory.iconOpacity(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(12f, 0f),
                Expression.stop(
                    13.5f,
                    Expression.switchCase(
                        Expression.get("active"), Expression.literal(1f),
                        Expression.literal(MANEUVER_IDLE_OPACITY),
                    ),
                ),
            ),
        ),
        PropertyFactory.iconSize(
            Expression.interpolate(
                Expression.linear(), Expression.zoom(),
                Expression.stop(
                    13f,
                    Expression.switchCase(
                        Expression.get("active"),
                        Expression.literal(0.65f * MANEUVER_ACTIVE_SCALE),
                        Expression.literal(0.65f),
                    ),
                ),
                Expression.stop(
                    17f,
                    Expression.switchCase(
                        Expression.get("active"), Expression.literal(MANEUVER_ACTIVE_SCALE),
                        Expression.literal(1f),
                    ),
                ),
            ),
        ),
    )
    if (style.getLayer(OPERATOR_ARROW_LAYER) != null) {
        style.addLayerBelow(glyph, OPERATOR_ARROW_LAYER)
    } else {
        style.addLayer(glyph)
    }
}

/**
 * Feeds the maneuver markers; empty list clears them.
 *
 * [activeStep] — крок плану, що зараз попереду ([GuidanceProgress.stepIndex]);
 * -1 поза супроводом, і тоді жоден маркер не вдає, ніби він наступний.
 */
internal fun updateManeuverLayer(
    style: Style,
    pins: List<ManeuverPins.Pin>,
    activeStep: Int = -1,
) {
    installManeuverLayer(style)
    val source = style.getSourceAs<GeoJsonSource>(MANEUVER_SOURCE_ID) ?: return
    if (pins.isEmpty()) {
        source.setGeoJson(EMPTY_FC)
        return
    }
    source.setGeoJson(buildManeuverGeoJson(pins, activeStep))
}

internal fun buildManeuverGeoJson(
    pins: List<ManeuverPins.Pin>,
    activeStep: Int = -1,
): String = buildString {
    append("{\"type\":\"FeatureCollection\",\"features\":[")
    pins.forEachIndexed { i, pin ->
        if (i > 0) append(',')
        // Поза супроводом активного немає: -1 не збігається з жодним кроком,
        // і саме тому за замовчуванням стоїть він, а не 0.
        val active = pin.stepIndex >= 0 && pin.stepIndex == activeStep
        append("{\"type\":\"Feature\",\"geometry\":{\"type\":\"Point\",\"coordinates\":[")
        append(pin.lon).append(',').append(pin.lat)
        append("]},\"properties\":{\"rotate\":")
        append(pin.rotationDeg)
        // Written ONLY when the router gave one. Its absence is what makes the
        // layer draw the non-directional disc instead of pointing somewhere
        // nobody computed — see the iconImage switch in installManeuverLayer.
        pin.bearingDeg?.let { append(",\"bearing\":").append(it) }
        // Активний іде першим у черзі розкладки, інакше сусідній різкий поворот
        // міг би витіснити саме той маркер, заради якого все це малюється.
        append(",\"sort\":").append(if (active) -1f else pin.sortKey)
        append(",\"active\":").append(active)
        append(",\"roundabout\":").append(pin.roundabout)
        pin.exitNumber?.let { append(",\"exit\":").append(it) }
        append("}}")
    }
    append("]}")
}

/**
 * The arrow inside the maneuver disc, normalized to the bitmap. Every y sits
 * 0.0667 BELOW the shape it replaced so the shoelace centroid lands on 0.5 —
 * i.e. on the centre of the disc the symbol rotates about. Off by 1.74dp, it
 * orbited that centre on every heading change. MapSymbolContrastTest asserts
 * the centroid; do not nudge a vertex alone.
 */
internal val MANEUVER_ARROW_PATH = floatArrayOf(
    0.5f, 0.3467f,
    0.35f, 0.6167f,
    0.5f, 0.5367f,
    0.65f, 0.6167f,
)

/** The disc every maneuver symbol is drawn on: one theme-invariant face. */
private fun drawManeuverPlate(c: android.graphics.Canvas, size: Int, d: Float) {
    val cx = size / 2f
    val r = size * 0.42f
    c.drawCircle(
        cx, cx, r,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MapSymbolPalette.DARK.plate
        },
    )
    // The rim is drawn INSIDE the fill radius, not on it — half a stroke
    // sitting outside the circle is half a stroke off the bitmap edge.
    c.drawCircle(
        cx, cx, r - 0.75f * d,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MapSymbolPalette.DARK.plateRim
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * d
        },
    )
}

/** A turn arrow: stem, corner, head — the same shape the banner draws. */
internal fun createManeuverTurnBitmap(context: android.content.Context): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    // coerceAtLeast(1) guards Bitmap.createBitmap, nothing else: the old floor
    // was raw px against a dp size, which is a floor that cannot bind.
    val size = (26 * d).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val c = android.graphics.Canvas(bmp)
    val cx = size / 2f
    drawManeuverPlate(c, size, d)
    // The teal ring stays: it is what says «this belongs to the route», and
    // it is the one thing on the disc that may be the route's own colour.
    c.drawCircle(
        cx, cx, size * 0.42f - 2.4f * d,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ROUTE_CORE_COLOR
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.6f * d
        },
    )
    // Points north at bearing = 0; the layer turns it to the road's bearing.
    c.drawPath(
        pathOf(MANEUVER_ARROW_PATH, 0f, size.toFloat()),
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = MapSymbolPalette.DARK.plateInk
        },
    )
    return bmp
}

/** A roundabout marker: the ring, because the ring IS the instruction. */
internal fun createManeuverRingBitmap(context: android.content.Context): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (26 * d).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val c = android.graphics.Canvas(bmp)
    val cx = size / 2f
    drawManeuverPlate(c, size, d)
    c.drawCircle(
        cx, cx, size * 0.24f,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ROUTE_CORE_COLOR
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 2.6f * d
        },
    )
    return bmp
}

/** A small chevron drawn once and reused along the whole line. */
internal fun createRouteArrowBitmap(
    context: android.content.Context,
    isDark: Boolean,
): android.graphics.Bitmap {
    val d = context.resources.displayMetrics.density
    val size = (18 * d).toInt().coerceAtLeast(1)
    val bmp = android.graphics.Bitmap.createBitmap(
        size, size, android.graphics.Bitmap.Config.ARGB_8888,
    )
    val c = android.graphics.Canvas(bmp)
    val path = android.graphics.Path().apply {
        moveTo(size * 0.32f, size * 0.24f)
        lineTo(size * 0.72f, size * 0.5f)
        lineTo(size * 0.32f, size * 0.76f)
    }
    c.drawPath(
        path,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(MapTokens.routeInk(isDark))
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3.4f * d
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        },
    )
    c.drawPath(
        path,
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.parseColor(MapTokens.routeHalo(isDark))
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.8f * d
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        },
    )
    return bmp
}

/**
 * [trimAt] is where the operator stands. The road already covered is cut off
 * there, so what stays blue is only what is still to be done — see
 * [local.phantom.companion.core.nav.RouteTrim] for why a bad snap keeps the
 * whole line instead.
 */
internal fun updateRouteLayer(
    style: Style,
    routes: List<MapPayload.PolylineData>,
    isDark: Boolean,
    trimAt: Pair<Double, Double>? = null,
) {
    installRouteLayer(style, isDark)
    val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
    if (routes.isEmpty()) {
        source.setGeoJson(EMPTY_FC)
        return
    }
    val shown = if (trimAt == null) {
        routes
    } else {
        routes.mapNotNull { poly ->
            val ahead = local.phantom.companion.core.nav.RouteTrim.remaining(
                poly.points.map { it.lat to it.lon },
                trimAt.first,
                trimAt.second,
            )
            if (ahead.size < 2) {
                null
            } else {
                poly.copy(points = ahead.map { MapPayload.PointData(it.first, it.second) })
            }
        }
    }
    if (shown.isEmpty()) {
        source.setGeoJson(EMPTY_FC)
        return
    }
    source.setGeoJson(buildPolylinesGeoJson(shown, isDark))
}

/**
 * Runs one step of the `onStyleLoaded` sequence in isolation: a throw here
 * (bad style assumption, missing source, whatever) is logged and swallowed
 * instead of aborting every subsequent step. See the call site for why this
 * matters — before this, one bad step blanked the whole map silently.
 */
internal inline fun mapStyleStep(tag: String, block: () -> Unit) {
    runCatching(block).onFailure {
        android.util.Log.e("PhantomMapStyle", "step '$tag' failed: ${it.message}", it)
    }
}

/** Наступний маневр більший за решту рівно настільки, щоб око знайшло його
 *  без пошуку, і не настільки, щоб він читався як інший знак. */
internal const val MANEUVER_ACTIVE_SCALE = 1.35f

/** Решта плану — присутня, але не кричить. */
internal const val MANEUVER_IDLE_OPACITY = 0.55f

internal const val OPERATOR_SOURCE_ID = "phantom-operator-source"
internal const val OPERATOR_ARROW_LAYER = "phantom-operator-arrow"
internal const val OPERATOR_DOT_LAYER = "phantom-operator-dot"
internal const val OPERATOR_CONE_LAYER = "phantom-operator-cone"
internal const val OPERATOR_NOTCH_LAYER = "phantom-operator-notch"
internal const val OPERATOR_HALO_LAYER = "phantom-operator-halo"
internal const val OPERATOR_RING_LAYER = "phantom-operator-ring"
internal const val SEARCH_SOURCE_ID = "phantom-search-source"
internal const val SEARCH_LAYER = "phantom-search-layer"
internal const val ROUTE_SOURCE_ID = "phantom-route-source"
internal const val ROUTE_LAYER_ID = "phantom-route-layer"
internal const val ROUTE_DASHED_LAYER_ID = "phantom-route-dashed"
internal const val ROUTE_CASING_LAYER_ID = "phantom-route-casing"
internal const val ROUTE_ARROWS_LAYER_ID = "phantom-route-arrows"
internal const val ROUTE_REGISTER_SOURCE_ID = "phantom-route-register-source"
internal const val ROUTE_REGISTER_LAYER_ID = "phantom-route-register-layer"
internal const val ROUTE_REGISTER_REMEMBERED_LAYER_ID = "phantom-route-register-remembered"
internal const val MANEUVER_SOURCE_ID = "phantom-maneuver-source"
internal const val MANEUVER_RING_LAYER_ID = "phantom-maneuver-ring"
internal const val MANEUVER_GLYPH_LAYER_ID = "phantom-maneuver-glyph"
internal const val MANEUVER_TURN_IMAGE = "phantom-maneuver-turn"
internal const val MANEUVER_RING_IMAGE = "phantom-maneuver-ring-img"
internal const val ALT_ROUTE_SOURCE_ID = "phantom-alt-route-source"
internal const val ALT_ROUTE_LAYER_ID = "phantom-alt-route-layer"
internal const val ALT_ROUTE_CASING_LAYER_ID = "phantom-alt-route-casing"

/** Narrower than the chosen route's [ROUTE_CASING_EXTRA] — the alternative
 *  has to be findable, not equal. */
internal const val ALT_ROUTE_CASING_EXTRA = 2.5f
internal const val ALT_LABEL_SOURCE_ID = "phantom-alt-label-source"
internal const val ALT_LABEL_LAYER_ID = "phantom-alt-label-layer"

internal const val ROUTE_ARROW_IMAGE = "phantom-route-arrow"

/** Far enough apart to read as rhythm, not as a dotted line. */
internal const val ROUTE_ARROW_SPACING = 90f

/**
 * Cool teal, deliberately NOT the amber the puck and chrome own: a route the
 * same hue as the operator's own dot makes the two read as one smear. Teal sits
 * opposite amber on the wheel, so the line separates at a glance while still
 * belonging to the palette.
 */
internal val ROUTE_CORE_COLOR = android.graphics.Color.parseColor("#3FD9C4")

internal fun routeCoreColor(isDark: Boolean): Int =
    android.graphics.Color.parseColor(MapTokens.routeInk(isDark))

// The casing colour lives in MapSymbolPalette.routeCasing now — it has to
// flip with the basemap, and a second copy of it here is how a palette drifts.

internal const val ROUTE_CASING_EXTRA = 4f

/**
 * Width that tracks zoom. A fixed width is a blob smeared over a city at z10
 * and a thread you cannot follow at z18 — the line has to keep roughly the
 * same road-relative weight as the map scales under it.
 */
internal fun routeWidthExpression(extra: Float): Expression = Expression.interpolate(
    Expression.exponential(1.4f),
    Expression.zoom(),
    Expression.stop(10f, 2.5f + extra * 0.5f),
    Expression.stop(14f, 5f + extra * 0.75f),
    Expression.stop(16f, 8f + extra),
    Expression.stop(18f, 13f + extra),
    Expression.stop(20f, 20f + extra),
)
internal const val REVERSE_GEOCODE_MIN_MOVE_M = 150.0

/** Great-circle distance in metres between two (lat, lon) points. */
internal fun haversineMeters(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val (lat1, lon1) = a
    val (lat2, lon2) = b
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val sa = kotlin.math.sin(dLat / 2)
    val sb = kotlin.math.sin(dLon / 2)
    val cos1 = kotlin.math.cos(Math.toRadians(lat1))
    val cos2 = kotlin.math.cos(Math.toRadians(lat2))
    val h = sa * sa + cos1 * cos2 * sb * sb
    return 2 * r * kotlin.math.asin(kotlin.math.sqrt(h.coerceIn(0.0, 1.0)))
}
// ATLAS-1: рейка basemap більше не форсує чужих файлів стилю — вона перемикає
// полярність нашого (OBSIDIAN_NIGHT_STYLE / OBSIDIAN_DAY_STYLE). Ані Liberty,
// ані Positron звідси більше нікуди не тягнуться: вендор лишається вибором
// оператора в [resolveStyleUrls], а не тінню в чотирьох місцях коду.

internal const val EMPTY_FC = """{"type":"FeatureCollection","features":[]}"""
// ATLAS-3: KYIV_LAT/KYIV_LON жили тут як «нейтральний центр» і стояли в
// дванадцяти фолбеках. Дім тепер каже пак країни — `country/countryHome()`.
internal const val DEFAULT_ZOOM = 12.0
internal const val OPERATOR_ZOOM = 15.0

// M4-C01 — tactical camera: past this zoom the operator is close enough
// for the 3D building extrusions to read, so the view tilts into a
// perspective; below it, pitch snaps back to top-down for orientation.
internal const val TACTICAL_PITCH_ZOOM_THRESHOLD = 15.5

/** Where the lean begins and where it is complete. */
internal const val TACTICAL_PITCH_ZOOM_START = 14.5
internal const val TACTICAL_PITCH_ZOOM_FULL = 17.5
internal const val TACTICAL_PITCH_DEGREES = 55.0

/** Below this the easeCamera would be re-triggered by its own settle. */
internal const val TACTICAL_PITCH_EPS_DEG = 0.5

/**
 * The tilt the idle listener should ease to, or null when it must keep its
 * hands off the camera entirely.
 *
 * Two other authorities write this camera: the per-frame follow director
 * (which owns tilt while following) and the operator's own fingers. A 650 ms
 * easeCamera fired on the idle that lands between a fling and the next drag is
 * a camera the operator has to fight to move — the same "map won't respond"
 * shape as the follow loop writing under a pinch, which is why the gesture
 * guard covers this too.
 */
internal fun idlePitchTarget(
    following: Boolean,
    gesturing: Boolean,
    zoom: Double,
    tiltDeg: Double,
): Double? {
    if (following || gesturing) return null
    // Ramped, not a switch. A single threshold meant the map stayed dead flat
    // through the whole zoom and then snapped to 55° once, after the fingers
    // were off — which reads as "tilt is not automatic" (operator, 2026-08-08).
    val target = tiltForZoom(zoom)
    return target.takeIf { kotlin.math.abs(tiltDeg - it) > TACTICAL_PITCH_EPS_DEG }
}

/** Flat far out, leaning in as the streets come up. */
internal fun tiltForZoom(zoom: Double): Double = when {
    zoom <= TACTICAL_PITCH_ZOOM_START -> 0.0
    zoom >= TACTICAL_PITCH_ZOOM_FULL -> TACTICAL_PITCH_DEGREES
    else -> {
        val t = (zoom - TACTICAL_PITCH_ZOOM_START) /
            (TACTICAL_PITCH_ZOOM_FULL - TACTICAL_PITCH_ZOOM_START)
        TACTICAL_PITCH_DEGREES * t * t * (3 - 2 * t)
    }
}
