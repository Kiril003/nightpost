package local.phantom.companion.feature.stream.ui.map.mount

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import local.phantom.companion.core.design.layout.HideBottomChrome
import local.phantom.companion.core.design.theme.LocalPhantomTheme
import local.phantom.companion.core.design.theme.LocalStateAccent
import local.phantom.companion.core.design.theme.PhantomAlpha
import local.phantom.companion.core.design.theme.PhantomBody
import local.phantom.companion.core.design.theme.PhantomMono
import local.phantom.companion.core.design.theme.PhantomProse
import local.phantom.companion.core.design.theme.PhantomRadii
import local.phantom.companion.core.design.theme.PhantomSpacings
import local.phantom.companion.core.nav.GuidanceHub
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.RouteRegister
import local.phantom.companion.core.nav.maneuverLines
import local.phantom.companion.feature.stream.R
import local.phantom.companion.feature.stream.ui.map.MapChromeMetrics
import local.phantom.companion.feature.stream.ui.map.nav.ManeuverGlyph
import local.phantom.companion.feature.stream.ui.map.nav.distanceParts

// Обидва — оптичні розміри для лобового скла, а не крок сітки: гліф має
// читатись на витягнутій руці, а проміж між трьома фактами в альбомній
// орієнтації — розводити їх, а не ритмізувати.
private val HEADING_SIZE = 100.dp
private val CLUSTER_GAP = 36.dp

/**
 * design-critic.md §S7's "mount mode" — the full phone map replaced by the
 * three facts §4.4 allows, on an opaque surface. Nothing from `MapChrome`,
 * `MapCanvas` or any sheet is reachable from here: that is how the four
 * refusals (hazard content, trafficability colour, air-raid colour,
 * decorative animation) are enforced — structurally, not by an audit of
 * which layer happens to be toggled on.
 */
@Composable
internal fun MountGlanceScreen(
    bearingDeg: Float,
    hasFix: Boolean,
    isDarkTheme: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The island has no business on a windscreen: this mode exists to leave
    // exactly three facts competing with the road, and its own reserve would
    // push the distance line — the one number a driver glances for — under
    // the pill. Hidden outright rather than inset around.
    HideBottomChrome()

    val guidance by GuidanceHub.state.collectAsStateWithLifecycle()
    val routeLine by GuidanceHub.routeLine.collectAsStateWithLifecycle()
    val glance = remember(guidance, routeLine, bearingDeg, hasFix) {
        deriveMountGlance(guidance, routeLine, bearingDeg, hasFix)
    }

    // §4.3's night rule applied wholesale: every colour below reads THIS
    // spec, morphed unconditionally on the real sun position (not gated on
    // theme id the way MainActivity's own call is — a windscreen is the one
    // surface where night comfort outranks theme identity). A control added
    // to this package later has no un-morphed theme left to accidentally read.
    val mountTheme = LocalPhantomTheme.current.morphForNight(isDarkTheme)

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        // ContinuumHost (app/.../ContinuumHost.kt) already owns this same
        // View flag while a drive is guiding. Clearing it unconditionally on
        // exit would race that owner if guidance is still live when the
        // operator leaves mount mode — converge to ITS rule, don't stomp it.
        onDispose { view.keepScreenOn = GuidanceHub.state.value is GuidanceState.Guiding }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val consumeTouches = remember { MutableInteractionSource() }

    // Системне «назад» не викидає з режиму: у кріпленні край екрана чіпляють
    // випадково, а вихід звідси повертає всю мапу з хромом одним рухом. Але й
    // мовчки зʼїдати жест не можна — він отримує ту саму підказку, що й
    // короткий тап по виходу. Позначка часу, а не прапорець: другий поспіль
    // «назад» має перезапустити показ, а не потрапити в ту саму гілку.
    var hintAtMs by remember { mutableLongStateOf(0L) }
    BackHandler { hintAtMs = SystemClock.uptimeMillis() }
    LaunchedEffect(hintAtMs) {
        if (hintAtMs == 0L) return@LaunchedEffect
        delay(EXIT_HINT_VISIBLE_MS)
        hintAtMs = 0L
    }

    var mirrorHud by remember { mutableStateOf(false) }

    val accent = LocalStateAccent.current.color

    CompositionLocalProvider(LocalPhantomTheme provides mountTheme) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color(0xFF000000)),
        ) {
            // Mount mode owns every pixel it covers — a tap must never reach
            // the chrome, sheets or tap-popovers it exists to hide. Окремим
            // шаром ПІД вмістом, а не на самому Box: на Box його
            // clearAndSetSemantics зняв би імена і з трьох фактів, і з виходу,
            // а без нього TalkBack читає весь екран як безіменну кнопку.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(interactionSource = consumeTouches, indication = null) {}
                    .clearAndSetSemantics {},
            )
            FactCluster(
                glance = glance,
                landscape = landscape,
                modifier = Modifier
                    .align(BiasAlignment(0f, -0.15f))
                    .graphicsLayer {
                        scaleX = if (mirrorHud) -1f else 1f
                    },
            )
            // Windshield HUD mirror toggle
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(PhantomSpacings.Base)
                    .clip(PhantomRadii.Pill)
                    .background(if (mirrorHud) accent.copy(alpha = 0.25f) else mountTheme.glassPanel)
                    .border(1.dp, if (mirrorHud) accent else mountTheme.glassBorder, PhantomRadii.Pill)
                    .clickable(role = Role.Button) { mirrorHud = !mirrorHud }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(if (mirrorHud) R.string.mapl_h_mirror_on else R.string.mapl_h_mirror_for_glass),
                    fontSize = 13.sp,
                    color = if (mirrorHud) accent else mountTheme.inkSecondary,
                    fontFamily = PhantomMono,
                    fontWeight = if (mirrorHud) FontWeight.Bold else FontWeight.Normal,
                )
            }
            MountExitControl(
                onExit = onExit,
                hintVisible = hintAtMs != 0L,
                onShowHint = { hintAtMs = SystemClock.uptimeMillis() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(PhantomSpacings.Base),
            )
        }
    }
}

