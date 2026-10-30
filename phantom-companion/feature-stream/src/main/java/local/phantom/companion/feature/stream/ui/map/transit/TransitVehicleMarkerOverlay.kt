package local.phantom.companion.feature.stream.ui.map.transit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.phantom.companion.feature.stream.R
import local.phantom.companion.core.data.transit.TransitRepository
import local.phantom.companion.core.data.transit.TransitVehicle
import local.phantom.companion.core.data.transit.raptor.TransitMode
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.feature.stream.ui.map.MapChromeMetrics
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import local.phantom.companion.feature.stream.ui.map.icons.glyph
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Номер маршруту так, щоб його не можна було прочитати як хвилини.
 *
 * Гонтлет: «транзитні чіпи ~36 — маршрут чи хвилини?» (4 персони). Голе число
 * поруч із рухомою одиницею однаково законно читається як «через 36 хв», а «~»
 * перед ним доводило справу до кінця — це рівно те, як пишуть ОЦІНКУ часу.
 * Тому число завжди носить знак номера, а «~» пішло з нього геть: тильда за
 * доктриною означає «позиція модельна», і тепер вона живе окремим кутовим
 * чипом, де стосується позиції, а не маршруту.
 *
 * [prefix] приходить з ресурсів (укр. «№», англ. «#»). Префікс, який уже є в
 * стрічці фіда, не дублюється: частина GTFS-джерел віддає ref разом із ним.
 * Порожній ref не отримує самотнього «№» — це був би номер, якого немає.
 */
internal fun transitPillRoute(routeRef: String?, prefix: String): String {
    val ref = routeRef?.trim().orEmpty()
    if (ref.isEmpty() || local.phantom.companion.core.net.transit.LineLabel.looksLikeId(ref)) return ""
    return if (ref.startsWith(prefix)) ref else prefix + ref
}

/** Нижче цього зуму пігулки — сміття, а не інформація. */
private const val MIN_PILL_ZOOM = 13.0

/** Стеля кадру: сорок пігулок і 60 fps, більше не тримає жоден із двох. */
private const val MAX_PILLS = 40

/** Порожнеча між двома пігулками, за якою вони ще читаються як дві. */
private val PILL_GAP = 4.dp

/** Верх екрана: тривога, вирок про позицію, банер маневру. */
private val TOP_SAFE = 96.dp

/** Низ: смуга відповіді, HUD транспорту, навігаційний острівець. */
private val BOTTOM_SAFE = 152.dp

/** Геометрія пігулки в dp — те, з чого рахується її коробка зіткнень. */
private val PILL_HEIGHT = 22.dp
private val PILL_PAD_H = 5.dp
private val PILL_ICON = 11.dp
private val PILL_ITEM_GAP = 3.dp
private val PILL_LIVE_DOT = 4.dp

/** Ширина знака в 11sp Bold, заміряна по цифрах — вони тут майже все. */
private val PILL_CHAR_W = 6.6.dp

/**
 * Пігулки транспорту над рухомими одиницями: силует виду, номер маршруту,
 * стрілка курсу, тап-вибір.
 *
 * Вид несе ГЛІФ родини, а не символ із системного шрифту: на одному телефоні
 * тролейбус малювався жовтим автобусом, на іншому — квадратом. Модельної
 * одиниці тут більше немає взагалі ([glassFleet]): приглушена пігулка з
 * тильдою однаково обіцяла машину, яку неможливо зустріти.
 *
 * ГОНТЛЕТ-1 §5: «чіпи наїжджають один на одного». Стеля в 40 одиниць їх
 * прорідила, але жодна пігулка не знала про сусідку — на z14 маршрути йдуть
 * одним проспектом, і чотири номери зливались у пляму. Тепер розкладка та
 * сама, якою колись жили POI-мітки: коробки AABB, ближчий до центра екрана
 * виграє тісноту, а прохід іде на ЗУПИНЦІ камери. Під час руху пігулки лише
 * переїжджають — у фазі РОЗКЛАДКИ, тож пан більше не перескладає сорок вузлів
 * на кожен кадр камери (це була друга половина рахунку за тепло).
 */
@Composable
fun TransitVehicleMarkerOverlay(
    map: MapLibreMap?,
    repository: TransitRepository?,
    enabled: Boolean,
    onVehicleClick: (TransitVehicle) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (!enabled || map == null || repository == null) return

    val fleet by repository.vehicles.collectAsStateWithLifecycle()
    val vehicles = glassFleet(fleet)
    if (vehicles.isEmpty()) return

    val routePrefix = stringResource(R.string.map_transit_route_prefix)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val viewW = constraints.maxWidth.toFloat()
        val viewH = constraints.maxHeight.toFloat()
        val px = with(LocalDensity.current) {
            PillSpace(
                height = PILL_HEIGHT.toPx(),
                padH = PILL_PAD_H.toPx(),
                icon = PILL_ICON.toPx(),
                itemGap = PILL_ITEM_GAP.toPx(),
                liveDot = PILL_LIVE_DOT.toPx(),
                charW = PILL_CHAR_W.toPx(),
                gap = PILL_GAP.toPx(),
                topSafe = TOP_SAFE.toPx(),
                bottomSafe = BOTTOM_SAFE.toPx(),
                rightSafe = MapChromeMetrics.RailBand.toPx(),
            )
        }

        var placed by remember { mutableStateOf<List<PlacedVehicle>>(emptyList()) }
        val liveVehicles by rememberUpdatedState(vehicles)
        val livePrefix by rememberUpdatedState(routePrefix)

        // Кут камери живе окремим станом, бо його читає ФАЗА МАЛЮВАННЯ стрілки:
        // прочитаний у композиції, він перескладав би всі сорок пігулок на
        // кожен градус повороту.
        val mapBearing = remember { mutableFloatStateOf(0f) }

        fun recompute() {
            val cam = map.cameraPosition
            mapBearing.floatValue = cam.bearing.toFloat()
            if (cam.zoom < MIN_PILL_ZOOM || viewW <= 0f || viewH <= 0f) {
                if (placed.isNotEmpty()) placed = emptyList()
                return
            }
            val projection = map.projection
            val candidates = liveVehicles.map { v ->
                val pt = projection.toScreenLocation(LatLng(v.lat, v.lon))
                PillSlot(v, pt.x, pt.y, px.widthOf(transitPillRoute(v.routeLabel, livePrefix), v))
            }
            val kept = placePills(candidates, px, viewW, viewH)

            // Той самий набір у тому ж порядку — зсуваємо наявні пігулки на
            // місці, без нового списку і без рекомпозиції.
            val current = placed
            val sameSet = current.size == kept.size &&
                current.indices.all { current[it].vehicle.vehicleId == kept[it].vehicle.vehicleId }
            if (sameSet) {
                kept.forEachIndexed { i, slot -> current[i].moveTo(slot.x, slot.y) }
            } else {
                placed = kept.map { PlacedVehicle(it.vehicle, it.width, it.x, it.y) }
            }
        }

        fun reproject() {
            val projection = map.projection
            mapBearing.floatValue = map.cameraPosition.bearing.toFloat()
            placed.forEach { p ->
                val pt = projection.toScreenLocation(LatLng(p.vehicle.lat, p.vehicle.lon))
                p.moveTo(pt.x, pt.y)
            }
        }

        DisposableEffect(map, viewW, viewH, px) {
            recompute()
            val move = MapLibreMap.OnCameraMoveListener { reproject() }
            val idle = MapLibreMap.OnCameraIdleListener { recompute() }
            map.addOnCameraMoveListener(move)
            map.addOnCameraIdleListener(idle)
            onDispose {
                map.removeOnCameraMoveListener(move)
                map.removeOnCameraIdleListener(idle)
            }
        }
        LaunchedEffect(vehicles) { recompute() }

        for (p in placed) {
            TransitPill(
                placed = p,
                routeLabel = transitPillRoute(p.vehicle.routeLabel, routePrefix),
                halfHeightPx = px.height / 2f,
                mapBearing = { mapBearing.floatValue },
                onClick = { onVehicleClick(p.vehicle) },
            )
        }
    }
}

