package local.phantom.companion.core.vision.scene

import kotlin.math.max
import kotlin.math.min

/**
 * Прямокутник у координатах ВИПРЯМЛЕНОГО кадру, не екрана.
 *
 * Рушій повертає рамки вже після повороту буфера, тож ширина тут — це ширина
 * того, що бачить людина, а не ширина сенсора. Переведення в пікселі екрана —
 * справа [BoxProjector], і поки воно не зроблене, ці числа малювати не можна.
 */
data class VisionBox(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = max(0f, right - left)
    val height: Float get() = max(0f, bottom - top)
    val area: Float get() = width * height
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float): Boolean =
        x >= left && x <= right && y >= top && y <= bottom

    /** Наскільки перекриваються дві рамки: 0 — не дотикаються, 1 — збіглись. */
    fun overlapWith(other: VisionBox): Float {
        val l = max(left, other.left)
        val t = max(top, other.top)
        val r = min(right, other.right)
        val b = min(bottom, other.bottom)
        val intersection = max(0f, r - l) * max(0f, b - t)
        val union = area + other.area - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    /** Відстань від точки до краю рамки; 0 — точка всередині. */
    fun distanceTo(x: Float, y: Float): Float {
        val dx = max(max(left - x, 0f), x - right)
        val dy = max(max(top - y, 0f), y - bottom)
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    fun towards(target: VisionBox, amount: Float): VisionBox {
        val t = amount.coerceIn(0f, 1f)
        return VisionBox(
            left = left + (target.left - left) * t,
            top = top + (target.top - top) * t,
            right = right + (target.right - right) * t,
            bottom = bottom + (target.bottom - bottom) * t,
        )
    }
}

/** Чим обʼєкт є для розділу — від цього залежать і підпис, і дії над ним. */
enum class ObjectKind { TEXT, CODE, THING }

/**
 * Те, що рушій побачив в одному кадрі. Особи ще не має: імена роздає
 * [ObjectTracker], бо рушій кожен кадр рахує наново й нічого не памʼятає.
 */
data class Detection(
    val box: VisionBox,
    val kind: ObjectKind,
    val label: String,
    val payload: String = label,
    val confidence: Float = 1f,
)

/**
 * Обʼєкт із памʼяттю: той самий [id] між кадрами, поки він у полі зору.
 *
 * [seenForMillis] — скільки він уже тримається. Розділ показує підпис не
 * одразу, інакше кожен випадковий проблиск рушія встигає блимнути написом.
 */
data class SceneObject(
    val id: Long,
    val box: VisionBox,
    val kind: ObjectKind,
    val label: String,
    val payload: String = label,
    val confidence: Float = 1f,
    val seenForMillis: Long = 0L,
    val fading: Boolean = false,
)

/**
 * Розміри кадру після повороту. За 90° і 270° сторони міняються місцями —
 * саме в цих координатах рушій повертає рамки, і саме їх треба давати
 * проєктору, інакше накладання розтягнуте поперек.
 */
fun uprightWidth(width: Int, height: Int, rotationDegrees: Int): Int =
    if (Math.floorMod(rotationDegrees, 180) == 0) width else height

fun uprightHeight(width: Int, height: Int, rotationDegrees: Int): Int =
    if (Math.floorMod(rotationDegrees, 180) == 0) height else width