@Composable
private fun FactCluster(glance: MountGlance, landscape: Boolean, modifier: Modifier = Modifier) {
    if (landscape) {
        Row(
            modifier = modifier,
            horizontalArrangement = Arrangement.spacedBy(CLUSTER_GAP),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeadingFact(glance.heading)
            DistanceFact(glance.distance)
            glance.worstRegister?.let { RegisterChip(it) }
        }
    } else {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(PhantomSpacings.Large),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HeadingFact(glance.heading)
            DistanceFact(glance.distance)
            glance.worstRegister?.let { RegisterChip(it) }
        }
    }
}

@Composable
private fun HeadingFact(heading: MountHeading) {
    val theme = LocalPhantomTheme.current
    // Намальований гліф не має тексту взагалі: без цього TalkBack мовчить
    // саме про той факт, заради якого водій дивиться на екран.
    val spoken = when (heading) {
        is MountHeading.Maneuver -> maneuverLines(
            type = heading.progress.maneuverType,
            modifier = heading.progress.maneuverModifier,
            roadName = heading.progress.roadName,
            exitNumber = heading.progress.exitNumber,
            locale = LocalConfiguration.current.locales[0],
        ).action
        is MountHeading.Compass -> compassWord(heading.bearingDeg)
        MountHeading.NoFix -> stringResource(R.string.mount_heading_no_fix)
    }
    Box(
        modifier = Modifier
            .size(HEADING_SIZE)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        contentAlignment = Alignment.Center,
    ) {
        when (heading) {
            is MountHeading.Maneuver -> ManeuverGlyph(
                type = heading.progress.maneuverType,
                modifier = heading.progress.maneuverModifier,
                color = theme.inkStrong,
                dimColor = theme.inkSecondary.copy(alpha = PhantomAlpha.Secondary),
                glyphModifier = Modifier.fillMaxSize(),
                turnAngleDeg = heading.progress.turnAngleDeg,
                exitNumber = heading.progress.exitNumber,
            )
            // No maneuver yet: the same glyph family drawn straight-ahead
            // and rotated to the device's own compass reading — one visual
            // grammar for "which way," not a second icon set.
            is MountHeading.Compass -> ManeuverGlyph(
                type = null,
                modifier = null,
                color = theme.inkStrong,
                dimColor = theme.inkSecondary.copy(alpha = PhantomAlpha.Secondary),
                glyphModifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = heading.bearingDeg },
            )
            MountHeading.NoFix -> PhantomProse(
                text = stringResource(R.string.mount_heading_no_fix),
                color = theme.inkSecondary,
            )
        }
    }
}

