package local.phantom.companion.feature.stream.ui.map.nav

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Drawn maneuver glyphs — one shape per OSRM family, no icon dependency.
 *
 * Everything here is set on ONE grid, and that is the whole point of the file.
 * The owner's words were «стрілки навігації і інші знаки ніби не дизайнерами
 * професійними створені, а накалякані від руки», and each half of that had a
 * measurable cause:
 *
 *  - **Weight.** The stroke was `h * 0.135` — 3.2dp in a 24dp box, against the
 *    2dp of every Material Outlined icon sitting beside it in the same chrome.
 *    Half again as heavy as its neighbours reads as a marker pen, not a line.
 *  - **Angle.** 34°, 38°, 42°, `bendDeg * 0.35` — arbitrary numbers, one per
 *    call site. The eye does not read an angle; it reads whether the angles
 *    belong to a system. These did not.
 *  - **Curve.** The bend was a cubic with both handles parked on the pivot —
 *    a curve of continuously varying curvature, which is literally the shape a
 *    hand draws. A fillet is one radius, held.
 *
 * So: a 24×24 grid with a 2u live margin, `u = box / 24`; 2u stroke for the
 * instruction and 1u for context (the road not taken, the ring as it exists);
 * round caps throughout; five canonical angles; one arrowhead; one fillet
 * radius. A new family has nothing left to invent.
 */

/** The grid, resolved for one draw. Every number below is in [u]. */
private class Grid(box: Float) {
    val u: Float = box / 24f
    val stroke: Float = 2f * u
    /** Context is drawn at half the instruction's weight, and only ever dim. */
    val context: Float = 1f * u
    /** Півширина головки. Кут вершини 90° ⇒ довжина дорівнює півширині. */
    val headHalf: Float = 1.75f * stroke
    val headLen: Float = headHalf
    val fillet: Float = 2.5f * u
}

/** OSRM modifier → bend, degrees; 0 = straight ahead, positive = right. */
internal fun maneuverBendDeg(modifier: String?): Float = when (modifier) {
    "left" -> -90f
    "right" -> 90f
    "slight left" -> -SLIGHT
    "slight right" -> SLIGHT
    "sharp left" -> -SHARP
    "sharp right" -> SHARP
    "uturn" -> 180f
    else -> 0f
}

/**
 * The five angles a glyph may be drawn at: 0, ±35, ±90, ±135, 180.
 *
 * The real OSRM angle CHOOSES a canon; it is never drawn raw. This is the one
 * change in the file that touches meaning rather than looks, so it is worth
 * being exact about what is given up and what is not: on the MAP the symbol is
 * still rotated by the true bearing — that is a number, and the number stays
 * honest. What snaps is the shape of the pictogram in the banner, where 42°
 * against 38° against 34° carried no information the operator could use and
 * cost the whole family its look of being drawn by someone.
 *
 * A named turn is still never drawn as going straight on: a «поверніть
 * праворуч» under an arrow pointing forward is the contradiction the operator
 * meets at the junction, and it outranks the thresholds.
 */
internal fun canonBendDeg(modifier: String?, turnAngleDeg: Float?): Float {
    val bucket = maneuverBendDeg(modifier)
    if (bucket == 180f) return 180f
    val real = turnAngleDeg ?: return bucket
    // Bearings and modifier disagreeing about the SIDE means the bearings are
    // stale or wrapped; the modifier is what the voice says, so it wins.
    if (bucket != 0f && (real == 0f || (real > 0f) != (bucket > 0f))) return bucket
    val canon = canonOf(abs(real))
    // Слово переважає поріг: названий поворот не має права стати прямою, і
    // «різко праворуч» під плавною дугою — та сама суперечність, лише тихіша.
    if (canon == 0f && bucket != 0f) return bucket
    val side = if (bucket != 0f) (if (bucket > 0f) 1f else -1f) else (if (real < 0f) -1f else 1f)
    return side * canon
}

private fun canonOf(degrees: Float): Float = when {
    degrees < 18f -> 0f
    degrees < 60f -> SLIGHT
    degrees < 110f -> 90f
    else -> SHARP
}

private const val SLIGHT = 35f
private const val SHARP = 135f

/** Exit N off a roundabout → the angle its arm leaves the ring at.
 *
 *  Right-hand traffic circulates counter-clockwise, so exits come up on the
 *  right first: exit 1 is the near right, exit 2 is usually straight on. The
 *  fourth and later exits share an arm — how far round the ring the driver
 *  actually goes is said by the driven sweep, not by a fifth arm angle nobody
 *  can tell from the fourth. */
