package local.phantom.companion.feature.stream.ui.map.indoor

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import local.phantom.companion.feature.stream.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.tokens.LocalPhantomColors
import local.phantom.companion.core.design.tokens.PhantomRadius
import local.phantom.companion.core.geo.indoor.BuildingEntrance
import local.phantom.companion.core.geo.indoor.BuildingIndoorPlan
import local.phantom.companion.core.geo.indoor.EntranceKind
import local.phantom.companion.core.geo.indoor.IndoorPassage
import local.phantom.companion.core.geo.indoor.IndoorRoom
import local.phantom.companion.core.geo.indoor.IndoorVenueCategory
import local.phantom.companion.core.geo.indoor.PassageKind
import local.phantom.companion.feature.stream.ui.map.icons.PhantomGlyphIcon
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import kotlin.math.roundToInt

/**
 * Диск входу. Менше 24dp — і палець у нього не влучає; 26dp — та сама тарілка,
 * яку носить пін місця ([MapPinFace.CONTENT_DP]), тож двері на землі одного
 * розміру з аптекою поруч.
 */
private const val ENTRANCE_BADGE_DP = 26
private const val ENTRANCE_GLYPH_DP = 14

/**
 * INDOOR-3 — Ultra-Crisp Map overlay for indoor corridors, passages, escalators, elevators,
 * stairs, building passages, rooms, and entrances.
 * Re-projects on every camera gesture with zero latency.
 */
