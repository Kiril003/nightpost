package local.phantom.companion.feature.stream.ui.map.nav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.component.ChipEmphasis
import local.phantom.companion.core.design.component.ChipTone
import local.phantom.companion.core.design.component.PhantomChip
import local.phantom.companion.core.design.motion.MotionTokens
import local.phantom.companion.core.design.motion.PhantomMotion
import local.phantom.companion.core.design.motion.PhantomMotion.EMPHASIS_OVERSHOOT
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomCaption
import local.phantom.companion.core.design.theme.PhantomDataText
import local.phantom.companion.core.design.theme.PhantomKicker
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.onAccentInk
import local.phantom.companion.core.design.theme.PhantomSans
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.design.theme.PhantomTouchFloor
import local.phantom.companion.core.nav.DeadlineWatch
import local.phantom.companion.core.nav.GuidanceProgress
import local.phantom.companion.core.nav.GuidanceSession
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.TransitSteps
import local.phantom.companion.core.nav.landmarkLead
import local.phantom.companion.core.nav.maneuverLines
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.GroundRegister
import local.phantom.companion.feature.stream.ui.map.MapText
import local.phantom.companion.feature.stream.ui.map.MapUiState
import local.phantom.companion.feature.stream.ui.map.fmtDistance
import local.phantom.companion.feature.stream.ui.map.fmtDuration
import local.phantom.companion.feature.stream.ui.map.staleNoteRes
import local.phantom.companion.feature.stream.ui.map.tint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** NAV-2 — GUIDE chrome: maneuver card on top, ETA strip at the bottom. */

private val TURN_TILE = 52.dp
private val TURN_GLYPH = 34.dp
private val ARRIVE_TILE = 44.dp
private val ARRIVE_GLYPH = 22.dp
private val NEXT_GLYPH = 24.dp
private val ICON = 18.dp
private val SIGN = 36.dp
private val HAIRLINE = 1.dp
private val ROUTE_BAR = 2.dp

/**
 * Смуга підходу — чітка, помітна периферійним зором водія.
 */
private val APPROACH_BAR = 6.dp

private val DISTANCE_SP = 32.sp
private val UNIT_SP = 15.sp
private val ACTION_SP = 19.sp
private val STREET_SP = 15.sp
private val ETA_SP = 22.sp

/** Скільки тримати ✕, щоб зупинити навігацію. */
internal const val STOP_HOLD_MS = 800

/** Скільки живе підказка після короткого тапу по ✕. */
private const val STOP_HINT_MS = 2_500L

/** Мовчання GPS довше за це вже треба називати вголос. */
private const val GPS_SILENCE_TICK_MS = 10_000L

private val STOP_RING = 3.dp

private enum class CountdownUnit { NONE, METRES, KILOMETRES }

/** Пороги відліку живуть в одному місці — інакше дві його форми розійдуться. */
private fun countdown(m: Double): Pair<String?, CountdownUnit> = when {
    !m.isFinite() || m < 15.0 -> null to CountdownUnit.NONE
    m < 1000.0 -> "${(m / 10.0).roundToInt() * 10}" to CountdownUnit.METRES
    m < 10_000.0 ->
        String.format(Locale.US, "%.1f", m / 1000.0).replace('.', ',') to CountdownUnit.KILOMETRES
    else -> "${(m / 1000.0).roundToInt()}" to CountdownUnit.KILOMETRES
}

/** Big countdown split into value + unit so they can be typeset apart. */
internal fun distanceParts(m: Double): Pair<String, String> {
    val (value, unit) = countdown(m)
    return (value ?: MapText.s(R.string.mapl_g_now).ifEmpty { "now" }) to when (unit) {
        CountdownUnit.NONE -> ""
        CountdownUnit.METRES -> MapText.s(R.string.mapl_g_unit_m).ifEmpty { "m" }
        CountdownUnit.KILOMETRES -> MapText.s(R.string.mapl_g_unit_km).ifEmpty { "km" }
    }
}

