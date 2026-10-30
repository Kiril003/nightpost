package local.phantom.companion.feature.car

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import local.phantom.companion.core.nav.GuidanceHub
import local.phantom.companion.core.nav.GuidanceState
import local.phantom.companion.core.nav.RoutePoint
import local.phantom.companion.core.nav.isUncertain

/**
 * Draws the drive on the car display.
 *
 * The car head rendered a NavigationTemplate with nothing behind it: correct
 * turn text over an empty rectangle. This paints the route itself — no tiles,
 * no network, so it keeps working exactly where the product is supposed to
 * work: with the sky jammed and the data link gone.
 */
internal class CarMapSurface(
    private val carContext: CarContext,
) : SurfaceCallback {

    private var container: SurfaceContainer? = null
    private var visible: Rect? = null
    private var stable: Rect? = null
    private var job: Job? = null

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val puckPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f }

    /** Static dash, phase 0 always — SA-1 forbids animating this while
     *  driving, so nothing here may ever depend on a clock or a frame count. */
    private val dashEffect = DashPathEffect(floatArrayOf(24f, 16f), 0f)

    fun attach(scope: CoroutineScope) {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)
        job = scope.launch {
            combine(GuidanceHub.state, GuidanceHub.routeLine) { s, line -> s to line }
                .collect { draw() }
        }
    }

    fun detach() {
        job?.cancel()
        job = null
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(null)
    }

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        container = surfaceContainer
        draw()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        container = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        visible = visibleArea
        draw()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        stable = stableArea
        draw()
    }

    /**
     * Day and night arrive on the Session, not here, and the Canvas is not a
     * template — `invalidate()` never reaches it.
     */
    fun redraw() = draw()

    private fun draw() {
        val holder = container?.surface?.takeIf { it.isValid } ?: return
        val width = container?.width ?: return
        val height = container?.height ?: return
        if (width <= 0 || height <= 0) return

        val palette = CarPalette.of(carContext.isDarkMode)
        val canvas = runCatching { holder.lockCanvas(null) }.getOrNull() ?: return
        try {
            canvas.drawColor(palette.ground)
            val state = GuidanceHub.state.value
            val progress = (state as? GuidanceState.Guiding)?.progress
            val line = GuidanceHub.routeLine.value
            if (progress == null || line.size < 2) {
                // «чекаю маршрут» — це твердження про очікування, і воно
                // правдиве РІВНО тоді, коли маршрут справді рахується. У
                // спокої ніхто нічого не чекає, і напис перетворюється на
                // вічну обіцянку: лишається чистий фон. Порожній кут
                // чесніший за слово, яке нічого не описує.
                if (state is GuidanceState.Guiding) {
                    drawWaiting(canvas, width, height, palette)
                }
                return
            }
            val cam = CarMapProjection.Camera(
                lat = progress.snappedLat,
                lon = progress.snappedLon,
                bearingDeg = progress.bearingDeg,
                widthPx = width,
                heightPx = height,
                spanM = CarMapProjection.spanForSpeed(progress.speedKmh),
                visible = visible.asArea(),
                stable = stable.asArea(),
            )
            val split = CarMapProjection.nearestIndex(line, progress.snappedLat, progress.snappedLon) { it.lat to it.lon }
            drawLine(
                canvas, cam,
                CarMapProjection.visibleSlice(cam, line.take(split + 1)) { it.lat to it.lon },
                Temporal.BEHIND, palette,
            )
            drawLine(
                canvas, cam,
                CarMapProjection.visibleSlice(cam, line.drop(split)) { it.lat to it.lon },
                Temporal.AHEAD, palette,
            )
            line.lastOrNull()?.let { dest ->
                drawDestinationPin(canvas, cam, dest, palette)
            }
            drawPuck(canvas, CarMapProjection.anchor(cam), palette)
            drawSpeedHUD(canvas, width, height, progress.speedKmh ?: 0, progress.limitKmh, stable ?: visible, palette)
        } finally {
            runCatching { holder.unlockCanvasAndPost(canvas) }
        }
    }

    private fun drawDestinationPin(
        canvas: Canvas,
        cam: CarMapProjection.Camera,
        dest: RoutePoint,
        palette: CarPalette,
    ) {
        val p = CarMapProjection.project(cam, dest.lat, dest.lon)
        val r = 16f
        puckPaint.color = palette.ahead
        canvas.drawCircle(p.x, p.y, r, puckPaint)
        puckPaint.color = palette.ground
        canvas.drawCircle(p.x, p.y, r * 0.5f, puckPaint)
    }

    private fun drawSpeedHUD(
        canvas: Canvas,
        width: Int,
        height: Int,
        speedKmh: Int,
        limitKmh: Int?,
        safeArea: Rect?,
        palette: CarPalette,
    ) {
        val speedStr = speedKmh.coerceAtLeast(0).toString()
        val kmhStr = "км/год"
        val boxWidth = 140f
        val boxHeight = 110f
        val margin = 24f

        val rightEdge = safeArea?.right?.toFloat() ?: (width - margin)
        val bottomEdge = safeArea?.bottom?.toFloat() ?: (height - margin)

        val left = (rightEdge - boxWidth).coerceAtLeast(margin)
        val top = (bottomEdge - boxHeight).coerceAtLeast(margin)

        val isOverspeed = limitKmh != null && speedKmh > limitKmh + 5
        val speedColor = if (isOverspeed) palette.danger else palette.ink

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.ground
            alpha = 230
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isOverspeed) palette.danger else palette.behind
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 18f, 18f, bgPaint)
        canvas.drawRoundRect(left, top, left + boxWidth, top + boxHeight, 18f, 18f, borderPaint)

        val numPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = speedColor
            textSize = 42f
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        val lblPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.behind
            textSize = 18f
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(speedStr, left + boxWidth / 2f, top + 52f, numPaint)
        canvas.drawText(kmhStr, left + boxWidth / 2f, top + 84f, lblPaint)

        // Draw Speed Limit Sign (if available) next to speed box
        if (limitKmh != null && limitKmh > 0) {
            val signRadius = 40f
            val signCenterX = left - signRadius - 16f
            val signCenterY = top + boxHeight / 2f

            if (signCenterX - signRadius >= (safeArea?.left ?: margin.toInt())) {
                val signBg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFFFFFFF.toInt()
                    style = Paint.Style.FILL
                }
                val signBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFFE60000.toInt() // European/Ukrainian road sign red
                    style = Paint.Style.STROKE
                    strokeWidth = 9f
                }
                val signText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = 0xFF000000.toInt()
                    textSize = 34f
                    isFakeBoldText = true
                    textAlign = Paint.Align.CENTER
                }
                canvas.drawCircle(signCenterX, signCenterY, signRadius, signBg)
                canvas.drawCircle(signCenterX, signCenterY, signRadius, signBorder)
                canvas.drawText(limitKmh.toString(), signCenterX, signCenterY + 12f, signText)
            }
        }
    }

    private fun Rect?.asArea(): CarMapProjection.Area? =
        this?.let { CarMapProjection.Area(it.left, it.top, it.right, it.bottom) }

    private fun drawWaiting(canvas: Canvas, width: Int, height: Int, palette: CarPalette) {
        val area = CarMapProjection.drawArea(width, height, visible.asArea())
        val text = CarCopy.SURFACE_WAITING
        textPaint.color = palette.ink
        val w = textPaint.measureText(text)
        canvas.drawText(text, area.left + (area.width - w) / 2f, area.top + area.height / 2f, textPaint)
    }

    /**
     * design-critic §S5 — register is drawn PER SEGMENT here, never as one
     * verdict for the whole line (that rule belongs only to
     * [local.phantom.companion.core.nav.GuidanceController]'s single spoken
     * summary). Conflating the two would make every route render dashed and
     * faint — ~85% of the country's roads are GUESSED
     * (map-realism.md's census) — which is a signal that is always on, i.e.
     * none. [registerRuns] groups consecutive points by the one bit that
     * changes what gets drawn; each run keeps its own colour/width/dash.
     */
    private fun drawLine(
        canvas: Canvas,
        cam: CarMapProjection.Camera,
        points: List<RoutePoint>,
        temporal: Temporal,
        palette: CarPalette,
    ) {
        if (points.size < 2) return
        val baseColor = if (temporal == Temporal.AHEAD) palette.ahead else palette.behind
        val baseWidth = if (temporal == Temporal.AHEAD) AHEAD_WIDTH else BEHIND_WIDTH
        for ((uncertain, run) in registerRuns(points)) {
            if (run.size < 2) continue
            val path = Path()
            run.forEachIndexed { i, pt ->
                val p = CarMapProjection.project(cam, pt.lat, pt.lon)
                if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
            }
            roadPaint.color = baseColor
            // SA-1 — static, never a marching-ants phase driven by a clock:
            // every redraw here is triggered by GuidanceHub.state/routeLine
            // actually changing, not a frame loop, and the dash below must
            // stay that way.
            if (uncertain) {
                roadPaint.alpha = REGISTER_ALPHA
                roadPaint.strokeWidth = BEHIND_WIDTH
                roadPaint.pathEffect = dashEffect
            } else {
                roadPaint.alpha = 255
                roadPaint.strokeWidth = baseWidth
                roadPaint.pathEffect = null
            }
            canvas.drawPath(path, roadPaint)
        }
    }

    /** A triangle, not a dot: on a car screen the driver reads facing first. */
    private fun drawPuck(canvas: Canvas, at: CarMapProjection.Pt, palette: CarPalette) {
        val r = 22f
        val path = Path().apply {
            moveTo(at.x, at.y - r)
            lineTo(at.x - r * 0.72f, at.y + r * 0.8f)
            lineTo(at.x, at.y + r * 0.35f)
            lineTo(at.x + r * 0.72f, at.y + r * 0.8f)
            close()
        }
        puckPaint.color = palette.puck
        canvas.drawPath(path, puckPaint)
    }

    internal companion object {
        /** The original 16px `AHEAD` / 10px `BEHIND` widths, now named
         *  instead of inline literals. */
        const val AHEAD_WIDTH = 16f
        const val BEHIND_WIDTH = 10f

        /** ~45% (map-realism.md §3.2's table). Verified in CarPaletteTest
         *  against both palettes' `dimFloor` — do not raise without re-
         *  checking DAY, which clears it by the smaller margin. */
        const val REGISTER_ALPHA = 115
    }
}

