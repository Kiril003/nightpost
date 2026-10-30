package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

private fun trackManifest(id: String, name: String) = FeatureManifest(
    id = id,
    tier = 0,
    displayName = name,
    erodesOperatorAgency = false,
    exfiltratesPrivate = false,
    actsWithoutConsent = false,
    surfaces = setOf(FeatureManifest.Surface.MAP, FeatureManifest.Surface.STREAM),
)

data class ExportedTrack(
    val format: String,
    val pointCount: Int,
    val data: String,
)

/**
 * M-N08 — `track.export` — Export footsteps or route as GPX / GeoJSON.
 */
class TrackExportTool(
    private val exportCurrentTrack: (format: String) -> ExportedTrack?,
) : Tool {

    override val id = "track.export"

    override val description =
        "Експортувати записані сліди пересування або поточний маршрут у файл формату GPX або GeoJSON."

    override val argsSchema: JsonObject = buildJsonObject {
        put("format", JsonPrimitive("string? — 'gpx' (за замовчуванням) або 'geojson'"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD // слід лишає застосунок — назад не відкличеш
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = trackManifest("tool.track.export", "Експорт треку / GPX")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val format = args["format"]?.jsonPrimitive?.content?.lowercase() ?: "gpx"

        val result = exportCurrentTrack(format)
        if (result == null || result.pointCount == 0) {
            return ToolResult.Err(
                code = "no_track_data",
                message = "Немає записаних слідів або активного маршруту для експорту.",
                durationMs = System.currentTimeMillis() - start,
            )
        }

        return ToolResult.Ok(
            output = "Експортовано трек (${result.format.uppercase()}, ${result.pointCount} точок).\n```\n${result.data.take(500)}...\n```",
            structured = buildJsonObject {
                put("format", JsonPrimitive(result.format))
                put("point_count", JsonPrimitive(result.pointCount))
                put("content", JsonPrimitive(result.data))
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