internal fun roundaboutExitBendDeg(exit: Int?, fallback: Float): Float = when (exit) {
    null -> fallback
    1 -> 90f
    2 -> 0f
    3 -> -90f
    else -> -SHARP
}

/** [dimColor] draws context — the road not taken, the traffic being joined. */
@Composable
internal fun ManeuverGlyph(
    type: String?,
    modifier: String?,
    color: Color,
    dimColor: Color,
    glyphModifier: Modifier = Modifier,
    /** OSRM `bearing_after − bearing_before`, when the route carried it. */
    turnAngleDeg: Float? = null,
    /** Roundabout exit, 1-based — the arm is drawn where that exit is. */
    exitNumber: Int? = null,
) {
    Canvas(modifier = glyphModifier) {
        drawManeuver(type, modifier, color, dimColor, turnAngleDeg, exitNumber)
    }
}

private fun DrawScope.drawManeuver(
    type: String?,
    modifier: String?,
    color: Color,
    dim: Color,
    turnAngleDeg: Float?,
    exitNumber: Int?,
) {
    val g = Grid(size.minDimension)
    val bend = canonBendDeg(modifier, turnAngleDeg)

    when (type) {
        "arrive" -> drawFinishFlag(color, dim, g)
        "depart" -> drawDepart(color, dim, g)
        "roundabout", "rotary", "roundabout turn", "exit roundabout" ->
            drawRoundabout(roundaboutExitBendDeg(exitNumber, bend), color, dim, g)
        "merge" -> drawMerge(bend, color, dim, g)
        "on ramp", "off ramp" -> drawRamp(bend, type == "off ramp", color, dim, g)
        "fork" -> drawFork(bend, color, dim, g)
        "uturn" -> drawUturn(color, g)
        else -> if (bend == 180f) drawUturn(color, g) else drawTurn(bend, color, g)
    }
}

/* ── the common turn: stem, held-radius fillet, head ───────────────────── */

private fun DrawScope.drawTurn(bendDeg: Float, color: Color, g: Grid) {
    val cx = size.width / 2f
    val baseY = 22f * g.u
    if (bendDeg == 0f) {
        drawArrow(Offset(cx, baseY), Offset(cx, 3f * g.u), 0f, 0f, color, g)
        return
    }
    // Різкий поворот бере піввот вище: рука за 135° йде вниз-убік, і з 13u вона
    // вийшла б за живе поле знизу.
    val pivotY = if (abs(bendDeg) > 100f) 11f * g.u else 13f * g.u
    val arm = armFor(bendDeg, g)
    drawArrow(Offset(cx, baseY), Offset(cx, pivotY), bendDeg, arm, color, g)
}

/**
 * The shortest arm that still fits a full-radius fillet, the shaft's stub and
 * the head. Derived rather than tabulated: the fillet radius is the constant,
 * so the arm is what has to give when the bend gets sharp.
 */
private fun armFor(bendDeg: Float, g: Grid): Float =
    maxOf(10f * g.u, filletRun(bendDeg, g) + g.headLen * SHAFT_STOP + g.u)

/** Tangent length of a [Grid.fillet]-radius arc through a [bendDeg] deflection. */
private fun filletRun(bendDeg: Float, g: Grid): Float =
    g.fillet * tan(abs(bendDeg) * PI.toFloat() / 360f)

/** Shaft stops short of the tip so the head is the point, not a lump on a line. */
private const val SHAFT_STOP = 0.85f