/** Те саме число, але словами з ресурсів. Стара сигнатура лишається: її читає
 *  екран кріплення, якого цей фронт не чіпає. */
internal fun distanceParts(
    res: android.content.res.Resources,
    m: Double,
): Pair<String, String> {
    val (value, unit) = countdown(m)
    return (value ?: res.getString(R.string.nav_distance_now)) to when (unit) {
        CountdownUnit.NONE -> ""
        CountdownUnit.METRES -> res.getString(R.string.map_unit_m)
        CountdownUnit.KILOMETRES -> res.getString(R.string.map_unit_km)
    }
}

/**
 * Що саме банер має право показати на цьому кадрі.
 *
 * Поза маршрутом [Maneuver] не існує — і це головне тут. Раніше банер вище
 * далі малював маневр і відстань СТАРОГО маршруту звичайною фарбою, а стан
 * «усе на екрані вже неправда» отримував рядок 10sp під ними. Числа, яких дані
 * більше не підтверджують, не гаснуть кольором — вони зникають.
 */
internal sealed interface BannerFace {
    data object Waiting : BannerFace
    data object OffRoute : BannerFace
    data class Maneuver(
        val distanceM: Double,
        val type: String?,
        val modifier: String?,
        val roadName: String?,
    ) : BannerFace
}

/**
 * @param positionTrusted чи віримо ми зараз власній позиції.
 *
 * **Навіщо цей параметр.** Власник надіслав кадр, де на екрані одночасно стоять
 * два наші написи:
 * ```
 * «Поза маршрутом — перепрокладаю…»
 * «Глушіння GPS — тримаю останню певну позицію, коло сумніву росте»
 * ```
 * Перший каже, що ми знаємо, де людина, і вона не там; другий — що ми не знаємо,
 * де вона, і тому нічого не міняємо. Обидва наші, обидва в одну мить. Це і є
 * буквальне джерело слова «брєд».
 *
 * **І суперечність тут не косметична, а логічна:** «поза маршрутом» — висновок,
 * ВИВЕДЕНИЙ із позиції. Якщо позиції ми не довіряємо, то й висновку з неї робити
 * не можемо: ми не знаємо, що людина зʼїхала — ми знаємо лише, що не знаємо, де
 * вона. Тому вирок про довіру старший за вирок про маршрут, а не рівний йому.
 *
 * Правило: **на екрані один стан руху за раз, і про позицію говорить той, хто
 * про неї знає.** Ведення мовчить про зʼїзд, а причину несе смуга довіри.
 */
internal fun bannerFace(
    progress: GuidanceProgress?,
    positionTrusted: Boolean = true,
): BannerFace = when {
    progress == null -> BannerFace.Waiting
    // Недовірена позиція знімає ПРАВО стверджувати зʼїзд, а не сам зʼїзд.
    progress.offRoute && !positionTrusted -> BannerFace.Waiting
    progress.offRoute -> BannerFace.OffRoute
    else -> BannerFace.Maneuver(
        distanceM = progress.distanceToManeuverM,
        type = progress.maneuverType,
        modifier = progress.maneuverModifier,
        roadName = progress.roadName,
    )
}

/** Поза маршрутом ETA лишається оцінкою по старій лінії — і каже це «≈». */
internal fun etaIsEstimate(progress: GuidanceProgress?): Boolean = progress?.offRoute == true

