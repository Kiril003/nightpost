package local.phantom.companion.feature.stream.ui.map

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.nav.CoastBadge
import local.phantom.companion.core.nav.CoastState
import local.phantom.companion.core.sensor.nav.FixFlux
import local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionProfile
import local.phantom.companion.core.sensor.nav.KinematicsEngine.MotionState
import local.phantom.companion.core.sensor.nav.LocationTrust
import local.phantom.companion.feature.stream.ui.map.atlas.CameraIntent
import local.phantom.companion.feature.stream.ui.map.atlas.CameraOutcome
import local.phantom.companion.feature.stream.ui.map.atlas.CameraPose
import local.phantom.companion.feature.stream.ui.map.atlas.CameraService
import local.phantom.companion.feature.stream.ui.map.nav.CameraDirector
import local.phantom.companion.feature.stream.ui.map.nav.CoastPilot
import local.phantom.companion.feature.stream.ui.map.nav.EwTrail
import local.phantom.companion.feature.stream.ui.map.nav.asDisplayFix
import local.phantom.companion.feature.stream.ui.map.nav.cameraCourse
import local.phantom.companion.feature.stream.ui.map.nav.cameraFixUsable
import local.phantom.companion.feature.stream.ui.map.nav.cameraSpeed
import org.maplibre.android.maps.MapLibreMap

/**
 * THERMAL — the dot/camera loop used to re-arm `withFrameNanos` every vsync (up
 * to 120 Hz), running spring + director math on the main thread and, under a
 * never-settling EW fix, redrawing the operator pin every frame — which is what
 * cooked the phone. A location dot and a follow camera read identically at
 * 30 Hz, so the loop is paced: ~30 fps while anything moves, coasting to ~6 fps
 * once several frames in a row have drawn nothing.
 */
private const val FLUX_ACTIVE_FRAME_MS = 16L
private const val FLUX_IDLE_FRAME_MS = 120L
private const val FLUX_IDLE_COAST_FRAMES = 6

/** Below this the operator pin would repaint onto its own pixel. */
private const val PIN_MOVE_EPS_PX = 0.5

/**
 * FIELD FIX — the trust halo's DRAWN radius cap. The underlying σ is unbounded
 * and honest (the Kyiv blackout ended at 346 km); a circle past this size is
 * visual noise at any zoom, and the trust colour carries the verdict instead.
 */
private const val HALO_DISPLAY_MAX_M = 1_500f

/**
 * ATLAS-0b (D4) — the per-frame applier, and the ONLY consumer of
 * [CameraService.frame].
 *
 * Two jobs ride one frame budget: the operator dot (a `FixFlux` spring chasing
 * the fused fix) and the follow camera (`CameraDirector` physics, unchanged —
 * it is good, and it is not what was broken). The director's pose is no longer
 * written here; it is OFFERED to the service, which decides whether this frame
 * belongs to the follow, to a one-shot intent somebody submitted, or to the
 * operator's own fingers.
 *
 * CRITICAL FIELD FAILURE (Kyiv, 2026-07-16) — the camera rides the same eased,
 * trust-gated dot the operator can see. Its first life ran off the RAW fix, so
 * every EW teleport (50 that drive, up to 12.8 km) went straight to the camera.
 */
