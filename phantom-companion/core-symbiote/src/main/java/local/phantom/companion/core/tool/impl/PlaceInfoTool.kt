package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialMarker
import local.phantom.companion.core.net.map.OpeningHours
import local.phantom.companion.core.net.map.PlaceDossierClient
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * NAV-4 — `place.info`: the dossier as a tool-verb (Law 2 — dual control:
 * the same facts the operator's tap assembles in the dossier sheet).
 * Real sources only: OSM tags via Overpass, uk.wikipedia geosearch, the
 * building footprint. Silent sources are silently absent from the output —
 * the model must never pad the gaps with invention.
 *
 * The dossier client swallows a dead network into an empty list, so «нічого
 * не знайшов» and «не було звʼязку» arrive here looking identical. The
 * answer therefore never claims OSM is empty: when no source answered at
 * all it says both причини and hands the operator a next step.
 */
class PlaceInfoTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
    private val dossier: PlaceDossierClient = PlaceDossierClient(),
    private val requestMapOpen: () -> Unit = {},
) : Tool {

    override val id = "place.info"

    override val description =
        "Досьє місця за координатами: реальні теги OSM (відчинено/зачинено наживо з opening_hours, телефон, " +
            "сайт, кухня, доступність), витяг з української Вікіпедії поблизу точки, картка будівлі " +
            "(поверхи/тип/адреса), лінії транспорту якщо це зупинка. Ставить мітку на живій мапі. " +
            "Уживай, коли оператор питає «що це за місце / розкажи про X на мапі». Передай lat/lon " +
            "(і name, якщо відома назва — точніший збіг). Відповідай ЛИШЕ фактами зі structured — " +
            "чого там нема, того чесно нема. Поле sources каже, які джерела озвались: якщо жодне, " +
            "це «не дізнався», а не «там нічого немає»."

    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number"))
        put("lon", JsonPrimitive("number"))
        put("name", JsonPrimitive("string?"))
    }

    override val permissions: Set<ToolPermission> = setOf(ToolPermission.NETWORK)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.place.info",
        tier = 0,
        displayName = "Досьє місця",
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
        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        if (lat == null || lon == null) {
            return ToolResult.Err(
                "bad_args",
                "Про яку точку питаєш? Потрібні lat і lon у десяткових градусах — " +
                    "візьми їх із map.search, place.list або з мітки, яку оператор торкнувся.",
                dur(),
            )
        }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) {
            return ToolResult.Err(
                "bad_coords",
                "Координати ($lat, $lon) поза мапою: широта −90…90, довгота −180…180. " +
                    "Схоже, широту й довготу переставлено місцями — передай ще раз, широта перша.",
                dur(),
            )
        }
        val nameHint = args["name"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

        val named = runCatching { dossier.fetchNamedAround(lat, lon) }.getOrDefault(emptyList())
        val hint = nameHint?.lowercase()
        val best = named.firstOrNull { d ->
            hint != null && d.tags["name"]?.lowercase()?.let { it.contains(hint) || hint.contains(it) } == true
        } ?: named.firstOrNull()
        val tags = best?.tags ?: emptyMap()

        val building = runCatching { dossier.fetchBuildingAt(lat, lon) }.getOrNull()
        val wiki = runCatching { dossier.fetchWiki(lat, lon, nameHint) }.getOrNull()
        val isStop = tags["highway"] == "bus_stop" || tags["amenity"] == "bus_station" ||
            tags["railway"] in setOf("tram_stop", "station", "halt", "subway_entrance") ||
            tags["public_transport"] in setOf("platform", "stop_position", "station")
        val lines = if (isStop) {
            runCatching { dossier.fetchStopRoutes(best?.lat ?: lat, best?.lon ?: lon) }.getOrDefault(emptyList())
        } else emptyList()

        val sources = buildList {
            if (named.isNotEmpty()) add("мапа: місце")
            if (building != null) add("мапа: будівля")
            if (wiki != null) add("Вікіпедія (uk)")
            if (lines.isNotEmpty()) add("мапа: маршрути транспорту")
        }
        val nameMatched = hint != null && best != null &&
            best.tags["name"]?.lowercase()?.let { it.contains(hint) || hint.contains(it) } == true

        val title = tags["name"] ?: nameHint ?: "точка ${"%.5f".format(lat)}, ${"%.5f".format(lon)}"

        // Show the operator where the dossier is anchored.
        val layerId = sink.push(
            SpatialLayerSpec(
                title = title,
                origin = LayerOrigin.PHANTOM,
                kind = "point",
                ownerThreadId = currentThreadId(),
                markers = listOf(SpatialMarker(lat = lat, lon = lon, label = title)),
                ttlMs = 30 * 60_000L,
            ),
        )
        runCatching { sink.requestFocus(FocusTarget(lat, lon, layerId = layerId)) }
        requestMapOpen()

        val rawHours = tags["opening_hours"]
        val status = rawHours?.let { OpeningHours.statusNow(it) }
        val openLine = when (status) {
            is OpeningHours.Status.Open ->
                "відчинено" + (status.untilMinuteOfDay?.let { " до ${OpeningHours.fmtMinute(it)}" } ?: "")
            is OpeningHours.Status.Closed ->
                "зачинено" + (status.opensAtMinuteOfDay?.let { ", відкриється о ${OpeningHours.fmtMinute(it)}" } ?: "")
            null -> null
            else -> "графік не розібрав: $rawHours"
        }

        val summary = buildString {
            append("Досьє «").append(title).append("»")
            openLine?.let { append(" — ").append(it) }
            append(". Мітка на мапі.")
            if (sources.isEmpty()) {
                append(" Жодне джерело не озвалось: або на мапі й у Вікіпедії про цю точку нічого немає, ")
                append("або зараз немає звʼязку з ними. Так і скажи — не вигадуй опис. ")
                append("Що можна зробити: перевірити мережу і повторити, або запитати про сусідню точку.")
            }
        }

        return ToolResult.Ok(
            output = summary,
            structured = buildJsonObject {
                put("layer_id", layerId)
                put("name", title)
                put("lat", lat)
                put("lon", lon)
                put("sources", buildJsonArray { sources.forEach { add(JsonPrimitive(it)) } })
                put("coverage", if (sources.isEmpty()) "жодне джерело не озвалось" else "джерел: ${sources.size}")
                if (sources.isEmpty()) {
                    put("uncertainty", "невідомо, чи це порожнеча в даних, чи відсутній звʼязок — не стверджуй жодного")
                }
                if (nameHint != null) {
                    put("name_match", if (nameMatched) "збіг за назвою" else "за назвою не збіглось — узято найближчий обʼєкт")
                }
                openLine?.let { put("open_now", it) }
                put(
                    "open_state",
                    when (status) {
                        is OpeningHours.Status.Open -> "відчинено"
                        is OpeningHours.Status.Closed -> "зачинено"
                        null -> "графіка роботи немає"
                        else -> "графік не розібрано"
                    },
                )
                rawHours?.let { put("opening_hours", it) }
                (tags["phone"] ?: tags["contact:phone"])?.let { put("phone", it) }
                (tags["website"] ?: tags["contact:website"])?.let { put("website", it) }
                tags["cuisine"]?.let { put("cuisine", it) }
                tags["wheelchair"]?.let { put("wheelchair", it) }
                tags["addr:street"]?.let { s ->
                    put("address", s + (tags["addr:housenumber"]?.let { ", $it" } ?: ""))
                }
                building?.let { b ->
                    put(
                        "building",
                        buildJsonObject {
                            b.levels?.let { put("levels", it) }
                            b.heightM?.let { put("height_m", it) }
                            b.buildingType?.let { put("type", it) }
                        },
                    )
                }
                wiki?.let { w ->
                    put(
                        "wikipedia",
                        buildJsonObject {
                            put("title", w.title)
                            put("extract", w.extract.take(WIKI_EXTRACT_MAX))
                            put("url", w.pageUrl)
                            put("truncated", w.extract.length > WIKI_EXTRACT_MAX)
                        },
                    )
                }
                if (lines.isNotEmpty()) {
                    put(
                        "transit_lines",
                        buildJsonArray {
                            lines.take(MAX_TRANSIT_LINES).forEach { l ->
                                add(
                                    JsonPrimitive(
                                        listOfNotNull(l.ref?.let { "№$it" }, l.mode, l.to?.let { "→ $it" })
                                            .joinToString(" "),
                                    ),
                                )
                            }
                        },
                    )
                    if (lines.size > MAX_TRANSIT_LINES) put("transit_lines_total", lines.size)
                }
            },
            durationMs = dur(),
        )
    }

    private companion object {
        /** Context is a budget — the full article stays one tap away. */
        const val WIKI_EXTRACT_MAX = 900
        const val MAX_TRANSIT_LINES = 12
    }
}