/**
 * Пігулка машини, 03.10 — шкіра v1.2.
 *
 * Була майже чорна `#070B14` з білим номером на будь-якій мапі, і на денній
 * землі читалась як дірка. Тепер тло — `surface` (тон аркуша над мапою), номер —
 * `text`, крапка «наживо» — `success`, стрілка курсу — власний вектор. Колір
 * виду ([TransitModeTint]) лишився там, де він несе вид: обідок і силует.
 */
@Composable
private fun TransitPill(
    placed: PlacedVehicle,
    routeLabel: String,
    halfHeightPx: Float,
    mapBearing: () -> Float,
    onClick: () -> Unit,
) {
    val c = LocalPhantomColors.current
    val v = placed.vehicle
    // The mode is carried from the feed, never read out of the id text:
    // a GTFS-RT tram numbered "bus_17" used to render as a bus.
    val badgeColor = TransitModeTint.of(v.mode)

    Box(
        modifier = Modifier
            // Координати читає РОЗКЛАДКА, не композиція: пігулка їде за мапою,
            // не перескладаючись.
            .offset {
                IntOffset(
                    (placed.x - placed.width / 2f).roundToInt(),
                    (placed.y - halfHeightPx).roundToInt(),
                )
            }
            .clickable { onClick() },
    ) {
        Box(
            modifier = Modifier
                // Tight, а не Pill: капсула в цій системі означає СТАН, і номер
                // маршруту в ній читався б як ще одна пігулка тривоги.
                .clip(PILL_SHAPE)
                .background(c.surface.copy(alpha = 0.94f))
                .border(1.5.dp, badgeColor, PILL_SHAPE)
                .padding(horizontal = PILL_PAD_H, vertical = 2.dp),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(PILL_ITEM_GAP),
            ) {
                Box(
                    modifier = Modifier
                        .size(PILL_LIVE_DOT)
                        // Радіус на пів сторони — це коло, і саме ним воно
                        // й було: 2dp у 4dp-коробці.
                        .clip(CircleShape)
                        .background(c.success),
                )
                PhantomGlyphIcon(
                    glyph = v.mode.glyph(),
                    tint = badgeColor,
                    modifier = Modifier.size(PILL_ICON),
                )
                if (routeLabel.isNotEmpty()) {
                    Text(
                        text = routeLabel,
                        color = c.text,
                        style = PILL_TEXT,
                        maxLines = 1,
                    )
                }
                v.bearingDegrees?.let { deg ->
                    Icon(
                        painter = painterResource(R.drawable.ms_bearing),
                        contentDescription = null,
                        tint = badgeColor,
                        modifier = Modifier
                            .size(PILL_ICON)
                            // Кут читається у фазі малювання: мапа під пальцем
                            // повертається щокадру.
                            .graphicsLayer { rotationZ = deg - mapBearing() },
                    )
                }
            }
        }
    }
}