/** Восьмипелюсткова роза — той самий словник, що й у HUD транспорту. */
@Composable
private fun compassWord(bearingDeg: Float): String {
    val points = listOf(
        R.string.map_dir_n, R.string.map_dir_ne, R.string.map_dir_e, R.string.map_dir_se,
        R.string.map_dir_s, R.string.map_dir_sw, R.string.map_dir_w, R.string.map_dir_nw,
    ).map { stringResource(it) }
    val index = (((bearingDeg + 22.5f) % 360f + 360f) % 360f / 45f).toInt()
    return points[index % points.size]
}

@Composable
private fun DistanceFact(distance: MountDistance) {
    val theme = LocalPhantomTheme.current
    when (distance) {
        is MountDistance.ToManeuver -> {
            val (value, unit) = distanceParts(distance.meters)
            // Windscreen-distance legibility, not chrome: no PhantomTextRoles
            // role reaches within 5x of this size. RawTextSizeRatchetTest's
            // ceiling carries this exception (and BigStatus's, and the unit
            // below) rather than shrinking mount mode's one glanceable number.
            // Число і одиниця — одне вимірювання, а не два вузли поспіль.
            val spoken = if (unit.isEmpty()) value else "$value $unit"
            Row(
                verticalAlignment = Alignment.Bottom,
                modifier = Modifier.semantics(mergeDescendants = true) {
                    contentDescription = spoken
                },
            ) {
                Text(
                    text = value,
                    color = theme.inkStrong,
                    fontSize = 84.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = PhantomMono,
                    maxLines = 1,
                    softWrap = false,
                )
                if (unit.isNotEmpty()) {
                    Spacer(Modifier.width(PhantomSpacings.Small))
                    Text(
                        text = unit,
                        color = theme.inkSecondary,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = PhantomMono,
                        modifier = Modifier.padding(bottom = PhantomSpacings.Small),
                    )
                }
            }
        }
        MountDistance.OffRoute -> BigStatus(stringResource(R.string.mount_distance_off_route), theme.coral)
        MountDistance.Arrived -> BigStatus(stringResource(R.string.mount_distance_arrived), theme.success)
        MountDistance.Waiting -> BigStatus(stringResource(R.string.nav_waiting_gps), theme.inkSecondary)
        MountDistance.NoRoute -> BigStatus(stringResource(R.string.mount_distance_no_route), theme.inkSecondary)
    }
}

@Composable
private fun BigStatus(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 30.sp,
        fontWeight = FontWeight.SemiBold,
        fontFamily = PhantomMono,
        maxLines = 1,
        softWrap = false,
    )
}

/** Лише давня мапа міняє, як їхати; «здогад» — це ~85 % країни, і на кожній поїздці він був би шумом. */
@Composable
private fun RegisterChip(register: RouteRegister) {
    if (register != RouteRegister.REMEMBERED) return
    val theme = LocalPhantomTheme.current
    // Emphasis by weight and fill, never by hue — a colour-coded confidence
    // signal is trafficability-colour's cousin and is refused on this screen
    // exactly like it is on the car surface (android-auto-expansion.md §4).
    PhantomBody(
        text = stringResource(R.string.nav_route_stale),
        color = theme.inkStrong,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = Modifier
            .clip(PhantomRadii.Pill)
            .background(theme.glassPanel.copy(alpha = PhantomAlpha.Near))
            .border(width = MapChromeMetrics.Hairline, color = theme.glassBorder, shape = PhantomRadii.Pill)
            .padding(horizontal = PhantomSpacings.Base, vertical = PhantomSpacings.Small),
    )
}