/** Локальна роль погляду. У core-design їй нема чого робити, поки споживач один. */
@Composable
private fun GlanceText(
    text: String,
    size: TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Medium,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        color = color,
        fontFamily = PhantomSans,
        fontSize = size,
        fontWeight = weight,
        lineHeight = size * 1.25f,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
internal fun ManeuverBanner(
    progress: GuidanceProgress?,
    /**
     * Чи віримо позиції. `false` знімає з банера право казати «поза маршрутом»:
     * цей висновок виведено з позиції, і без довіри до неї він не наш. Причину
     * несе смуга довіри — див. [bannerFace].
     */
    positionTrusted: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalPhantomTheme.current.primary

    // Landscape is the car mount: the banner becomes a left rail so the
    // road stays visible instead of being pushed under the chrome.
    local.phantom.companion.feature.stream.ui.map.MapFloatingSurface(
        modifier = Modifier
            .fillMaxWidth(local.phantom.companion.core.design.layout.chromeWidthFraction())
            .let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it },
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            val face = bannerFace(progress, positionTrusted)
            when {
                face is BannerFace.Maneuver && progress != null -> {
                    ManeuverFace(progress, face, theme.inkStrong, accent)
                    if (progress.laneApproach) {
                        local.phantom.companion.feature.stream.ui.map.nav.lanes.LaneStrip(
                            lanes = progress.lanes,
                            maneuverModifier = progress.maneuverModifier,
                            accent = accent,
                            dim = theme.inkSecondary,
                        )
                    }
                    ApproachBar(progress.stepFraction, accent, theme.glassBorder)
                }
                face is BannerFace.OffRoute -> OffRouteFace()
                else -> WaitingFace()
            }
        }
    }
}

@Composable
private fun WaitingFace() {
    val theme = LocalPhantomTheme.current
    // Кільце, що крутиться, не називає стану і крутиться вічно. Слово каже те
    // саме й гасне разом із очікуванням — а коли мовчання затягується, воно
    // ще й каже, наскільки.
    val silentMinutes by produceState(0) {
        val since = System.currentTimeMillis()
        while (true) {
            value = ((System.currentTimeMillis() - since) / 60_000L).toInt()
            kotlinx.coroutines.delay(GPS_SILENCE_TICK_MS)
        }
    }
    Column(
        modifier = Modifier.padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro),
    ) {
        GlanceText(
            text = stringResource(R.string.nav_waiting_gps),
            size = ACTION_SP,
            color = theme.inkSecondary,
        )
        if (silentMinutes >= 1) {
            PhantomCaption(
                text = stringResource(R.string.nav_gps_silent, silentMinutes),
                color = theme.coral,
                maxLines = 1,
            )
        }
    }
}

/**
 * Поза маршрутом — перехоплення банера, не рядок-шепіт.
 *
 * Маневр і відстань старого маршруту тут не показуються взагалі, і смуга
 * підходу гасне разом із ними: на швидкості цей стан означає «все, що на
 * екрані, вже неправда», і найменший кегль у системі був для нього рівно
 * навпаки.
 */