@Composable
fun IndoorFloorOverlay(
    map: MapLibreMap?,
    plan: BuildingIndoorPlan?,
    currentLevel: Int,
    enabled: Boolean,
    onEntranceClick: (BuildingEntrance) -> Unit = {},
    onRoomClick: ((IndoorRoom) -> Unit)? = null,
    onPassageClick: ((IndoorPassage) -> Unit)? = null,
) {
    if (!enabled || plan == null || map == null) return

    val c = LocalPhantomColors.current
    val passages = remember(plan, currentLevel) { plan.passagesForLevel(currentLevel) }
    val rooms = remember(plan, currentLevel) { plan.roomsForLevel(currentLevel) }
    val entrances = remember(plan, currentLevel) { plan.entrancesForLevel(currentLevel) }

    // Підпис носить рівно один вхід — той, якого торкнулись. Стан живе ТУТ, а
    // не в MapUiState: він не переживає ні виходу з плану, ні зміни поверху, і
    // нікому за межами цього оверлея не потрібен.
    var selectedEntranceId by remember(plan, currentLevel) { mutableStateOf<String?>(null) }

    // Live frame ticker tied to MapLibre camera motion
    var cameraFrame by remember { mutableLongStateOf(0L) }
    DisposableEffect(map) {
        val listener = MapLibreMap.OnCameraMoveListener {
            cameraFrame++
        }
        map.addOnCameraMoveListener(listener)
        onDispose {
            map.removeOnCameraMoveListener(listener)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val _tick = cameraFrame
        val projection = map.projection

        // 1. Vector Passages, Corridors, Stairs, Escalators & Rooms
        Canvas(modifier = Modifier.fillMaxSize()) {
            val _t = _tick // force redraw on camera movement

            // A. Draw Rooms / Shops / Areas
            for (room in rooms) {
                if (room.polygon.size < 3) continue
                val path = Path()
                var first = true
                for ((lat, lon) in room.polygon) {
                    val screenPt = projection.toScreenLocation(LatLng(lat, lon))
                    if (first) {
                        path.moveTo(screenPt.x, screenPt.y)
                        first = false
                    } else {
                        path.lineTo(screenPt.x, screenPt.y)
                    }
                }
                path.close()

                val roomColor = IndoorPalette.room(room.venueCategory)

                // Room fill & glowing outline
                drawPath(
                    path = path,
                    color = roomColor.copy(alpha = 0.24f),
                )
                drawPath(
                    path = path,
                    color = roomColor.copy(alpha = 0.85f),
                    style = Stroke(width = 2.2.dp.toPx()),
                )
            }

            // B. Draw Passages / Escalators / Elevators / Stairs / Building Passages
            for (passage in passages) {
                if (passage.points.size < 2) continue

                val strokeColor = IndoorPalette.passage(passage.kind)
                val (strokeWidth, pathEffect) = when (passage.kind) {
                    PassageKind.ESCALATOR ->
                        5.0.dp.toPx() to PathEffect.dashPathEffect(floatArrayOf(12f, 6f), 0f)
                    PassageKind.ELEVATOR -> 6.0.dp.toPx() to null
                    PassageKind.STEPS ->
                        4.0.dp.toPx() to PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    PassageKind.BUILDING_PASSAGE ->
                        4.5.dp.toPx() to PathEffect.dashPathEffect(floatArrayOf(14f, 8f), 0f)
                    PassageKind.MOVING_WALKWAY ->
                        4.5.dp.toPx() to PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)
                    else -> 2.5.dp.toPx() to null
                }

                for (i in 0 until passage.points.size - 1) {
                    val p1 = projection.toScreenLocation(LatLng(passage.points[i].first, passage.points[i].second))
                    val p2 = projection.toScreenLocation(LatLng(passage.points[i + 1].first, passage.points[i + 1].second))

                    // Background halo glow for passage
                    drawLine(
                        color = strokeColor.copy(alpha = 0.35f),
                        start = Offset(p1.x, p1.y),
                        end = Offset(p2.x, p2.y),
                        strokeWidth = strokeWidth + 4.dp.toPx(),
                    )
                    // Foreground crisp stroke
                    drawLine(
                        color = strokeColor,
                        start = Offset(p1.x, p1.y),
                        end = Offset(p2.x, p2.y),
                        strokeWidth = strokeWidth,
                        pathEffect = pathEffect,
                    )
                }
            }
        }

        // 2. Vertical Transit Markers (Elevators & Escalators)
        // Прохід крізь будівлю чи паркан уже намальовано лінією — слово «Прохід» над
        // кожним шлагбаумом лише налазило одне на одне (кадр A52 26.09, Острава).
        // Підписуються тільки ліфт, ескалатор і сходи, і не ближче ніж BADGE_GAP один до одного.
        val placed = mutableListOf<Offset>()
        val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { BADGE_GAP.toPx() }
        for (passage in passages) {
            if (passage.kind == PassageKind.ELEVATOR || passage.kind == PassageKind.ESCALATOR || passage.kind == PassageKind.STEPS) {
                val pt = passage.points.firstOrNull() ?: continue
                val screenPt = projection.toScreenLocation(LatLng(pt.first, pt.second))
                if (screenPt.x < 0 || screenPt.x > constraints.maxWidth ||
                    screenPt.y < 0 || screenPt.y > constraints.maxHeight
                ) continue
                val at = Offset(screenPt.x, screenPt.y)
                if (placed.any { (it - at).getDistance() < gapPx }) continue
                placed += at

                // Слово, не піктограма: emoji на мапі — операторський вміст, а не
                // мова форми (кадр A52 04.09: двері-емодзі та «Прохід» на вуличному шлагбаумі).
                // Слово — з ресурсів, у мові оператора (див. strings_indoor_badges.xml).
                // Колір — не мова: тип каже слово, а плашка — та сама піднята поверхня мапи.
                val badgeWord = when (passage.kind) {
                    PassageKind.ELEVATOR -> R.string.indoor_badge_elevator
                    PassageKind.ESCALATOR -> R.string.indoor_badge_escalator
                    else -> R.string.indoor_badge_steps
                }
                val badgeLabel = stringResource(badgeWord)

                IndoorBadge(
                    text = badgeLabel,
                    modifier = Modifier
                        .offset { IntOffset(screenPt.x.roundToInt() - 32, screenPt.y.roundToInt() - 14) },
                    onClick = { onPassageClick?.invoke(passage) },
                )
            }
        }

        // 3. Room & Shop Badges
        for (room in rooms) {
            // Безіменна кімната — форма, не місце: сирий тег («parking» ×6 на
            // кадрі власника) словом не стає. Див. indoorRoomLabel. Слово
            // орієнтира — з ресурсів, у мові оператора, дістається тут.
            val label = when (val word = indoorRoomLabel(room.name, room.category)) {
                null -> continue
                is IndoorRoomLabel.Named -> word.text
                is IndoorRoomLabel.Landmark -> stringResource(word.word)
            }
            if (room.polygon.isEmpty()) continue
            val centerLat = room.polygon.map { it.first }.average()
            val centerLon = room.polygon.map { it.second }.average()
            val screenPt = projection.toScreenLocation(LatLng(centerLat, centerLon))

            if (screenPt.x < 0 || screenPt.x > constraints.maxWidth ||
                screenPt.y < 0 || screenPt.y > constraints.maxHeight
            ) continue

            IndoorBadge(
                text = label,
                modifier = Modifier
                    .offset { IntOffset(screenPt.x.roundToInt() - 40, screenPt.y.roundToInt() - 12) },
                onClick = { onRoomClick?.invoke(room) },
            )
        }

        // 4. Entrance Portals
        val camera = map.cameraPosition
        val labelled = EntranceLabelRule.visible(
            entrances = entrances,
            zoom = camera.zoom,
            focusLat = camera.target?.latitude ?: 0.0,
            focusLon = camera.target?.longitude ?: 0.0,
        )
        for (ent in labelled) {
            val screenPt = projection.toScreenLocation(LatLng(ent.lat, ent.lon))
            if (screenPt.x < 0 || screenPt.x > constraints.maxWidth ||
                screenPt.y < 0 || screenPt.y > constraints.maxHeight
            ) continue

            val kind = EntranceSign.kindOf(ent)
            val bgCol = EntranceSign.tint(kind)
            val entranceWord = when (kind) {
                EntranceKind.MAIN -> R.string.indoor_entrance_main
                EntranceKind.SUBWAY -> R.string.indoor_entrance_subway
                EntranceKind.EMERGENCY -> R.string.indoor_entrance_emergency
                EntranceKind.ACCESSIBLE -> R.string.indoor_entrance_accessible
                EntranceKind.SERVICE, EntranceKind.GENERIC -> R.string.indoor_entrance_default
            }
            val word = stringResource(entranceWord)
            val named = selectedEntranceId == ent.id

            // Знак сидить НА дверях: зсув — половина значка, а не навмання
            // підібрані 36×15 старої плашки, які клали текст збоку від точки.
            Box(
                modifier = Modifier
                    // Проєкція віддає ПІКСЕЛІ, диск задано в dp: без переводу
                    // знак зʼїжджав би з дверей рівно на щільність екрана.
                    .offset {
                        val half = ENTRANCE_BADGE_DP.dp.roundToPx() / 2
                        IntOffset(
                            screenPt.x.roundToInt() - half,
                            screenPt.y.roundToInt() - half,
                        )
                    }
                    .size(ENTRANCE_BADGE_DP.dp)
                    .background(bgCol.copy(alpha = 0.95f), shape = CircleShape)
                    // Обідок кольору землі, а не білий: білий зникав на денній мапі
                    // й світився плямою на нічній. Поверхня теми відділяє знак від
                    // будь-якої землі під ним.
                    .border(1.5.dp, c.surface, CircleShape)
                    .clickable {
                        selectedEntranceId = if (named) null else ent.id
                        onEntranceClick(ent)
                    },
                contentAlignment = Alignment.Center,
            ) {
                PhantomGlyphIcon(
                    glyph = EntranceSign.glyph(kind),
                    tint = c.onAct,
                    modifier = Modifier.size(ENTRANCE_GLYPH_DP.dp),
                )
            }

            // Слово — рівно одному входу, тому, якого торкнулись. Вісім слів
            // одночасно і були тією скаргою, через яку знак прийшов на їх місце.
            if (named) {
                IndoorBadge(
                    text = if (ent.ref != null) "$word №${ent.ref}" else (ent.name ?: word),
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                screenPt.x.roundToInt() - 36,
                                screenPt.y.roundToInt() + ENTRANCE_BADGE_DP.dp.roundToPx() / 2 + 4,
                            )
                        },
                    // Рід входу — крапкою його ж кольору перед словом: колір
                    // лишається знаком на землі, слово — словом на плашці.
                    dot = bgCol,
                )
            }
        }
    }
}

private val BADGE_GAP = 56.dp

/**
 * Плашка на плані: піднята поверхня, волосина, caption — та сама мова, що й
 * пігулки мапи, лише дрібніше, бо лежить на самій землі.
 */
@Composable
private fun IndoorBadge(
    text: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    dot: Color? = null,
) {
    val c = LocalPhantomColors.current
    val shape = RoundedCornerShape(PhantomRadius.chip)
    Row(
        modifier = modifier
            .clip(shape)
            .background(c.raised)
            .border(1.dp, c.line, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
            androidx.compose.foundation.layout.Spacer(Modifier.size(4.dp))
        }
        Text(
            text = text,
            style = PhantomText.caption,
            fontWeight = FontWeight.SemiBold,
            color = c.text,
            maxLines = 1,
        )
    }
}
