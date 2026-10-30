package local.phantom.companion.core.nav

import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.net.map.RouteStep
import local.phantom.companion.core.net.map.RouteVariant
import java.util.Calendar
import kotlin.math.abs

/** Відмінки прописані руками: генератор відмінювання тут дав би «за аптекоюю». */
enum class LandmarkKind(
    val nom: String,
    val ins: String,
    val gen: String,
    val acc: String,
    val needsLight: Boolean,
    val rank: Int,
) {
    BRIDGE("міст", "мостом", "мосту", "міст", false, 9),
    ARCH("арка", "аркою", "арки", "арку", false, 8),
    CHURCH("церква", "церквою", "церкви", "церкву", false, 7),
    TEMPLE("храм", "храмом", "храму", "храм", false, 6),
    FUEL("заправка", "заправкою", "заправки", "заправку", true, 6),
    SUPERMARKET("супермаркет", "супермаркетом", "супермаркету", "супермаркет", true, 5),
    STOP("зупинка", "зупинкою", "зупинки", "зупинку", false, 5),
    MARKET("ринок", "ринком", "ринку", "ринок", true, 4),
    HOSPITAL("лікарня", "лікарнею", "лікарні", "лікарню", false, 4),
    PHARMACY("аптека", "аптекою", "аптеки", "аптеку", true, 4),
    POST("пошта", "поштою", "пошти", "пошту", true, 3),
    BANK("банк", "банком", "банку", "банк", true, 2),
    POLICE("поліція", "поліцією", "поліції", "поліцію", false, 2),
    SHOP("магазин", "магазином", "магазину", "магазин", true, 1),
}

data class Landmark(
    val kind: LandmarkKind,
    /** Уже очищена назва ([landmarkName]); null — лише рід речі. */
    val name: String?,
    val lat: Double,
    val lon: Double,
    val hours: String? = null,
)

/** Міст чи арка: те, крізь що або по чому проходить сама дорога. */
data class RouteLine(val kind: LandmarkKind, val points: List<Pair<Double, Double>>)

data class LandmarkField(
    val places: List<Landmark> = emptyList(),
    val lines: List<RouteLine> = emptyList(),
) {
    val isEmpty: Boolean get() = places.isEmpty() && lines.isEmpty()
}

enum class LandmarkRelation { AFTER, AT, THROUGH, TOWARD }

data class StepHint(
    val kind: LandmarkKind,
    val name: String?,
    val relation: LandmarkRelation,
    val soon: Boolean = false,
    val hours: String? = null,
) {
    /** Темна вітрина вночі не орієнтир, а невідомі години вночі — теж темрява. */
    fun visible(nowMs: Long, dark: Boolean): Boolean {
        if (!kind.needsLight || !dark) return true
        val cal = Calendar.getInstance().apply { timeInMillis = nowMs }
        return OpeningHours.statusNow(hours, cal) is OpeningHours.Status.Open
    }
}

fun interface LandmarkSource {
    suspend fun near(anchors: List<Pair<Double, Double>>, radiusM: Double): LandmarkField
}

object TurnLandmarks {

    const val QUERY_RADIUS_M = 220.0

    private val TURN_TYPES = setOf(
        "turn", "end of road", "fork", "off ramp", "on ramp", "merge",
        "roundabout", "rotary", "roundabout turn", "exit roundabout",
    )
    private val SIDEWAYS = setOf("left", "right", "slight left", "slight right", "sharp left", "sharp right")

    fun isTurn(step: RouteStep): Boolean = when (step.type) {
        "roundabout", "rotary", "roundabout turn", "exit roundabout" -> true
        in TURN_TYPES -> step.modifier in SIDEWAYS
        else -> false
    }

