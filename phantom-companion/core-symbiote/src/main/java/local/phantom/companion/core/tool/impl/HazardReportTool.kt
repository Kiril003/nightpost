package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.isRefusedMarkKind
import local.phantom.companion.core.data.spatial.pushHazardMark
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * R4.6 — the HONEST "де патрулі / небезпеки" half. There is NO magic police
 * feed (Law 1: absent source = absent capability). Instead this is a
 * self/crowd-report: the operator tells the phantom what they saw ("патруль
 * на розі", "ДТП", "ремонт"), and it drops a time-limited marker onto a
 * single growing "позначки на мапі" layer. Reports self-expire (default 2h)
 * so stale warnings don't mislead. Never presented as authoritative — it is
 * exactly, and only, what the operator reported.
 */
class HazardReportTool(
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
) : Tool {

    override val id = "world.report"
    override val description =
        "Позначити на мапі те, що оператор особисто побачив/повідомив: kind (дтп|ремонт|затор|небезпека|камера|інше), note (опис), lat/lon (за замовч. поточна локація), ttl_min (за замовч. 120 — позначка сама зникне). Це НЕ офіційний фід — лише самозвіт оператора; так і подавай («ти позначив…»), нічого не вигадуй сам."
    override val argsSchema: JsonObject = buildJsonObject {
        put("kind", JsonPrimitive("string"))
        put("note", JsonPrimitive("string?"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("ttl_min", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> = setOf(ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.world.report", "Позначка на мапі", tier = 0)

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val kind = args["kind"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "не позначив: не сказано ЩО саме. Передай kind — дтп, ремонт, затор, " +
                    "небезпека, камера або інше — і повтори виклик.",
                dur(),
            )
        if (isRefusedMarkKind(kind)) {
            return ToolResult.Err(
                "refused_kind",
                "не позначив «$kind»: такі позначки на мапу не ставляться — " +
                    "позначки бачать інші люди. Скажи людині це прямо, не шукай обхідного слова.",
                dur(),
            )
        }
        val note = args["note"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        var positionFromArgs = true
        val (lat, lon) = run {
            val la = args["lat"]?.jsonPrimitive?.doubleOrNull
            val lo = args["lon"]?.jsonPrimitive?.doubleOrNull
            if (la != null && lo != null) la to lo
            else runCatching { locationProvider?.current() }.getOrNull()
                ?.also { positionFromArgs = false }
                ?.let { it.lat to it.lon }
                ?: return ToolResult.Err(
                    "no_location",
                    "не позначив «$kind»: немає координат. Позначка без точки нічого не варта — " +
                        "передай lat/lon вручну або увімкни геолокацію і повтори.",
                    dur(),
                )
        }
        val ttlMin = (args["ttl_min"]?.jsonPrimitive?.intOrNull ?: 120).coerceIn(5, 24 * 60)

        // NAV-5 — the merge/replace/TTL logic is the shared pushHazardMark:
        // the operator's finger on the long-press sheet writes the exact
        // same layer (architecture rule 2 — one capability, two hands).
        val result = sink.pushHazardMark(
            kind = kind, lat = lat, lon = lon,
            note = note, ttlMin = ttlMin, threadId = currentThreadId(),
        )
        runCatching {
            sink.requestFocus(local.phantom.companion.core.data.spatial.FocusTarget(lat, lon, layerId = result.layerId))
        }
        val expiresAt = System.currentTimeMillis() + ttlMin * 60_000L
        return ToolResult.Ok(
            output = "Позначив «$kind»${note?.let { " ($it)" } ?: ""} на мапі" +
                " (зникне за $ttlMin хв). Усього позначок: ${result.markCount}. " +
                "Джерело — твій власний звіт, не офіційний фід: перевіряти нема кому.",
            structured = buildJsonObject {
                put("layer_id", result.layerId)
                put("kind", kind)
                note?.let { put("note", it) }
                put("lat", lat)
                put("lon", lon)
                put("position_source", if (positionFromArgs) "аргументи виклику" else "поточна геолокація")
                put("ttl_min", ttlMin)
                put("expires_at_ms", expiresAt)
                put("count", result.markCount)
                // The one field that keeps this from being read as intelligence.
                put("source", "operator_self_report")
                put("authoritative", false)
            },
            durationMs = System.currentTimeMillis() - start,
        )
    }
}