internal suspend fun runOperatorFluxLoop(
    map: MapLibreMap,
    ui: MapUiState,
    camera: CameraService,
    latestFix: () -> LocationFix?,
    bearingNow: () -> Float,
    trustNow: () -> LocationTrust?,
    isDarkNow: () -> Boolean,
    motionNow: () -> MotionProfile?,
    curvatureNow: () -> Double,
    /** The corridor's own bearing while guiding; the cone must not aim elsewhere. */
    routeBearingNow: () -> Float?,
    nowMsProvider: () -> Long,
    /**
     * ATLAS-2.5 — the coasting seam, one call per frame. It is handed the
     * gate's fix and answers who owns the puck. The default is the identity
     * («the gate's position, unchanged»), so a caller that does not wire it
     * gets exactly the loop that existed before.
     */
    coastFrame: (LocationFix?, Long) -> CoastState = { _, _ -> CoastPilot.LIVE },
    onCoastBadge: (CoastBadge) -> Unit = {},
) {
    val flux = FixFlux()
    var lastSeen: LocationFix? = null
    var lastDrawnBearing = Float.NaN
    var drewSettled = false
    // `settled` alone was not a sufficient rest test: under EW·DR the estimate
    // drifts forever, so the spring never settles and this loop repainted the
    // pin 33×/s while the operator stood still — SKIN 42 °C, severe throttle.
    var lastDrawnLat = Double.NaN
    var lastDrawnLon = Double.NaN
    var wasMoving = false
    var currentMotionAlpha = 0f
    var lastFluxTickMs = 0L
    var haloLat = 0.0
    var haloLon = 0.0
    var haloR = -1f
    var haloColor = ""
    var haloDark = isDarkNow()

    val director = CameraDirector()
    // Seeded from where the map ACTUALLY is. Unseeded, the first guidance frame
    // adopts the envelope outright — a whole pose swapped in one frame, and the
    // extrusions tear away from the base map while they catch up.
    runCatching {
        val cp0 = map.cameraPosition
        director.seed(
            lat = cp0.target?.latitude ?: 0.0,
            lon = cp0.target?.longitude ?: 0.0,
            zoom = cp0.zoom,
            tiltDeg = cp0.tilt,
            bearingDeg = cp0.bearing,
            speedMps = 0f,
        )
    }

    var camApplied = false
    var resumeAdoptsZoom = false
    var idleFrames = 0
    var lastBadge = CoastBadge.LIVE

    while (true) {
        var drew = false
        withFrameNanos { frameNs ->
            val nowMs = frameNs / 1_000_000
            val gateFix = latestFix()
            // Contract §5 — there is no third case. A puck REPLACES the gate's
            // position for the dot, the halo, the guidance feed and (because
            // the camera rides the same eased dot, and only that one) the
            // camera; no puck leaves every one of them exactly as they were.
            val coast = coastFrame(gateFix, nowMs)
            val puck = coast.puck
            val fix = puck?.asDisplayFix() ?: gateFix
            if (coast.badge != lastBadge) {
                lastBadge = coast.badge
                onCoastBadge(coast.badge)
            }
            val isDark = isDarkNow()

            var eased: FixFlux.Eased? = null
            var fixUsable = false
            var moving = false

            if (fix == null) {
                if (lastSeen != null) {
                    lastSeen = null
                    flux.reset()
                    map.style?.let {
                        updateOperatorPin(it, null, bearingNow(), ui.compassFidelity, isDark)
                        clearTrustHalo(it)
                    }
                    haloR = -1f
                    drew = true
                }
            } else {
                // ARCH: PRECISION PIVOT — believe the FIX's own accuracy, NOT the
                // EW jam verdict. The verdict flaps JAMMED on single-frequency
                // hardware, and that flapping thrashed the camera bearing, the
                // zoom cap and the flux snap mode at once. EW is a passive badge.
                // A coasted puck is usable by construction: its bearing is the
                // corridor's own tangent, which is the single most believable
                // heading on the phone under a jam. Judging it by the growing
                // uncertainty radius instead would drop the camera's course
                // mid-tunnel — the break the driver must not see.
                fixUsable = puck != null || cameraFixUsable(fix.accuracyM)
                if (fix !== lastSeen) {
                    lastSeen = fix
                    flux.onTarget(
                        lat = fix.lat,
                        lon = fix.lon,
                        speedMps = fix.speedMps ?: 0f,
                        bearingDeg = fix.bearing,
                        uncertaintyM = fix.accuracyM ?: 0f,
                        atMs = nowMs,
                        trusted = fixUsable,
                    )
                }
                eased = flux.step(nowMs)
            }

            val e = eased
            if (fix != null && e != null) {
                val speed = fix.speedMps ?: 0f
                // The entry gate is the walking floor, not 1.4: a real pedestrian
                // fix reads ~1.2–1.3 m/s, and the old gate refused the chevron
                // AND the camera's course for an entire walk.
                moving = speed > if (wasMoving) STILL_ENTER_MPS else HEADING_FLOOR_MPS
                wasMoving = moving
                // A DEAD fix keeps the inertial course; an untrusted-but-live sky
                // (spoofed) owns NOTHING — its velocity vector is the spinning
                // arrow the operator reported.
                val bearing = when {
                    !fixUsable -> (if (fix.dead) e.bearingDeg else null) ?: bearingNow()
                    moving -> e.bearingDeg ?: bearingNow()
                    else -> bearingNow()
                }
                val bearingMoved = lastDrawnBearing.isNaN() ||
                    kotlin.math.abs(((bearing - lastDrawnBearing + 540f) % 360f) - 180f) > 0.5f
                // Sub-pixel drift is not movement — measured in screen pixels at
                // this zoom, so the threshold is honest downtown and from altitude.
                val pinMoved = lastDrawnLat.isNaN() || run {
                    val mPerPx = runCatching {
                        map.projection.getMetersPerPixelAtLatitude(e.lat)
                    }.getOrNull() ?: 0.0
                    if (mPerPx <= 0.0) return@run true
                    val dN = (e.lat - lastDrawnLat) * 111_320.0
                    val dE = (e.lon - lastDrawnLon) * 111_320.0 *
                        kotlin.math.cos(Math.toRadians(e.lat))
                    kotlin.math.hypot(dN, dE) / mPerPx > PIN_MOVE_EPS_PX
                }
                // `drewSettled` deliberately does NOT guard the second clause: it
                // mirrors `settled`, which under DR is never true — exactly the
                // case this gate exists to catch.
                val skipDraw = (e.settled && drewSettled && !bearingMoved) ||
                    (!pinMoved && !bearingMoved)
                val style = map.style
                if (!skipDraw && style != null && style.isFullyLoaded) {
                    drew = true
                    lastDrawnLat = e.lat
                    lastDrawnLon = e.lon
                    val courseBelievable = moving && (fixUsable || fix.dead)
                    val targetMotionAlpha = if (courseBelievable) 1f else 0f
                    val dtFluxS = if (lastFluxTickMs > 0L) {
                        ((nowMs - lastFluxTickMs).coerceAtLeast(1L) / 1000f)
                    } else {
                        0.016f
                    }
                    lastFluxTickMs = nowMs
                    currentMotionAlpha +=
                        (targetMotionAlpha - currentMotionAlpha) *
                        (1f - kotlin.math.exp(-dtFluxS / 0.15f))

                    updateOperatorPinAt(
                        style, e.lat, e.lon,
                        courseBearing = bearing,
                        // At rest the cone shows where the DEVICE points, which is
                        // meaningless under guidance: the highlight aimed one way
                        // and the line ran another.
                        compassBearing = routeBearingNow() ?: bearingNow(),
                        motionAlpha = currentMotionAlpha,
                        compass = ui.compassFidelity,
                        isDark = isDark,
                    )
                    lastDrawnBearing = bearing
                    drewSettled = e.settled

                    val color = EwTrail.colorFor(fix.dead, trustNow())
                    // МЕЖА НА ЕКРАНІ, не лише в метрах.
                    //
                    // Вирок власника 31.08: «ці великі овали тупі ну і вибішує».
                    // Порахував на його полотні (411 dp, смуга 500 м ≈ 88 dp):
                    //     сумнів  300 м → овал  106 dp = чверть ширини
                    //     сумнів  500 м → овал  176 dp = пів екрана
                    //     сумнів 1500 м → овал  528 dp = ширше за екран
                    // Тобто овал виростає рівно тоді, коли фікс поганий — у
                    // приміщенні, у дворі, під глушінням, тобто в його щоденних
                    // умовах. Стеля HALO_DISPLAY_MAX_M (1500 м) від цього не
                    // рятує: вона в метрах, а болить у пікселях.
                    //
                    // Чому це не «сховати правду»: коло чесне доти, доки
                    // читається ЯК ПЛОЩА. Перерісши третину екрана, воно
                    // перестає казати «ось наскільки я не певен» і починає
                    // казати «із застосунком щось не так» — тобто перестає бути
                    // інформацією і стає шумом. Величину сумніву однаково несе
                    // ЧИСЛО у смузі довіри, і воно не бреше й не займає пів
                    // екрана.
                    //
                    // Межа: діаметр ≤ ширини екрана, тобто радіус ≤ половини.
                    //
                    // Спершу я поставив третину — і це було забагато. Критик має
                    // рацію: між третиною і повною шириною коло ще ЧИТАЄТЬСЯ як
                    // площа, і саме звідти власникові «сумнів 500 м → пів
                    // екрана». Обрізати читабельну правду не можна; обрізаємо
                    // лише те, чий край уже за екраном і не несе нічого.
                    //
                    // Джерело метрів на піксель — `map.projection`, а НЕ
                    // `metersPerPixel(lat, zoom)`. Перша версія брала друге, і
                    // це була помилка: та функція рахує метри на піксель тайла
                    // 256 px (так і написано в її коментарі), нічого не знає про
                    // щільність екрана, а MapLibre рендерить у 512 і з
                    // масштабом щільності. На щільності 420 кап виходив
                    // роздутим у рази, тобто правка була б декоративною.
                    // Правильне джерело лежало за 70 рядків вище, у цьому ж
                    // циклі (рядок 204) — я його не подивився.
                    val onScreenCapM = runCatching {
                        val mppScreen = map.projection.getMetersPerPixelAtLatitude(e.lat)
                        val widthPx = android.content.res.Resources.getSystem()
                            .displayMetrics.widthPixels
                        (mppScreen * widthPx / 2.0).toFloat()
                    }.getOrDefault(HALO_DISPLAY_MAX_M)
                    val r = e.uncertaintyM
                        .coerceAtMost(HALO_DISPLAY_MAX_M)
                        .coerceAtMost(onScreenCapM)
                    val movedM = haversineMeters(haloLat to haloLon, e.lat to e.lon)
                    if (haloR < 0f || movedM > 0.5 || color != haloColor || isDark != haloDark ||
                        kotlin.math.abs(r - haloR) > haloR * 0.03f
                    ) {
                        mapStyleStep("trustHaloUpdate") {
                            updateTrustHalo(style, e.lat, e.lon, r, color, isDark)
                        }
                        haloLat = e.lat
                        haloLon = e.lon
                        haloR = r
                        haloColor = color
                        haloDark = isDark
                    }
                }
            }

            // ── the camera's single frame ─────────────────────────────────
            // Runs whatever the fix did: a search-result fly must land even
            // with no GPS at all, which the old `return` on a null fix ate.
            val gesturing = camera.gestureFresh()
            if (ui.followMode != camera.followArmed) {
                val at = nowMsProvider()
                camera.submit(
                    if (ui.followMode) CameraIntent.FollowArm(issuedAtMs = at)
                    else CameraIntent.FollowDisarm(issuedAtMs = at),
                )
            }

            val wantsFollow = ui.followMode && !gesturing && fix != null && e != null
            val pose: CameraPose? = if (wantsFollow) {
                // Under EW the fused speed can be fiction (a teleport reads as
                // 300 m/s and thrashes the zoom/tilt envelope) — clamp it to what
                // the kinematic classifier says this body can plausibly do.
                val motion = motionNow()
                val speedCap = when (motion?.state) {
                    MotionState.STILL -> 0f
                    MotionState.WALK -> 3f
                    MotionState.RUN -> 6f
                    MotionState.CYCLE -> 12f
                    MotionState.TRAIN -> 60f
                    MotionState.FLIGHT -> 300f
                    else -> 45f
                }
                val camSpeed = cameraSpeed(fixUsable, fix!!.speedMps ?: 0f, speedCap)
                if (!camApplied) {
                    // Activation is a GLIDE from the pose the operator is looking
                    // at, never a snap to the envelope. Only a resume from their
                    // own pinch adopts the current zoom as a preference.
                    val cp = map.cameraPosition
                    director.seed(
                        lat = cp.target?.latitude ?: e!!.lat,
                        lon = cp.target?.longitude ?: e!!.lon,
                        zoom = cp.zoom,
                        tiltDeg = cp.tilt,
                        bearingDeg = cp.bearing,
                        speedMps = camSpeed,
                        motion = motion?.state,
                        adoptZoomBias = resumeAdoptsZoom,
                    )
                    resumeAdoptsZoom = false
                }
                // The camera's bearing is the direction of TRAVEL, or held. The
                // compass may own the ARROW at a standstill, but it must never
                // steer the MAP.
                val d = director.step(
                    nowMs = nowMs,
                    lat = e!!.lat,
                    lon = e.lon,
                    speedMps = camSpeed,
                    courseBearingDeg = cameraCourse(fixUsable, moving, e.bearingDeg),
                    motion = motion?.state,
                    curvatureDegPerM = curvatureNow(),
                )
                CameraPose(d.targetLat, d.targetLon, d.zoom, d.tiltDeg, d.bearingDeg)
            } else {
                if (camApplied) {
                    director.reset()
                    camApplied = false
                    // A pause CAUSED by the operator's own pinch resumes with
                    // their zoom adopted; a fresh locate-arm must NOT adopt
                    // whatever zoom a route overview left.
                    resumeAdoptsZoom = gesturing && ui.followMode
                }
                null
            }

            when (val outcome = camera.frame(pose)) {
                is CameraOutcome.FollowWrote -> {
                    camApplied = true
                    drew = true
                }
                // The zoom buttons that were dead in the one mode a driver uses
                // them: a nudge under a follow is a shift of the envelope.
                is CameraOutcome.FollowBias -> {
                    director.nudgeZoomBias(outcome.levels)
                    drew = true
                }
                is CameraOutcome.Applied -> drew = true
                else -> Unit
            }
            if (camera.followArmed != ui.followMode) ui.followMode = camera.followArmed
        }

        // Pace the loop. Anything that moved keeps it at ~30 fps; a run of empty
        // frames coasts to ~6 fps so a parked map — even one whose dot jitters
        // under EW — stops pinning the main thread at panel refresh.
        if (drew || camera.hasPending) idleFrames = 0
        else if (idleFrames < FLUX_IDLE_COAST_FRAMES) idleFrames++
        delay(if (idleFrames >= FLUX_IDLE_COAST_FRAMES) FLUX_IDLE_FRAME_MS else FLUX_ACTIVE_FRAME_MS)
    }
}