    fun anchors(variant: RouteVariant, path: RoutePath): List<Pair<Double, Double>> {
        val out = ArrayList<Pair<Double, Double>>()
        variant.steps.forEachIndexed { i, s ->
            if (isTurn(s)) out += s.lat to s.lon
            if (i == 0 && variant.profile == RouteProfile.FOOT) {
                val p = path.pointAtM(minOf(TOWARD_MAX_M * 0.6, path.totalDistM))
                out += p.lat to p.lon
            }
        }
        return out
    }

    private class Win(val afterMin: Double, val afterMax: Double, val atBack: Double, val off: Double)

    private fun winFor(profile: RouteProfile) = if (profile == RouteProfile.FOOT) {
        Win(afterMin = 5.0, afterMax = 45.0, atBack = 10.0, off = 25.0)
    } else {
        Win(afterMin = 10.0, afterMax = 80.0, atBack = 15.0, off = 35.0)
    }

    /** Кандидати для кожного кроку, найкращий першим; вибір — у мить мовлення. */
    fun plan(variant: RouteVariant, path: RoutePath, field: LandmarkField): Map<Int, List<StepHint>> {
        if (field.isEmpty) return emptyMap()
        val win = winFor(variant.profile)
        val lines = field.lines.mapNotNull { spanOf(it, path) }
        val placed = field.places.mapNotNull { lm ->
            val snap = snapToPolyline(path.points, path.cumDistM, lm.lat, lm.lon)
            if (snap.offMeters <= win.off) lm to snap.alongM else null
        }
        val out = HashMap<Int, List<StepHint>>()
        variant.steps.forEachIndexed { i, step ->
            val at = path.stepAlongM.getOrNull(i) ?: return@forEachIndexed
            val c = when {
                i == 0 && variant.profile == RouteProfile.FOOT -> towardHints(placed, lines, path, at)
                isTurn(step) -> turnHints(placed, lines, at, win)
                else -> emptyList()
            }
            // Та сама вивіска на двох поворотах поспіль перестає щось розрізняти.
            val prev = out[i - 1].orEmpty()
            val fresh = c.filter { h -> h.name == null || prev.none { it.kind == h.kind && it.name == h.name } }
            if (fresh.isNotEmpty()) out[i] = fresh
        }
        return out
    }

    fun choose(hints: List<StepHint>?, nowMs: Long, dark: () -> Boolean): StepHint? {
        if (hints.isNullOrEmpty()) return null
        if (hints.none { it.kind.needsLight }) return hints.first()
        val d = dark()
        return hints.firstOrNull { it.visible(nowMs, d) }
    }

    private class Span(val kind: LandmarkKind, val start: Double, val end: Double)

    private fun spanOf(line: RouteLine, path: RoutePath): Span? {
        if (line.points.size < 2) return null
        var len = 0.0
        for (k in 1 until line.points.size) len += haversineM(line.points[k - 1], line.points[k])
        if (len < minLineM(line.kind)) return null
        val on = line.points.mapNotNull { (lat, lon) ->
            snapToPolyline(path.points, path.cumDistM, lat, lon).takeIf { it.offMeters <= LINE_OFF_M }?.alongM
        }
        if (on.size < 2) return null
        val start = on.min()
        val end = on.max()
        // Дорога ПІД мостом торкається його в одній точці: проліт уздовж маршруту має покрити більшість мосту.
        if (end - start < len * 0.6) return null
        return Span(line.kind, start, end)
    }

    private fun minLineM(kind: LandmarkKind) = if (kind == LandmarkKind.BRIDGE) 25.0 else 5.0