/** Кут пігулки — `PhantomRadius.chip`, як у бейджа лінії на аркуші. */
private val PILL_SHAPE = RoundedCornerShape(PhantomRadius.chip)

/** Номер: підпис v1.2 у 11sp Bold з `tnum` — ширина знака [PILL_CHAR_W] заміряна саме на ньому. */
private val PILL_TEXT = PhantomText.caption.copy(
    fontSize = 11.sp,
    lineHeight = 14.sp,
    fontWeight = FontWeight.Bold,
    fontFeatureSettings = "tnum",
)

/** Один кандидат одного проходу розкладки — живе рівно до кінця перерахунку. */
internal class PillSlot(
    val vehicle: TransitVehicle,
    val x: Float,
    val y: Float,
    val width: Float,
)

/**
 * Хто з кандидатів лишається на склі.
 *
 * Чистий і без MapLibre — проєкція вже зроблена, тут лише геометрія екрана.
 * Три правила поспіль:
 *
 *  1. **Уся пігулка на склі.** Чіп, розрізаний краєм екрана або накритий
 *     декою, — та сама нечитаність, тільки чужими руками. Права смуга — це
 *     виміряна ширина деки ([MapChromeMetrics.RailBand]), не здогадка.
 *  2. **Ближчий до центра екрана виграє тісноту.** Око читає центр; те, що
 *     людина щойно навела під палець, не має програвати периферії.
 *  3. **Коробки AABB, роздуті на проміжок.** Дві пігулки, що торкаються
 *     боками, — це вже пляма, а не два номери.
 */