/** Stem → arc of one held radius → arm → solid head. */
private fun DrawScope.drawArrow(
    from: Offset,
    pivot: Offset,
    bendDeg: Float,
    arm: Float,
    color: Color,
    g: Grid,
) {
    val rad = bendDeg * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val tip = if (arm > 0f) Offset(pivot.x + dx * arm, pivot.y + dy * arm) else pivot
    val shaftEnd = Offset(
        tip.x - dx * g.headLen * SHAFT_STOP,
        tip.y - dy * g.headLen * SHAFT_STOP,
    )

    val path = Path().apply {
        moveTo(from.x, from.y)
        if (arm > 0f && bendDeg != 0f) {
            val side = if (bendDeg > 0f) 1f else -1f
            val run = filletRun(bendDeg, g)
            // Дуга сталого радіуса, дотична до обох плечей: центр лежить на
            // нормалі до стема, на боці повороту. Кубік із обома ручками в
            // піввоті давав криву змінної кривини — тобто розчерк.
            val enter = Offset(pivot.x, pivot.y + run)
            val centre = Offset(pivot.x + side * g.fillet, enter.y)
            lineTo(enter.x, enter.y)
            arcTo(
                rect = Rect(
                    Offset(centre.x - g.fillet, centre.y - g.fillet),
                    Size(g.fillet * 2f, g.fillet * 2f),
                ),
                startAngleDegrees = if (side > 0f) 180f else 0f,
                sweepAngleDegrees = side * abs(bendDeg),
                forceMoveTo = false,
            )
            lineTo(shaftEnd.x, shaftEnd.y)
        } else {
            lineTo(shaftEnd.x, shaftEnd.y)
        }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = g.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawHead(tip, dx, dy, color, g)
}

/** One arrowhead for the whole family: closed triangle, 90° at the apex. */
private fun DrawScope.drawHead(tip: Offset, dx: Float, dy: Float, color: Color, g: Grid) {
    val px = -dy
    val py = dx
    val back = Offset(tip.x - dx * g.headLen, tip.y - dy * g.headLen)
    val head = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(back.x + px * g.headHalf, back.y + py * g.headHalf)
        lineTo(back.x - px * g.headHalf, back.y - py * g.headHalf)
        close()
    }
    drawPath(head, color)
}

/* ── families ─────────────────────────────────────────────────────────── */

