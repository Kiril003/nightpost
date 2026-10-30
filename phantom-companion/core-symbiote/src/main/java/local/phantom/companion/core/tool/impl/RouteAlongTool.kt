package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.net.map.OverpassClient
import local.phantom.companion.core.net.map.rankAlongRoute
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * NAV-Ω1 — `route.along`: «заправка по дорозі» as a verb. Searches a
 * category ALONG the operator's ACTIVE route (the same corridor logic the
 * capsule chips use mid-route — Law 2), ordered first-reachable first
 * with an honest off-route detour figure per hit. Requires a live route:
 * without one the tool refuses and points at map.nearby instead of
 * silently degrading to a radial search the operator didn't ask for.
 *
 * Every hit carries its reachability in `kind` as well as in numbers —
 * that field is the one the card actually draws under the name, and the
 * detour is the whole reason the operator asked.
 */
class RouteAlongTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
    /** The live route's polyline from the map surface, or null when none. */
    private val activeRoutePoints: () -> List<Pair<Double, Double>>?,
    private val overpass: OverpassClient = OverpassClient(),
) : Tool {

    override val id = "route.along"

    override val description =
        "Знайти категорію ПО ДОРОЗІ — вздовж АКТИВНОГО маршруту оператора, у порядку досяжності " +
            "(перша попереду — перша в списку), з чесним «збоченням» у метрах убік від маршруту. " +
            "Ті самі категорії, що map.nearby: укриття, аптеки, заправки, банкомати, магазини, їжа, " +
            "лікарні, вода. Працює ЛИШЕ коли маршрут прокладено; без маршруту вживай map.nearby. " +
            "Кладе шар на живу мапу. Дані — OSM: те, чого туди не внесли, тут не зʼявиться; " +
            "«відчинено» цей інструмент не перевіряє."

    override val argsSchema: JsonObject = buildJsonObject {
        put("category", JsonPrimitive("string"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.route.along",
        tier = 0,
        displayName = "По дорозі",
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
        fun dur() = System.currentTimeMillis() - start
        val rawCategory = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "Не сказав, що шукати по дорозі. Назви категорію: укриття, аптеки, заправки, банкомати, " +
                    "магазини, їжа, лікарні, вода.",
                dur(),
            )
        val kind = resolveNearbyKind(rawCategory)
        val selector = kind?.selector
            ?: rawCategory.lowercase().takeIf { it.matches(Regex("[a-z_]+")) }?.let { "amenity=$it" }
            ?: return ToolResult.Err(
                "bad_args",
                "Категорії «$rawCategory» не знаю. Є укриття, аптеки, заправки, банкомати, магазини, їжа, " +
                    "лікарні, вода — обери найближчу за змістом.",
                dur(),
            )
        val label = kind?.label ?: rawCategory

        val route = activeRoutePoints()
        if (route == null || route.size < 2) {
            return ToolResult.Err(
                "no_active_route",
                "Активного маршруту немає, а «по дорозі» рахується саме від нього. " +
                    "Спершу проклади маршрут (map.route або nav.start), або шукай навколо оператора — map.nearby.",
                dur(),
            )
        }

        val elements = runCatching { overpass.fetchAlong(route, radiusMeters = CORRIDOR_M, selector = selector) }
            .getOrElse {
                return ToolResult.Err(
                    "overpass_unavailable",
                    "Не дістав мапу вздовж цього шляху. " +
                        "Це «не знаю», а не «нічого нема». Спробуй ще раз, коли буде мережа, " +
                        "або подивись збережені місця (place.list).",
                    dur(),
                )
            }
        val hits = rankAlongRoute(elements, route).take(MAX_HITS)
        if (hits.isEmpty()) {
            return ToolResult.Ok(
                output = "По дорозі (до $CORRIDOR_M м убік від маршруту) не знайшов: $label. " +
                    "На мапі вздовж шляху такого нема — це не означає, що його нема насправді. " +
                    "Можна поширити пошук навколо точки призначення (map.nearby).",
                structured = buildJsonObject {
                    put("results", buildJsonArray {})
                    put("category", label)
                    put("radius_m", CORRIDOR_M)
                    put("source", "OSM/Overpass, коридор навколо активного маршруту")
                },
                durationMs = dur(),
            )
        }

        val points = hits.map { h ->
            WorldPoint(
                lat = h.lat, lon = h.lon,
                label = h.element.tags?.get("name") ?: label,
                glyph = kind?.glyph,
                detail = reachWords(h.alongM, h.offRouteM),
            )
        }
        val layerId = sink.pushWorldLayer(
            title = "по дорозі: $label", kind = "nearby", points = points,
            glyph = kind?.glyph, ownerThreadId = currentThreadId(), ttlMs = 30 * 60_000L,
        )

        val first = hits.first()
        return ToolResult.Ok(
            output = "Знайшов ${hits.size} «$label» по дорозі. Перша — ${points.first().label}, " +
                "${reachWords(first.alongM, first.offRouteM)}. Показав на мапі. " +
                "Відстані — уздовж маршруту й убік від нього; графіку роботи тут немає.",
            structured = buildJsonObject {
                layerId?.let { put("layer_id", it) }
                put("category", label)
                put("radius_m", CORRIDOR_M)
                put("found", hits.size)
                put("source", "OSM/Overpass, коридор навколо активного маршруту")
                put(
                    "results",
                    buildJsonArray {
                        hits.forEach { h ->
                            add(
                                buildJsonObject {
                                    put("name", h.element.tags?.get("name") ?: label)
                                    // `kind` — той самий рядок, який картка малює під назвою.
                                    put("kind", reachWords(h.alongM, h.offRouteM))
                                    put("along_m", h.alongM.toInt())
                                    put("off_route_m", h.offRouteM.toInt())
                                    put("lat", h.lat)
                                    put("lon", h.lon)
                                },
                            )
                        }
                    },
                )
            },
            durationMs = dur(),
        )
    }

    private companion object {
        const val MAX_HITS = 12
        const val CORRIDOR_M = 400

        fun fmtKmA(m: Double): String =
            if (m >= 1000) String.format(Locale.US, "%.1f км", m / 1000.0) else "${m.toInt()} м"

        fun reachWords(alongM: Double, offRouteM: Double): String =
            "через ${fmtKmA(alongM)} по маршруту · ${offRouteM.toInt()} м убік"
    }
}