internal fun placePills(
    candidates: List<PillSlot>,
    space: PillSpace,
    viewW: Float,
    viewH: Float,
    max: Int = MAX_PILLS,
): List<PillSlot> {
    val cx = viewW / 2f
    val cy = viewH / 2f
    val onGlass = candidates.filter { c ->
        val halfW = c.width / 2f
        c.x - halfW >= 0f && c.x + halfW <= viewW - space.rightSafe &&
            c.y >= space.topSafe && c.y <= viewH - space.bottomSafe
    }.sortedBy { hypot(it.x - cx, it.y - cy) }

    val kept = ArrayList<PillSlot>(max)
    val boxes = ArrayList<FloatArray>(max)
    for (c in onGlass) {
        if (kept.size >= max) break
        val box = floatArrayOf(
            c.x - c.width / 2f - space.gap,
            c.y - space.height / 2f - space.gap,
            c.x + c.width / 2f + space.gap,
            c.y + space.height / 2f + space.gap,
        )
        val clash = boxes.any { b ->
            box[0] < b[2] && box[2] > b[0] && box[1] < b[3] && box[3] > b[1]
        }
        if (clash) continue
        boxes += box
        kept += c
    }
    return kept
}

/**
 * Пігулка на склі. Координати — стан, який читає лише `Modifier.offset`, тобто
 * фаза розкладки.
 */
@Stable
private class PlacedVehicle(
    val vehicle: TransitVehicle,
    val width: Float,
    x: Float,
    y: Float,
) {
    var x by mutableFloatStateOf(x)
        private set
    var y by mutableFloatStateOf(y)
        private set

    fun moveTo(newX: Float, newY: Float) {
        if (x != newX) x = newX
        if (y != newY) y = newY
    }
}

/**
 * Виміри проходу розкладки, зведені з dp у пікселі один раз на розкладку.
 *
 * Ширина — ОЦІНКА, і свідомо: справжній обмір тексту вимагав би скласти сорок
 * пігулок, щоб дізнатися, які з них складати. Оцінка завищена (знак ширший за
 * середню цифру), тож помилка йде в бік зайвої порожнечі, а не накладання.
 */
internal data class PillSpace(
    val height: Float,
    val padH: Float,
    val icon: Float,
    val itemGap: Float,
    val liveDot: Float,
    val charW: Float,
    val gap: Float,
    val topSafe: Float,
    val bottomSafe: Float,
    val rightSafe: Float,
) {
    fun widthOf(routeLabel: String, vehicle: TransitVehicle): Float = widthOf(
        routeLabel = routeLabel,
        live = vehicle.isLiveGps,
        hasBearing = vehicle.bearingDegrees != null,
    )

    fun widthOf(routeLabel: String, live: Boolean, hasBearing: Boolean): Float {
        var items = 1 // силует виду завжди є
        var content = icon
        if (live) {
            items++
            content += liveDot
        }
        if (routeLabel.isNotEmpty()) {
            items++
            content += routeLabel.length * charW
        }
        if (hasBearing) {
            items++
            content += icon
        }
        return content + (items - 1) * itemGap + 2 * padH
    }
}