/** U-turn: up one side, a half circle over the top, back down the other. */
private fun DrawScope.drawUturn(color: Color, g: Grid) {
    val cx = size.width / 2f
    val r = 4f * g.u
    val topY = 8f * g.u
    val path = Path().apply {
        moveTo(cx + r, 22f * g.u)
        lineTo(cx + r, topY)
        arcTo(
            rect = Rect(Offset(cx - r, topY - r), Size(r * 2f, r * 2f)),
            startAngleDegrees = 0f,
            sweepAngleDegrees = -180f,
            forceMoveTo = false,
        )
        lineTo(cx - r, 18f * g.u - g.headLen * SHAFT_STOP)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = g.stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
    drawHead(Offset(cx - r, 18f * g.u), 0f, 1f, color, g)
}

/** Roundabout: the ring is the instruction; the arm is which exit. */
private fun DrawScope.drawRoundabout(bendDeg: Float, color: Color, dim: Color, g: Grid) {
    val cx = size.width / 2f
    val cy = 10f * g.u
    val r = 6f * g.u
    val box = Rect(Offset(cx - r, cy - r), Size(r * 2f, r * 2f))
    // The whole ring, faint — the junction as it exists on the ground.
    drawCircle(
        color = dim,
        radius = r,
        center = Offset(cx, cy),
        style = Stroke(width = g.context, cap = StrokeCap.Round),
    )
    // The part actually driven, bright: from the entry at the bottom, round
    // COUNTER-CLOCKWISE — that is the direction of travel in right-hand
    // traffic, and a ring drawn the other way tells the driver to go against
    // it. Canvas y grows downward, so a positive sweep is counter-clockwise
    // on the ground.
    val driven = Path().apply {
        arcTo(
            rect = box,
            startAngleDegrees = 90f,
            sweepAngleDegrees = drivenSweepDeg(bendDeg),
            forceMoveTo = true,
        )
    }
    drawPath(
        path = driven,
        color = color,
        style = Stroke(width = g.stroke, cap = StrokeCap.Round),
    )
    drawLine(
        color = color,
        start = Offset(cx, 22f * g.u),
        end = Offset(cx, cy + r),
        strokeWidth = g.stroke,
        cap = StrokeCap.Round,
    )
    val rad = bendDeg * PI.toFloat() / 180f
    val dx = sin(rad)
    val dy = -cos(rad)
    val start = Offset(cx + dx * r, cy + dy * r)
    val arm = 6f * g.u
    val tip = Offset(start.x + dx * arm, start.y + dy * arm)
    drawLine(
        color = color,
        start = start,
        end = Offset(tip.x - dx * g.headLen * SHAFT_STOP, tip.y - dy * g.headLen * SHAFT_STOP),
        strokeWidth = g.stroke,
        cap = StrokeCap.Round,
    )
    drawHead(tip, dx, dy, color, g)
}

/**
 * How far round the ring the driver goes before the exit. Entry sits at the
 * bottom; a bend of +90° (first exit, near right) is a quarter turn, straight
 * on is a half, and a hard left is three quarters.
 */
internal fun drivenSweepDeg(bendDeg: Float): Float {
    val sweep = 180f - bendDeg
    return sweep.coerceIn(45f, 320f)
}

/** Merge: the operator's lane, plus the traffic it is joining. */
private fun DrawScope.drawMerge(bendDeg: Float, color: Color, dim: Color, g: Grid) {
    val cx = size.width / 2f
    val side = if (bendDeg < 0f) 1f else -1f
    drawLine(
        color = dim,
        start = Offset(cx + side * 7f * g.u, 22f * g.u),
        end = Offset(cx + side * 1.5f * g.u, 11f * g.u),
        strokeWidth = g.context,
        cap = StrokeCap.Round,
    )
    drawArrow(
        from = Offset(cx - side * 4f * g.u, 22f * g.u),
        pivot = Offset(cx, 11f * g.u),
        bendDeg = -side * SLIGHT,
        arm = armFor(SLIGHT, g),
        color = color,
        g = g,
    )
}

/** Ramp: the mainline runs on; the operator peels off it. */
private fun DrawScope.drawRamp(bendDeg: Float, off: Boolean, color: Color, dim: Color, g: Grid) {
    val cx = size.width / 2f
    val side = if (bendDeg < 0f) -1f else 1f
    val laneX = cx - side * 3.5f * g.u
    drawLine(
        color = dim,
        start = Offset(laneX, 22f * g.u),
        end = Offset(laneX, 3f * g.u),
        strokeWidth = g.context,
        cap = StrokeCap.Round,
    )
    drawArrow(
        from = Offset(laneX, 22f * g.u),
        pivot = Offset(laneX, if (off) 13f * g.u else 11f * g.u),
        bendDeg = side * SLIGHT,
        arm = armFor(SLIGHT, g),
        color = color,
        g = g,
    )
}

/** Fork: both arms exist; only one of them is the instruction. */
private fun DrawScope.drawFork(bendDeg: Float, color: Color, dim: Color, g: Grid) {
    val cx = size.width / 2f
    val pivotY = 13f * g.u
    val taken = if (bendDeg < 0f) -1f else if (bendDeg > 0f) 1f else 0f
    if (taken != 0f) {
        // The road not taken, drawn faint so the choice is visible as a choice.
        val rad = -taken * SLIGHT * PI.toFloat() / 180f
        val len = 8f * g.u
        drawLine(
            color = dim,
            start = Offset(cx, pivotY),
            end = Offset(cx + sin(rad) * len, pivotY - cos(rad) * len),
            strokeWidth = g.context,
            cap = StrokeCap.Round,
        )
    }
    drawArrow(
        from = Offset(cx, 22f * g.u),
        pivot = Offset(cx, pivotY),
        bendDeg = taken * SLIGHT,
        arm = if (taken == 0f) 0f else armFor(SLIGHT, g),
        color = color,
        g = g,
    )
}

/** Depart: where the drive starts. */
private fun DrawScope.drawDepart(color: Color, dim: Color, g: Grid) {
    val cx = size.width / 2f
    drawCircle(color = dim, radius = 2f * g.u, center = Offset(cx, 21f * g.u))
    drawArrow(
        from = Offset(cx, 18f * g.u),
        pivot = Offset(cx, 4f * g.u),
        bendDeg = 0f,
        arm = 0f,
        color = color,
        g = g,
    )
}

/** Arrive: a flag, because «фініш попереду» is a place, not a direction. */
private fun DrawScope.drawFinishFlag(color: Color, dim: Color, g: Grid) {
    val poleX = 8f * g.u
    drawLine(
        color = color,
        start = Offset(poleX, 22f * g.u),
        end = Offset(poleX, 3f * g.u),
        strokeWidth = g.stroke,
        cap = StrokeCap.Round,
    )
    val pennant = Path().apply {
        moveTo(poleX, 3.5f * g.u)
        lineTo(20f * g.u, 7.5f * g.u)
        lineTo(poleX, 11.5f * g.u)
        close()
    }
    drawPath(pennant, color)
    // The ground the flag stands on — keeps the glyph from floating.
    drawLine(
        color = dim,
        start = Offset(4f * g.u, 22f * g.u),
        end = Offset(15f * g.u, 22f * g.u),
        strokeWidth = g.context,
        cap = StrokeCap.Round,
    )
}