    private fun turnHints(
        placed: List<Pair<Landmark, Double>>,
        lines: List<Span>,
        at: Double,
        win: Win,
    ): List<StepHint> {
        val scored = ArrayList<Pair<StepHint, Double>>()
        for (s in lines) {
            when (s.kind) {
                LandmarkKind.BRIDGE -> {
                    val gap = at - s.end
                    if (gap in 3.0..BRIDGE_BACK_M) {
                        scored += StepHint(s.kind, null, LandmarkRelation.AFTER, soon = gap <= SOON_M) to 100.0 - gap / 10
                    }
                }
                LandmarkKind.ARCH -> when {
                    s.start in (at - 8.0)..(at + 35.0) ->
                        scored += StepHint(s.kind, null, LandmarkRelation.THROUGH) to 95.0
                    at - s.end in 8.0..60.0 ->
                        scored += StepHint(s.kind, null, LandmarkRelation.AFTER, soon = at - s.end <= 25.0) to 90.0
                }
                else -> Unit
            }
        }
        for ((lm, along) in placed) {
            val d = at - along
            val rel = when {
                d in win.afterMin..win.afterMax -> LandmarkRelation.AFTER
                d >= -win.atBack && d < win.afterMin -> LandmarkRelation.AT
                else -> continue
            }
            if (lm.kind == LandmarkKind.SHOP && lm.name == null) continue
            // Друга аптека на підході робить «за аптекою» загадкою, а не вказівкою.
            val twins = placed.count { (o, oa) ->
                o !== lm && o.kind == lm.kind && o.name == lm.name && oa in (at - AMBIGUITY_BACK_M)..(at + win.atBack) &&
                    haversineM(o.lat to o.lon, lm.lat to lm.lon) > SAME_PLACE_M
            }
            if (twins > 0) continue
            val score = lm.kind.rank * 10.0 + (if (lm.name != null) 3.0 else 0.0) - abs(d) / 10
            scored += StepHint(lm.kind, lm.name, rel, hours = lm.hours) to score
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    private fun towardHints(
        placed: List<Pair<Landmark, Double>>,
        lines: List<Span>,
        path: RoutePath,
        departAlong: Double,
    ): List<StepHint> {
        val straightEnd = path.stepAlongM.getOrNull(1) ?: path.totalDistM
        val lo = departAlong + TOWARD_MIN_M
        val hi = minOf(straightEnd + 15.0, departAlong + TOWARD_MAX_M)
        if (hi <= lo) return emptyList()
        val scored = ArrayList<Pair<StepHint, Double>>()
        lines.filter { it.kind == LandmarkKind.BRIDGE && it.start in lo..hi }
            .forEach { scored += StepHint(it.kind, null, LandmarkRelation.TOWARD) to 90.0 + it.start / 100 }
        for ((lm, along) in placed) {
            if (along !in lo..hi || lm.kind.rank < 4) continue
            if (lm.kind == LandmarkKind.SHOP && lm.name == null) continue
            scored += StepHint(lm.kind, lm.name, LandmarkRelation.TOWARD, hours = lm.hours) to lm.kind.rank * 10.0 + along / 100
        }
        return scored.sortedByDescending { it.second }.map { it.first }.distinct()
    }

    /** Крок, за яким одразу наступний поворот: «праворуч і одразу ліворуч». */
    fun chainedNext(variant: RouteVariant, path: RoutePath, stepIdx: Int): RouteStep? {
        val a = variant.steps.getOrNull(stepIdx) ?: return null
        val b = variant.steps.getOrNull(stepIdx + 1) ?: return null
        if (!isTurn(a) || !isTurn(b) || b.exitNumber != null || a.exitNumber != null) return null
        val gap = (path.stepAlongM.getOrNull(stepIdx + 1) ?: return null) - path.stepAlongM[stepIdx]
        val limit = when (variant.profile) {
            RouteProfile.FOOT -> 30.0
            RouteProfile.BIKE -> 45.0
            else -> 60.0
        }
        return b.takeIf { gap in 1.0..limit }
    }

    private const val LINE_OFF_M = 10.0
    private const val BRIDGE_BACK_M = 120.0
    private const val SOON_M = 60.0
    private const val AMBIGUITY_BACK_M = 200.0
    private const val SAME_PLACE_M = 40.0
    private const val TOWARD_MIN_M = 40.0
    const val TOWARD_MAX_M = 400.0
}

private val GENERIC_NAMES = setOf(
    "продукти", "магазин", "гастроном", "продуктовий", "продуктовий магазин", "крамниця", "мінімаркет",
    "супермаркет", "заправка", "азс", "агзс", "аптека", "пошта", "банк", "ринок", "рынок", "базар",
    "лікарня", "поліція", "церква", "храм", "зупинка",
)
private val LEADING_GENERIC = Regex("^(аптека|магазин|супермаркет|азс|заправка|банк)\\s+", RegexOption.IGNORE_CASE)
private val QUOTES = Regex("[\"«»“”„]")
private val NUMBER_TAIL = Regex("\\s*№\\s*\\d+.*$")
private val PARENS = Regex("\\s*\\(.*?\\)")

/** Назва, яку людина прочитає на вивісці, або null, коли там лише рід речі. */
fun landmarkName(raw: String?, kind: LandmarkKind): String? {
    var n = raw?.trim().orEmpty()
    if (n.isEmpty()) return null
    if (n.startsWith("АТБ", ignoreCase = true)) return "АТБ"
    n = n.replace(QUOTES, "").replace(PARENS, "").replace(NUMBER_TAIL, "").trim()
    if (kind != LandmarkKind.STOP) {
        val rest = n.replace(LEADING_GENERIC, "").trim()
        if (rest != n && rest.firstOrNull()?.isUpperCase() != true) return null
        n = rest
    }
    if (n.length < 2 || n.length > 24) return null
    if (n.lowercase() in GENERIC_NAMES || n.equals(kind.nom, ignoreCase = true)) return null
    if (kind == LandmarkKind.CHURCH || kind == LandmarkKind.TEMPLE || kind == LandmarkKind.HOSPITAL) return null
    return n
}

private val CHURCH_WORDS = Regex("церк|собор|храм|каплиц|костел|костьол", RegexOption.IGNORE_CASE)

/** Рядок газетира → орієнтир. Вулиці й дрібниця (банкомати, гідранти) — не орієнтири. */
fun landmarkKindOf(category: String, subcategory: String?, name: String?): LandmarkKind? = when (category) {
    "shop" -> when (subcategory) {
        "supermarket" -> LandmarkKind.SUPERMARKET
        "convenience", "department_store", "general", "mall" -> LandmarkKind.SHOP
        else -> null
    }
    "amenity" -> when (subcategory) {
        "fuel" -> LandmarkKind.FUEL
        "pharmacy" -> LandmarkKind.PHARMACY
        "bank" -> LandmarkKind.BANK
        "post_office" -> LandmarkKind.POST
        "marketplace" -> LandmarkKind.MARKET
        "hospital" -> LandmarkKind.HOSPITAL
        "police" -> LandmarkKind.POLICE
        "place_of_worship" -> if (name != null && CHURCH_WORDS.containsMatchIn(name)) LandmarkKind.CHURCH else LandmarkKind.TEMPLE
        else -> null
    }
    "railway" -> if (subcategory == "tram_stop") LandmarkKind.STOP else null
    "highway" -> if (subcategory == "bus_stop") LandmarkKind.STOP else null
    "public_transport" -> if (subcategory == "platform" || subcategory == "stop_position") LandmarkKind.STOP else null
    else -> null
}

/** Теги дорожньої лінії → міст чи арка, або null. */
fun routeLineKindOf(tags: Map<String, String>): LandmarkKind? = when {
    tags["tunnel"] == "building_passage" -> LandmarkKind.ARCH
    tags["bridge"].let { it != null && it != "no" } && tags["highway"] != null -> LandmarkKind.BRIDGE
    else -> null
}

fun landmarkOf(category: String, subcategory: String?, name: String?, lat: Double, lon: Double, hours: String?): Landmark? {
    val kind = landmarkKindOf(category, subcategory, name) ?: return null
    val clean = landmarkName(name, kind)
    if (kind == LandmarkKind.STOP && clean == null) return null
    return Landmark(kind, clean, lat, lon, hours)
}