@Composable
private fun OffRouteFace() {
    val theme = LocalPhantomTheme.current
    Row(
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TURN_TILE)
                .clip(PhantomRadii.LargeShape)
                .background(theme.coral.copy(alpha = PhantomAlpha.Wash))
                .border(HAIRLINE, theme.coral, PhantomRadii.LargeShape),
            contentAlignment = Alignment.Center,
        ) {
            // Розворот — єдиний чесний знак цього стану: маршруту попереду
            // немає, є дорога назад до нього.
            ManeuverGlyph(
                type = "uturn",
                modifier = null,
                color = theme.coral,
                dimColor = theme.coral.copy(alpha = PhantomAlpha.Secondary),
                glyphModifier = Modifier.size(TURN_GLYPH),
            )
        }
        Spacer(Modifier.width(PhantomSpacings.Base))
        Column(Modifier.weight(1f)) {
            GlanceText(
                text = stringResource(R.string.nav_off_route_big),
                size = ACTION_SP,
                color = theme.coral,
            )
            GlanceText(
                text = stringResource(R.string.nav_rerouting),
                size = STREET_SP,
                color = theme.inkSecondary,
                weight = FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun ManeuverFace(
    progress: GuidanceProgress,
    face: BannerFace.Maneuver,
    ink: Color,
    accent: Color,
) {
    val theme = LocalPhantomTheme.current
    // The turn changed: the glyph tile takes one emphasis beat so the eye
    // is pulled back to it. The TEXT is never gated on this — every word
    // in this card is already correct on the frame the fix landed; the
    // pulse rides on top of information that has already arrived.
    val turn = face.type to face.modifier
    val beat = remember { Animatable(1f) }
    val beatSpec = PhantomMotion.emphasis
    LaunchedEffect(turn) {
        beat.snapTo(EMPHASIS_OVERSHOOT)
        beat.animateTo(1f, beatSpec)
    }

    Row(
        // Один маневр — одне промовляння, а не чотири уламки.
        modifier = Modifier
            .semantics(mergeDescendants = true) {}
            .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(TURN_TILE)
                .graphicsLayer {
                    scaleX = beat.value
                    scaleY = beat.value
                }
                .clip(PhantomRadii.LargeShape)
                .background(accent),
            contentAlignment = Alignment.Center,
        ) {
            // Єдина залита річ плашки — сам поворот: його бачать краєм ока.
            ManeuverGlyph(
                type = face.type,
                modifier = face.modifier,
                color = accent.onAccentInk(),
                dimColor = accent.onAccentInk().copy(alpha = PhantomAlpha.Recessed),
                glyphModifier = Modifier.size(TURN_GLYPH),
                turnAngleDeg = progress.turnAngleDeg,
                exitNumber = progress.exitNumber,
            )
        }

        Spacer(Modifier.width(PhantomSpacings.Base))

        Column(Modifier.weight(1f)) {
            val (value, unit) = distanceParts(LocalContext.current.resources, face.distanceM)
            Row(verticalAlignment = Alignment.Bottom) {
                // Моно з tnum: відлік 300→250→200 не смикає макет.
                PhantomDataText(
                    text = value,
                    color = ink,
                    fontSize = DISTANCE_SP,
                    fontWeight = FontWeight.Medium,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(PhantomSpacings.Micro))
                    PhantomDataText(
                        text = unit,
                        color = theme.inkSecondary,
                        fontSize = UNIT_SP,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = PhantomSpacings.Micro),
                    )
                }
            }
            val locale = LocalConfiguration.current.locales[0]
            val lines = maneuverLines(face.type, face.modifier, face.roadName, progress.exitNumber, locale)
            GlanceText(
                text = lines.action,
                size = ACTION_SP,
                color = ink,
                // Посадка несе лінію, напрямок і платформу: обрізати її — сховати, куди сідати.
                maxLines = if (face.type?.startsWith(TransitSteps.PREFIX) == true) 2 else 1,
            )
            // Орієнтир — те, що людина бачить очима («за АТБ»), тому чорнилом, а
            // не тихим тоном назви вулиці, яку на розі часто не видно.
            progress.landmark?.let {
                GlanceText(text = landmarkLead(it, locale), size = STREET_SP, color = ink, weight = FontWeight.Normal)
            }
            lines.detail?.let {
                GlanceText(
                    text = it,
                    size = STREET_SP,
                    color = theme.inkSecondary,
                    weight = FontWeight.Normal,
                )
            }
        }

        if (progress.nextManeuverType != null && progress.nextManeuverType != "arrive") {
            Spacer(Modifier.width(PhantomSpacings.Small))
            NextManeuverPreview(progress, theme.inkSecondary)
        }
    }
}

