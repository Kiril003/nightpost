package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * NAV-6 — `transit.home`: «додому громадським» as one action. The honest
 * OSM-only heuristic: route relations serving stops near the operator ∩
 * relations serving stops near home = the direct lines. The best match is
 * drawn on the living map. No schedule invention — OSM carries no
 * timetables, and when no direct line exists the tool says so and points
 * at transit.routes instead of fabricating a transfer plan.
 */
class TransitHomeTool(
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    /** The operator's saved home, or null — wired from the Vault in :app. */
    private val resolveHome: suspend () -> Pair<Double, Double>?,
    private val dossier: PlaceDossierClient = PlaceDossierClient(),
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "transit.home"

    override val description =
        "«Додому громадським» одним викликом: знаходить ПРЯМІ лінії транспорту (OSM route-відносини), що " +
            "проходять і біля оператора, і біля дому, та малює найкращу на живій мапі. Дім — збережена у " +
            "Vault точка «Дім»/«home» або передані home_lat/home_lon. Розкладів в OSM НЕМАЄ — не вигадуй " +
            "час; якщо прямої лінії нема, чесно скажи і запропонуй transit.routes."

    override val argsSchema: JsonObject = buildJsonObject {
        put("home_lat", JsonPrimitive("number?"))
        put("home_lon", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.transit.home", "Додому громадським")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val here = runCatching { locationProvider?.current() }.getOrNull()?.let { it.lat to it.lon }
            ?: return ToolResult.Err(
                "no_location",
                "локація оператора невідома — без точки «звідки» прямої лінії не знайти. " +
                    "Наступний крок: попроси оператора увімкнути геолокацію, або назви вулицю, " +
                    "щоб я знайшов її через geo.search.",
                elapsed(),
            )
        val home = run {
            val hl = args["home_lat"]?.jsonPrimitive?.doubleOrNull
            val ho = args["home_lon"]?.jsonPrimitive?.doubleOrNull
            if (hl != null && ho != null) hl to ho else runCatching { resolveHome() }.getOrNull()
        } ?: return ToolResult.Err(
            "no_home",
            "дім не збережено — попроси оператора зберегти точку «Дім» у сховище з мапи, " +
                "або передай home_lat/home_lon прямо у виклик.",
            elapsed(),
        )

        val straightM = haversineMeters(here.first, here.second, home.first, home.second)
        val nearMe = runCatching { dossier.fetchStopRoutes(here.first, here.second, radiusM = STOP_RADIUS_M) }
            .getOrDefault(emptyList())
        val nearHome = runCatching { dossier.fetchStopRoutes(home.first, home.second, radiusM = STOP_RADIUS_M) }
            .getOrDefault(emptyList())
        // The dossier client collapses a network failure into an empty list, so
        // "нема зупинок" and "Overpass лежить" arrive identical. Do not pick one.
        if (nearMe.isEmpty()) {
            return ToolResult.Ok(
                "У радіусі $STOP_RADIUS_M м від оператора я не отримав жодної лінії. Два пояснення, " +
                    "і я не можу їх розрізнити: поруч справді нема зупинок, або мапа зараз не відповіла. " +
                    "До дому по прямій ${straightM.toInt()} м. Повтори виклик, а якщо повториться — " +
                    "пішки до найближчої зупинки або таксі.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("direct_lines", buildJsonArray {})
                    put("straight_line_m", straightM.toInt())
                    put("stop_radius_m", STOP_RADIUS_M)
                    put("confidence", "низька: порожньо і збій джерела виглядають однаково")
                    put("source", "OSM · Overpass")
                    put("schedule", "невідомий")
                },
                elapsed(),
            )
        }

        val homeIds = nearHome.map { it.relationId }.toSet()
        val direct = nearMe.filter { it.relationId in homeIds }
        if (direct.isEmpty()) {
            return ToolResult.Ok(
                "Прямої лінії додому на мапі немає (біля оператора: " +
                    nearMe.take(5).joinToString { it.ref?.let { r -> "№$r" } ?: it.name.orEmpty() } +
                    "). До дому по прямій ${straightM.toInt()} м. Пересадковий план не вигадую — " +
                    "наступний крок: transit.routes біля дому, щоб побачити, що туди їде.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("direct_lines", buildJsonArray {})
                    put("straight_line_m", straightM.toInt())
                    put("stop_radius_m", STOP_RADIUS_M)
                    put("lines_near_operator", buildJsonArray {
                        nearMe.take(10).forEach { l -> add(JsonPrimitive(l.ref?.let { "№$it" } ?: l.name ?: "?")) }
                    })
                    put("source", "OSM · Overpass")
                    put("schedule", "невідомий")
                },
                elapsed(),
            )
        }

        // OSM returns relations in no meaningful order, so "перша" is not "краща".
        val best = direct.first()
        val segments = dossier.fetchRouteSegments(best.relationId)
        val geometry = segments.flatten()
        val label = best.ref?.let { "№$it" } ?: best.name ?: "маршрут"
        val layerId = if (geometry.size >= 2) {
            val color = best.colour ?: "#22C55E"
            sink.push(
                SpatialLayerSpec(
                    title = "додому: $label",
                    origin = LayerOrigin.WORLD,
                    kind = "transit",
                    ownerThreadId = currentThreadId(),
                    markers = listOf(
                        SpatialMarker(here.first, here.second, "ви тут"),
                        SpatialMarker(home.first, home.second, "дім"),
                    ),
                    lines = segments.map { seg ->
                        SpatialLine(
                            points = seg.map { GeoPoint(it.first, it.second) },
                            color = color,
                            width = 5f,
                            label = label,
                        )
                    },
                    color = color,
                    glyph = "маршрути",
                    ttlMs = 60 * 60_000L,
                ),
            ).also {
                runCatching {
                    sink.requestFocus(
                        FocusTarget((here.first + home.first) / 2, (here.second + home.second) / 2, layerId = it),
                    )
                }
                requestMapOpen()
            }
        } else null

        return ToolResult.Ok(
            output = "Прямі лінії додому: " +
                direct.take(5).joinToString { (it.ref?.let { r -> "№$r" } ?: it.name ?: "?") + " (${it.mode})" } +
                (if (layerId != null) ". Намалював $label на мапі." else ". Лінію не намалювати — є лише список.") +
                " До дому по прямій ${straightM.toInt()} м. Малював $label лише тому, що вона перша у " +
                "списку — це не «найкраща». Розкладу мапа не знає, часу відправлення не називай.",
            structured = buildJsonObject {
                layerId?.let { put("layer_id", it) }
                put("results", buildJsonArray {
                    direct.take(10).forEach { l ->
                        add(buildJsonObject {
                            put("title", l.ref?.let { r -> "№$r" } ?: l.name ?: "маршрут")
                            put("description", listOfNotNull(l.mode, l.from, l.to).joinToString(" · "))
                            put("kind", l.mode)
                        })
                    }
                })
                put("direct_lines", buildJsonArray {
                    direct.take(10).forEach { l ->
                        add(buildJsonObject {
                            l.ref?.let { put("ref", it) }
                            put("mode", l.mode)
                            l.from?.let { put("from", it) }
                            l.to?.let { put("to", it) }
                        })
                    }
                })
                put("count", direct.size)
                put("drawn", label)
                put("ranked_by", "порядок відповіді OSM, не якість")
                put("straight_line_m", straightM.toInt())
                put("stop_radius_m", STOP_RADIUS_M)
                put("source", "OSM · Overpass")
                put("schedule", "невідомий")
            },
            durationMs = elapsed(),
        )
    }

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
        return 6_371_000.0 * 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    }

    private companion object {
        /** A comfortable walk to the stop; wider would promise too much. */
        const val STOP_RADIUS_M = 450
    }
}
