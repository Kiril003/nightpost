package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.GeoPoint
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.SpatialLine
import local.phantom.companion.core.data.spatial.SpatialRegion
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * ATLAS-5 — `map.zone`: PHANTOM's tactical hand on the map. Where
 * `map.show` draws *data*, this draws *judgement*: a named exclusion zone
 * («уникати»), a safe corridor («тихий хід»), or a watch area («пильнуй»),
 * as a first-class layer — persisted, listed, toggleable, TTL'd — through
 * the same [SpatialLayerSink] every other spatial object rides.
 *
 * A zone is either a CIRCLE (lat + lon + radius_m) or a CORRIDOR (points
 * as "lat,lon" strings, drawn as a wide line). Colour states the verdict —
 * red / green / amber — and cannot be overridden: a green exclusion zone
 * is a lie in paint.
 */
class MapZoneTool(
    private val sink: SpatialLayerSink,
    private val currentThreadId: () -> String?,
) : Tool {

    override val id = "map.zone"

    override val description =
        "Намалювати на мапі тактичну зону від Nightpost. zone: exclusion (уникати, червона), " +
            "safe (безпечний коридор, зелена), watch (пильнувати, бурштинова). label — коротка назва причини. " +
            "Коло: lat + lon + radius_m. Коридор: points — масив рядків \"lat,lon\" (≥2). " +
            "ttl_min — коли зона сама зникне (типово 120 хв; 0 — постійна). " +
            "Зона лягає шаром «від Nightpost»: її видно у списку шарів, можна перемкнути чи прибрати map.clear."

    override val argsSchema: JsonObject = buildJsonObject {
        put("zone", JsonPrimitive("string"))
        put("label", JsonPrimitive("string"))
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
        put("points", JsonPrimitive("array?"))
        put("ttl_min", JsonPrimitive("number?"))
    }

    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = FeatureManifest(
        id = "tool.map.zone",
        tier = 0,
        displayName = "Тактична зона",
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
        val zone = args["zone"]?.jsonPrimitive?.content?.trim()?.lowercase()
        val (color, verdict) = when (zone) {
            "exclusion" -> "#EF4444" to "уникати"
            "safe" -> "#22C55E" to "безпечний коридор"
            "watch" -> "#F59E0B" to "пильнувати"
            else -> return ToolResult.Err(
                "bad_args",
                "зони не намалював: zone=«${zone ?: "не передано"}» не є вердиктом. " +
                    "Дозволені три: exclusion (уникати), safe (безпечний коридор), watch (пильнувати).",
                dur(),
            )
        }
        val label = args["label"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "зони не намалював: не передано label. Зона без причини — пляма на мапі, " +
                    "яку через годину ніхто не пояснить. Дай коротку причину («міни», «завал», «затоплено»).",
                dur(),
            )

        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        val radiusM = args["radius_m"]?.jsonPrimitive?.doubleOrNull
        val corridor = args["points"]?.let { el ->
            runCatching {
                el.jsonArray.mapNotNull { p ->
                    val parts = p.jsonPrimitive.content.split(",")
                    val la = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    val lo = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: return@mapNotNull null
                    GeoPoint(la, lo)
                }
            }.getOrDefault(emptyList())
        }.orEmpty()

        val regions: List<SpatialRegion>
        val lines: List<SpatialLine>
        when {
            corridor.size >= 2 -> {
                regions = emptyList()
                lines = listOf(
                    SpatialLine(points = corridor, color = color, width = 8f, label = label),
                )
            }
            lat != null && lon != null && radiusM != null && radiusM > 0 -> {
                regions = listOf(SpatialRegion(lat, lon, radiusM, color, label))
                lines = emptyList()
            }
            else -> return ToolResult.Err(
                "bad_args",
                "зони «$label» не намалював: не вийшло ні кола, ні коридору. " +
                    "Коло потребує lat + lon + radius_m (>0); коридор — points як мінімум з двох " +
                    "рядків «широта,довгота». Розібрано точок коридору: ${corridor.size}.",
                dur(),
            )
        }

        // TTL: zones are judgements about NOW; a stale verdict is worse than
        // none, so they default to two hours. ttl_min=0 pins one permanently
        // (a minefield report is not a rain shower).
        val ttlMin = args["ttl_min"]?.jsonPrimitive?.doubleOrNull
        val ttlMs = when {
            ttlMin == null -> DEFAULT_TTL_MS
            ttlMin <= 0.0 -> null
            else -> (ttlMin * 60_000).toLong()
        }

        val spec = SpatialLayerSpec(
            title = "$verdict · $label",
            origin = LayerOrigin.PHANTOM,
            kind = "zone-$zone",
            ownerThreadId = currentThreadId(),
            regions = regions,
            lines = lines,
            color = color,
            glyph = "зона",
            ttlMs = ttlMs,
        )
        val layerId = sink.push(spec)

        val focus = regions.firstOrNull()?.let { it.lat to it.lon }
            ?: corridor[corridor.size / 2].let { it.lat to it.lon }
        runCatching { sink.requestFocus(FocusTarget(focus.first, focus.second, layerId = layerId)) }

        val shape = if (lines.isEmpty()) "коло r=${radiusM?.toInt()} м" else "коридор із ${corridor.size} точок"
        return ToolResult.Ok(
            output = "Позначив зону «$label» ($verdict, $shape). " +
                (if (ttlMs != null) "Зникне за ${ttlMs / 60_000} хв. " else "Постійна. ") +
                "Це моє судження, а не виміряні дані — перевіряй. id=$layerId",
            structured = buildJsonObject {
                put("layer_id", layerId)
                put("zone", zone)
                put("verdict", verdict)
                put("label", label)
                put("shape", if (lines.isEmpty()) "коло" else "коридор")
                if (lines.isEmpty()) {
                    put("lat", focus.first)
                    put("lon", focus.second)
                    radiusM?.let { put("radius_m", it) }
                } else {
                    put("points", corridor.size)
                    put("lat", focus.first)
                    put("lon", focus.second)
                }
                ttlMs?.let {
                    put("ttl_min", it / 60_000)
                    put("expires_at_ms", System.currentTimeMillis() + it)
                }
                put("permanent", ttlMs == null)
                // A zone is a verdict, not a measurement; nothing downstream may
                // present it with the weight of sensed data.
                put("source", "phantom_judgement")
                put("authoritative", false)
            },
            durationMs = dur(),
        )
    }

    private companion object {
        const val DEFAULT_TTL_MS = 2 * 60 * 60_000L
    }
}