/** The turn after this one — on an interchange, knowing only the first is knowing nothing. */
@Composable
private fun NextManeuverPreview(progress: GuidanceProgress, dim: Color) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Micro),
    ) {
        PhantomKicker(text = stringResource(R.string.nav_next_maneuver), color = dim)
        ManeuverGlyph(
            type = progress.nextManeuverType,
            modifier = progress.nextManeuverModifier,
            color = dim,
            dimColor = dim.copy(alpha = PhantomAlpha.Recessed),
            glyphModifier = Modifier.size(NEXT_GLYPH),
            turnAngleDeg = progress.nextTurnAngleDeg,
            exitNumber = progress.nextExitNumber,
        )
        PhantomCaption(
            text = fmtDistance(LocalContext.current.resources, progress.distanceBetweenManeuversM),
            color = dim,
            maxLines = 1,
        )
    }
}

/**
 * A meter that only ever fills. Between fixes it glides to the newest
 * reading; when the value drops it does NOT unwind — that drop is a new
 * step or a new route, and rewinding a progress bar in front of a driver
 * says something false about the drive. See [PhantomMotion.meterRewinds].
 */
@Composable
private fun rememberMeter(target: Float): State<Float> {
    val clamped = target.coerceIn(0f, 1f)
    val meter = remember { Animatable(clamped) }
    val spec = PhantomMotion.meter
    LaunchedEffect(clamped) {
        if (PhantomMotion.meterRewinds(meter.value, clamped)) meter.snapTo(clamped)
        else meter.animateTo(clamped, spec)
    }
    return meter.asState()
}

/** Drains as the maneuver approaches; the last stretch goes solid accent. */
@Composable
private fun ApproachBar(stepFraction: Float, accent: Color, track: Color) {
    val driven by rememberMeter(stepFraction)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(APPROACH_BAR),
    ) {
        drawRect(color = track.copy(alpha = PhantomAlpha.Secondary), size = size)
        val remaining = 1f - driven
        if (remaining > 0f) {
            drawRect(
                color = if (remaining < NEAR_TURN) accent else accent.copy(alpha = PhantomAlpha.Emphasis),
                size = Size(size.width * remaining, size.height),
            )
        }
    }
}

/** Скільки лишилось до повороту, щоб смуга пішла на повну силу. */
private const val NEAR_TURN = 0.18f

/**
 * Прибуття закривають СЛОВОМ, а не хрестиком: ✕ означає «сховати це», а тут
 * людина завершує поїздку.
 */
@Composable
internal fun ArrivedBanner(onDone: () -> Unit) {
    val theme = LocalPhantomTheme.current
    val accent = LocalPhantomTheme.current.primary
    local.phantom.companion.feature.stream.ui.map.MapFloatingSurface(
        modifier = Modifier.fillMaxWidth(local.phantom.companion.core.design.layout.chromeWidthFraction()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PhantomSpacings.Base),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(ARRIVE_TILE)
                    .clip(PhantomRadii.MediumShape)
                    .background(accent.copy(alpha = PhantomAlpha.Wash)),
                contentAlignment = Alignment.Center,
            ) {
                ManeuverGlyph(
                    type = "arrive",
                    modifier = null,
                    color = accent,
                    dimColor = theme.inkSecondary.copy(alpha = PhantomAlpha.Secondary),
                    glyphModifier = Modifier.size(ARRIVE_GLYPH),
                )
            }
            Spacer(Modifier.width(PhantomSpacings.Base))
            GlanceText(
                text = stringResource(R.string.nav_arrived),
                size = ACTION_SP,
                color = theme.inkStrong,
                modifier = Modifier.weight(1f),
            )
            PhantomChip(
                label = stringResource(R.string.nav_finish),
                tone = ChipTone.Accent,
                emphasis = ChipEmphasis.Solid,
                onClick = onDone,
            )
        }
    }
}

