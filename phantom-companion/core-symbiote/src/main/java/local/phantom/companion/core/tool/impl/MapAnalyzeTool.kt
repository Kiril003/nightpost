package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * LEVIATHAN-5 — `map.analyze`: PHANTOM reads his own instruments and speaks
 * one tactical brief. Everything in it is the device's own truth — the EW
 * engine's trust verdict, the kinematic classifier, the live drive, the
 * layer stack, the offline-first shelter registry. Nothing is fetched,
 * nothing is invented; a field the system doesn't know is a field the brief
 * doesn't have (Law 1).
 *
 * The host injects [snapshot] because the truth lives in process-scoped
 * singletons the tool must not depend on directly.
 */
class MapAnalyzeTool(
    private val snapshot: suspend () -> Snapshot,
) : Tool {

    /** One instant of tactical truth. Null = that instrument is dark. */
    data class Snapshot(
        val lat: Double? = null,
        val lon: Double? = null,
        val accuracyM: Float? = null,
        val navigationGrade: Boolean = false,
        val dead: Boolean = false,
        /** LocationTrust name, or null when no locator runs. */
        val trust: String? = null,
        val motionState: String? = null,
        val motionConfidence: Float = 0f,
        /** Live guidance, when a drive is on. */
        val guiding: Boolean = false,
        val remainingDistanceM: Double? = null,
        val remainingDurationS: Double? = null,
        /** A drawn (not necessarily guided) corridor exists. */
        val routeDrawn: Boolean = false,
        /** Visible layer titles, newest first, capped by the host. */
        val layers: List<String> = emptyList(),
        val shelterName: String? = null,
        val shelterDistanceM: Double? = null,
        /** HORIZON — false = device has no barometer (one witness short);
         *  null = no locator, nothing to say. */
        val baroAvailable: Boolean? = null,
    )

    override val id = "map.analyze"

    override val description =
        "Зріз мапи одним викликом: де оператор і чи можна вірити місцю, як він рухається, чи йде " +
            "маршрут і скільки лишилось, які шари на мапі, де найближче укриття (по прямій; шлях " +
            "до нього рахує shelter.nearest). Без аргументів. Відповідь уже людською мовою — " +
            "переказуй її, не додаючи технічних слів."

    override val argsSchema: JsonObject = buildJsonObject { }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.map.analyze",
        tier = 0,
        displayName = "Тактичний зріз",
        erodesOperatorAgency = false,
        exfiltratesPrivate = false,
        actsWithoutConsent = false,
        abusableAgainstWeak = false,
        sunsetAfterDaysIdle = 90,
        surfaces = setOf(FeatureManifest.Surface.STREAM),
    )

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val s = runCatching { snapshot() }.getOrElse {
            return ToolResult.Err(
                "snapshot_failed",
                "Мапа зараз не відповіла — спробуй ще раз за хвилину.",
                System.currentTimeMillis() - start,
            )
        }

        val lines = ArrayList<String>(6)

        if (s.lat != null && s.lon != null) {
            // Locale.ROOT — uk_UA prints %.5f with a comma; downstream parsers expect 50.45010.
            lines += "Ти тут: %.5f, %.5f.".format(java.util.Locale.ROOT, s.lat, s.lon)
        } else {
            lines += "Де ти — поки не знаю."
        }
        trustLine(s)?.let { lines += it }

        s.motionState?.let { st ->
            val label = when (st) {
                "WALK" -> "пішки"; "RUN" -> "біг"; "CYCLE" -> "вело"
                "DRIVE" -> "авто"; "TRAIN" -> "потяг"; "FLIGHT" -> "політ"
                "STILL" -> "на місці"; else -> null
            }
            if (label != null) {
                val hedge = if (s.motionConfidence < 0.7f) " (ймовірно)" else ""
                lines += "Рух: $label$hedge."
            }
        }

        lines += when {
            s.guiding && s.remainingDistanceM != null && s.remainingDurationS != null ->
                "Маршрут ведеться: лишилось ${fmtM(s.remainingDistanceM)} (~${(s.remainingDurationS / 60).toInt()} хв)."
            s.routeDrawn -> "Маршрут прокладений, але ведення не увімкнене."
            else -> "Активного маршруту немає."
        }

        if (s.layers.isNotEmpty()) {
            lines += "На мапі: ${s.layers.joinToString(", ")}."
        }

        if (s.shelterName != null && s.shelterDistanceM != null) {
            // Haversine: said plainly so the number is not heard as a walking distance.
            lines += "Найближче укриття: «${s.shelterName}», ${fmtM(s.shelterDistanceM)} по прямій — пішки буде довше."
        }

        val structured = buildJsonObject {
            s.lat?.let { put("lat", it) }
            s.lon?.let { put("lon", it) }
            s.accuracyM?.let { put("accuracy_m", it) }
            put("position_known", s.lat != null && s.lon != null)
            put("navigation_grade", s.navigationGrade)
            put("dead_reckoning", s.dead)
            s.trust?.let { put("trust", it) }
            put("trust_engine_active", s.trust != null)
            s.motionState?.let { put("motion", it) }
            // Without the confidence the state reads as certain; below 0.7 the
            // spoken line already hedges and the payload must hedge with it.
            s.motionState?.let { put("motion_confidence", s.motionConfidence) }
            put("guiding", s.guiding)
            s.remainingDistanceM?.let { put("remaining_m", it) }
            s.remainingDurationS?.let { put("remaining_s", it) }
            put("route_drawn", s.routeDrawn)
            put("layers", buildJsonArray { s.layers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
            put("layer_count", s.layers.size)
            s.shelterName?.let { put("shelter", it) }
            s.shelterDistanceM?.let { put("shelter_m", it) }
            s.shelterName?.let { put("shelter_measured_by", "great_circle") }
            s.baroAvailable?.let { put("barometer", it) }
            put("source", "device_instruments")
        }

        return ToolResult.Ok(
            output = lines.joinToString(" "),
            structured = structured,
            durationMs = System.currentTimeMillis() - start,
        )
    }

    // Only states that change what the person does get a sentence; a sure
    // position and a silent engine say nothing.
    private fun trustLine(s: Snapshot): String? = when (s.trust) {
        "DEGRADED" -> "Місце приблизне — можу помилитись із поворотом."
        "JAMMED" -> if (s.dead) {
            "Поруч глушать сигнал — показую останнє певне місце, і що далі, то воно приблизніше."
        } else {
            "Поруч глушать сигнал — де ти, зараз не знаю напевно."
        }
        "SPOOFED" -> "Увага: телефону підсовують чуже місце — я йому не вірю, звіряйся з тим, що бачиш навколо."
        else -> if (s.lat != null && !s.navigationGrade) "Місце приблизне — можу помилитись із поворотом." else null
    }

    private fun fmtM(m: Double): String =
        if (m >= 1000) "%.1f км".format(java.util.Locale.ROOT, m / 1000.0) else "${m.toInt()} м"
}
