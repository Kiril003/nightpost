package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import local.phantom.companion.feature.stream.ui.map.atlas.DayPhase
import local.phantom.companion.feature.stream.ui.map.atlas.LensInput
import local.phantom.companion.feature.stream.ui.map.atlas.MotionClass
import local.phantom.companion.feature.stream.ui.map.atlas.SceneDeriver
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMemory
import local.phantom.companion.feature.stream.ui.map.atlas.SceneMode
import local.phantom.companion.feature.stream.ui.map.atlas.SceneSignals
import local.phantom.companion.core.sensor.nav.KinematicsEngine
import local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState

/**
 * ATLAS-0b (D5) — the one place the map's ~35 visibility booleans are read.
 *
 * [MapUiState] keeps what the operator INTENDS (toggles, selections, pins);
 * whether a tier is actually on screen is decided by `LensEngine` from this
 * input and nowhere else. ATLAS-2 додало друге питання того ж роду: сцену
 * більше не вирішує те, що ВІДКРИТО на екрані, — її виводить [SceneDeriver] з
 * того, що людина робить.
 */
internal fun mapLensInput(
    ui: MapUiState,
    scene: SceneMode,
    zoom: Double,
    terrainDepth: Boolean,
    ownOblastKnown: Boolean,
    ownOblastAlarm: Boolean,
    /**
     * `SkyVerdict.feedSilent` — вирок, знятий тим самим `verdict()`, який
     * озвучує пігулку. Приходить готовим числом, а не перераховується тут:
     * другий підрахунок віку стрічки був би другим вироком про те саме небо.
     */
    skyFeedSilent: Boolean,
    /** Пак під фіксом оголошує небо. Знімається тим самим `verdict()`. */
    skyFeedDeclared: Boolean,
    pinnedPlaces: Boolean,
    motion: MotionState?,
    mapPhase: DayPhase,
): LensInput = LensInput(
    scene = scene,
    zoom = zoom,
    motion = motion.toMotionClass(),
    ownOblastAlarm = ownOblastAlarm,
    ownOblastKnown = ownOblastKnown,
    activeAlarmCount = ui.alertStates.count { it.value.active },
    skyFeedSilent = skyFeedSilent,
    skyFeedDeclared = skyFeedDeclared,
    alertsVisible = ui.alertsVisible,
    radarVisible = ui.radarVisible,
    // The Lens asks about light, and a forced basemap decides that regardless
    // of the clock — the same value the whole style is painted from. До ATLAS-1
    // сюди приходив `Boolean`, тож `DayPhase.DUSK` існував і не будувався
    // ніколи: правила сутінків стояли мертвими.
    dayPhase = mapPhase,
    indoorPlanPresent = ui.indoorPlan != null,
    dossierOpen = ui.dossierTarget != null,
    pinnedPlaces = pinnedPlaces,
    terrainEntitlement = terrainDepth,
)

internal fun MotionState?.toMotionClass(): MotionClass = when (this) {
    MotionState.STILL -> MotionClass.STILL
    MotionState.WALK, MotionState.RUN -> MotionClass.WALK
    MotionState.CYCLE -> MotionClass.WALK
    MotionState.TRAIN, MotionState.FLIGHT -> MotionClass.TRANSIT
    null -> MotionClass.UNKNOWN
    else -> MotionClass.DRIVE
}

/**
 * Наскільки певний класифікатор має бути, щоб його вердикт перебудував екран.
 *
 * Поріг не власний: [KinematicsEngine.WALK_SPEED_ONLY_CONFIDENCE] — це ходьба,
 * побачена самою лише швидкістю, і власний коментар движка каже прямо, що
 * гейт, який її не пускає, не побачить пішохода взагалі. Пігулка ходи стоїть
 * вище (0.55) свідомо: сказати слово дешевше, ніж перебудувати мапу.
 */
internal const val MODE_MOTION_CONFIDENCE = KinematicsEngine.WALK_SPEED_ONLY_CONFIDENCE

/**
 * Режим сцени, і жодного циклу кадрів під ним.
 *
 * [SceneDeriver] сам каже, коли вердикт МОЖЕ змінитись, тож ця корутина спить
 * рівно до тієї миті й прокидається двічі-тричі за сесію. Час останнього дотику
 * читається ВСЕРЕДИНІ циклу, а не приходить сигналом: під час пану він
 * переписується щокадру, і ключем ефекту він перезапускав би все це так само
 * часто.
 */
@Composable
internal fun rememberSceneMode(signals: SceneSignals, interactionAtMs: () -> Long): SceneMode {
    val memory = remember { SceneMemoryHolder() }
    val live by rememberUpdatedState(interactionAtMs)
    var mode by remember { mutableStateOf(SceneMode.EXPLORE) }
    LaunchedEffect(signals) {
        while (true) {
            val now = android.os.SystemClock.uptimeMillis()
            val folded = SceneDeriver.fold(
                memory.value,
                signals.copy(interactionAtMs = live()),
                now,
            )
            memory.value = folded
            mode = folded.mode
            if (folded.dueAtMs == SceneDeriver.NO_DEADLINE) break
            delay((folded.dueAtMs - now).coerceAtLeast(1L))
        }
    }
    return mode
}

/** Памʼять згортки поза Compose-станом: перекомпозиція має бути лише на РЕЖИМ. */
private class SceneMemoryHolder {
    var value = SceneMemory()
}