@Composable
internal fun EtaStrip(
    progress: GuidanceProgress?,
    onStop: () -> Unit,
    deadline: DeadlineWatch.Status? = null,
    /** ux-map-design.md §2.3/§5.2 — the active route's worst KNOWN register. */
    worstRegister: GroundRegister? = null,
) {
    val theme = LocalPhantomTheme.current
    val accent = LocalPhantomTheme.current.primary
    val estimate = etaIsEstimate(progress)
    // Поза маршрутом числа лишаються оцінкою по старій лінії: форма регістру
    // «похідне» — префікс «≈» і тихіше чорнило, а не мовчазна впевненість.
    val figureInk = if (estimate) theme.inkSecondary else theme.inkStrong
    val prefix = if (estimate) "≈" else ""
    var hintAtMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(hintAtMs) {
        if (hintAtMs == 0L) return@LaunchedEffect
        kotlinx.coroutines.delay(STOP_HINT_MS)
        hintAtMs = 0L
    }

    local.phantom.companion.feature.stream.ui.map.MapFloatingSurface(
        modifier = Modifier.fillMaxWidth(local.phantom.companion.core.design.layout.chromeWidthFraction()),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(
                    horizontal = PhantomSpacings.Base,
                    vertical = PhantomSpacings.Small,
                ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    val clockFmt = remember { SimpleDateFormat("HH:mm", Locale("uk", "UA")) }
                    // Без фіксу години прибуття не існує — тоді тут лише слово,
                    // а не «—:—», що виглядає як число, якого немає.
                    if (progress != null) {
                        val eta = clockFmt.format(
                            Date(System.currentTimeMillis() + (progress.remainingDurationS * 1000).toLong()),
                        )
                        Row(verticalAlignment = Alignment.Bottom) {
                            PhantomDataText(
                                text = "$prefix$eta",
                                color = figureInk,
                                fontSize = ETA_SP,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.width(PhantomSpacings.Small))
                            PhantomKicker(
                                text = stringResource(R.string.nav_arrival_label),
                                color = theme.inkSecondary,
                                modifier = Modifier.padding(bottom = PhantomSpacings.Micro),
                            )
                        }
                    }
                    val res = LocalContext.current.resources
                    PhantomDataText(
                        text = progress?.let {
                            "$prefix${fmtDistance(res, it.remainingDistanceM)} · " +
                                fmtDuration(res, it.remainingDurationS)
                        } ?: stringResource(R.string.nav_waiting_gps),
                        color = theme.inkSecondary,
                        fontSize = 13.sp,
                        overflow = TextOverflow.Ellipsis,
                    )
                    RouteRegisterChip(worstRegister)
                }

                if (deadline != null && kotlin.math.abs(deadline.slackMinutes) <= 120) {
                    val bandColor = when (deadline.band) {
                        DeadlineWatch.Band.COMFORTABLE -> theme.success
                        DeadlineWatch.Band.TIGHT -> theme.primary
                        DeadlineWatch.Band.LATE -> theme.coral
                    }
                    val bandWord = stringResource(
                        when (deadline.band) {
                            DeadlineWatch.Band.COMFORTABLE -> R.string.nav_slack_comfortable
                            DeadlineWatch.Band.TIGHT -> R.string.nav_slack_tight
                            DeadlineWatch.Band.LATE -> R.string.nav_slack_late
                        },
                    )
                    val slack = if (kotlin.math.abs(deadline.slackMinutes) > 60) {
                        val hrs = deadline.slackMinutes / 60
                        val mins = kotlin.math.abs(deadline.slackMinutes % 60)
                        if (hrs >= 0) {
                            stringResource(R.string.mapl_g_slack_plus_hm, hrs, mins)
                        } else {
                            stringResource(R.string.mapl_g_slack_hm, hrs, mins)
                        }
                    } else if (deadline.slackMinutes >= 0) {
                        stringResource(R.string.mapl_g_slack_plus_m, deadline.slackMinutes)
                    } else {
                        stringResource(R.string.mapl_g_slack_m, deadline.slackMinutes)
                    }
                    MetricBlock(
                        value = slack,
                        label = bandWord,
                        description = stringResource(R.string.nav_slack_desc, slack, bandWord),
                        valueColor = bandColor,
                        labelColor = theme.inkSecondary,
                    )
                    Spacer(Modifier.width(PhantomSpacings.Small))
                }

                val speed = progress?.speedKmh
                val limit = progress?.limitKmh
                val over = speed != null && limit != null &&
                    speed > limit + GuidanceSession.overspeedToleranceKmh(limit)
                if (speed != null) {
                    MetricBlock(
                        value = "$speed",
                        label = if (over) stringResource(R.string.nav_speed_over) else stringResource(R.string.mapl_g_unit_kmh),
                        description = stringResource(R.string.nav_speed_desc_over, speed),
                        valueColor = if (over) theme.coral else theme.inkStrong,
                        labelColor = if (over) theme.coral else theme.inkSecondary,
                        valueSize = 20.sp,
                    )
                    Spacer(Modifier.width(PhantomSpacings.Small))
                }
                if (limit != null) {
                    val limitName = stringResource(R.string.nav_speed_limit_sign, limit)
                    Box(
                        modifier = Modifier
                            .size(SIGN)
                            .clip(PhantomRadii.Pill)
                            .background(if (over) theme.coral.copy(alpha = PhantomAlpha.Wash) else theme.surfaceDeep)
                            .border(2.dp, if (over) theme.coral else 0xFFE60000.toInt().let { Color(it) }, PhantomRadii.Pill)
                            .semantics(mergeDescendants = true) { contentDescription = limitName },
                        contentAlignment = Alignment.Center,
                    ) {
                        PhantomDataText(
                            text = "$limit",
                            color = theme.inkStrong,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(PhantomSpacings.Small))
                }

                Spacer(Modifier.width(PhantomSpacings.Small))
                HoldToStop(onStop = onStop, onTooShort = { hintAtMs = System.currentTimeMillis() })
            }

            AnimatedVisibility(
                visible = hintAtMs != 0L,
                enter = PhantomMotion.enter,
                exit = PhantomMotion.exit,
            ) {
                PhantomCaption(
                    text = stringResource(R.string.nav_stop_hint),
                    color = accent,
                    maxLines = 1,
                    modifier = Modifier.padding(
                        start = PhantomSpacings.Base,
                        end = PhantomSpacings.Base,
                        bottom = PhantomSpacings.Small,
                    ),
                )
            }

            RouteProgressBar(progress?.routeFraction ?: 0f, accent, theme.glassBorder)
        }
    }
}