/** Whether a segment is drawn as already-driven (dim) or the road ahead
 *  (bright) — orthogonal to [RoutePoint.register]: a segment can be either
 *  temporal state AND either confidence, and both must show. */
internal enum class Temporal { BEHIND, AHEAD }

/**
 * Splits [points] into consecutive runs sharing an uncertain/not verdict,
 * each sharing its boundary point with its neighbour so the drawn line has
 * no gap where the style changes. Pure so this is provable without a head
 * unit, same discipline as [CarMapProjection].
 */
internal fun registerRuns(points: List<RoutePoint>): List<Pair<Boolean, List<RoutePoint>>> {
    if (points.size < 2) return emptyList()
    val runs = mutableListOf<Pair<Boolean, List<RoutePoint>>>()
    var runStart = 0
    var uncertain = points[0].register.isUncertain()
    for (i in 1 until points.size) {
        val u = points[i].register.isUncertain()
        if (u != uncertain) {
            runs += uncertain to points.subList(runStart, i + 1)
            runStart = i
            uncertain = u
        }
    }
    runs += uncertain to points.subList(runStart, points.size)
    return runs
}

/**
 * The two palettes the car head draws in.
 *
 * Google requires a navigation app to repaint its map for the head unit's dark
 * theme. PHANTOM had one hardcoded dark palette and never asked which theme it
 * was in, so night was right by accident and day was wrong in silence.
 *
 * Since 04.10 both are the v1.2 identity, copied as Ints on purpose: this
 * module has no Compose and no `:core-design` dependency (see its build file —
 * the absence IS the enforcement), so it mirrors the tokens instead of
 * importing `NightColors`/`DayColors`. Which value is which token:
 *  - ground — `canvas` («Ніч» #0A0C10 / day #F8F4EE);
 *  - ahead — the phone map's own route colour (MapStyleLayers ROUTE_CORE_COLOR,
 *    day #00786A), NOT the accent: amber is for actions, and a car route in a
 *    different colour from the phone's would split the two heads;
 *  - puck — night: the phone's PUCK_CORE (= D `accent` #E2A048); day: `accentLine`
 *    #A9680A, because the bright accent on the light ground fails 3:1;
 *  - ink — `text2`; behind — a quiet grey that still clears the dim floor;
 *  - danger — `danger` (overspeed). The road-sign red of the limit disc stays
 *    #E60000: that is the sign's colour, not ours.
 */
internal data class CarPalette(
    val ground: Int,
    val ahead: Int,
    val behind: Int,
    val puck: Int,
    val ink: Int,
    val danger: Int,
) {
    companion object {
        fun of(dark: Boolean): CarPalette = if (dark) NIGHT else DAY

        val NIGHT = CarPalette(
            ground = 0xFF0A0C10.toInt(),
            ahead = 0xFF3FD9C4.toInt(),
            behind = 0xFF3B4352.toInt(),
            puck = 0xFFE2A048.toInt(),
            ink = 0xFFB6BCC6.toInt(),
            danger = 0xFFFF7D71.toInt(),
        )

        val DAY = CarPalette(
            ground = 0xFFF8F4EE.toInt(),
            ahead = 0xFF00786A.toInt(),
            behind = 0xFFB0A79A.toInt(),
            puck = 0xFFA9680A.toInt(),
            ink = 0xFF4E4339.toInt(),
            danger = 0xFFB42318.toInt(),
        )
    }
}