/**
 * Зупинка — утриманням, не тапом.
 *
 * Миттєвий тап тут коштує живої навігації: телефон у кишені або палець у
 * вібрації на кріпленні вбивав ведення мовчки. Кільце заповнюється по колу
 * кнопки, тож рука бачить, скільки ще тримати; короткий тап каже словом, що
 * саме треба зробити, замість того щоб не зробити нічого.
 *
 * `onClick` у семантиці — не дублікат: утримання для TalkBack недосяжне, і без
 * нього ця кнопка для нього просто не існує.
 */
@Composable
private fun HoldToStop(onStop: () -> Unit, onTooShort: () -> Unit) {
    val accent = LocalPhantomTheme.current.primary
    val haptic = LocalHapticFeedback.current
    val stopName = stringResource(R.string.nav_stop)
    val hold = remember { Animatable(0f) }
    // Стрічка перескладається на кожному фіксі GPS. Якби детектор ключувався на
    // самі колбеки, кожен фікс перезапускав би його і рвав утримання — кнопку
    // було б неможливо дотиснути саме тоді, коли машина їде.
    val stop by rememberUpdatedState(onStop)
    val tooShort by rememberUpdatedState(onTooShort)

    Box(
        modifier = Modifier
            .size(PhantomTouchFloor)
            .clip(PhantomRadii.Pill)
            .background(LocalPhantomTheme.current.surfaceBase)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        // Стоп спрацьовує на ЗАВЕРШЕННІ кільця, а не на
                        // відпусканні: рука мусить відчути, що вже сталося,
                        // поки палець ще на склі.
                        var fired = false
                        kotlinx.coroutines.coroutineScope {
                            val fill = launch {
                                hold.animateTo(1f, tween(STOP_HOLD_MS, easing = LinearEasing))
                                fired = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                stop()
                            }
                            tryAwaitRelease()
                            fill.cancel()
                        }
                        if (!fired) tooShort()
                        hold.snapTo(0f)
                    },
                )
            }
            .semantics(mergeDescendants = true) {
                contentDescription = stopName
                role = Role.Button
                onClick(label = stopName) { stop(); true }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (hold.value <= 0f) return@Canvas
            val stroke = STOP_RING.toPx()
            drawArc(
                color = accent,
                startAngle = -90f,
                sweepAngle = 360f * hold.value,
                useCenter = false,
                topLeft = Offset(stroke / 2f, stroke / 2f),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke),
            )
        }
        Icon(
            Icons.Outlined.Close,
            contentDescription = null,
            tint = LocalPhantomTheme.current.ink,
            modifier = Modifier.size(ICON),
        )
    }
}

/**
 * Worst-register-wins governs this chip, but only REMEMBERED speaks: ~85 % of the
 * country is GUESSED, and a warning on every drive is no warning. The tick fires
 * only when the route crosses INTO old ground, never on the first reading.
 */
@Composable
private fun RouteRegisterChip(worst: GroundRegister?) {
    val haptic = LocalHapticFeedback.current
    var previous by remember { mutableStateOf<GroundRegister?>(null) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(worst) {
        val prior = previous
        previous = worst
        if (prior != null && worst != null && worst.severity > prior.severity && worst.staleNoteRes() != null) {
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            flash.snapTo(1f)
            flash.animateTo(0f, MotionTokens.registerFlashTween)
        }
    }
    val note = worst?.staleNoteRes() ?: return
    val registerTint = worst.tint()
    PhantomCaption(
        text = stringResource(note),
        color = registerTint,
        maxLines = 1,
        modifier = Modifier
            .clip(PhantomRadii.TightShape)
            .background(registerTint.copy(alpha = flash.value * PhantomAlpha.Recessed))
            .padding(horizontal = PhantomSpacings.Micro),
    )
}

@Composable
private fun MetricBlock(
    value: String,
    label: String,
    description: String,
    valueColor: Color,
    labelColor: Color,
    valueSize: TextUnit = 12.sp,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
    ) {
        PhantomDataText(
            text = value,
            color = valueColor,
            fontSize = valueSize,
            fontWeight = FontWeight.SemiBold,
        )
        // Підпис під числом — одиниця або слово стану, не заголовок групи.
        PhantomCaption(text = label, color = labelColor, maxLines = 1)
    }
}

/** How much of the whole drive is behind — the one thing the old strip never said. */
@Composable
private fun RouteProgressBar(fraction: Float, accent: Color, track: Color) {
    val done by rememberMeter(fraction)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(ROUTE_BAR),
    ) {
        drawRect(color = track.copy(alpha = PhantomAlpha.Secondary), size = size)
        if (done > 0f) {
            drawRoundRect(
                color = accent,
                size = Size(size.width * done, size.height),
                cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
                topLeft = Offset.Zero,
            )
        }
    }
}

/**
 * Guidance only claims the camera; the per-frame pose is written by
 * CameraDirector in MapCanvas. Two smoothing passes on one camera lurch.
 */
@Composable
internal fun GuidanceCameraEffect(ui: MapUiState, guidance: GuidanceState) {
    val guiding = guidance is GuidanceState.Guiding
    LaunchedEffect(guiding) {
        val wasChasing = ui.guidanceChase
        ui.guidanceChase = guiding
        if (guiding) {
            ui.followMode = true
        } else if (wasChasing) {
            ui.followMode = false
        }
    }
}
