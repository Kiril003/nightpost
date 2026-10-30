package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk
import java.util.Locale

/**
 * R4.5 — the "living map" gains eyes on the physical world, from REAL public
 * sources (Law 1):
 *
 *  - [WorldCamerasTool] `world.cameras` — surveillance + speed/enforcement
 *    cameras from OSM (`man_made=surveillance`, `highway=speed_camera`,
 *    `enforcement`). Only cameras OSM actually records — no guessing.
 *  - [WorldAircraftTool] `world.aircraft` — live aircraft overhead from the
 *    free ADS-B feed (adsb.lol, no key), each with heading + altitude +
 *    callsign. Flying objects, real, self-expiring (a 4-min TTL layer).
 */

private val worldJson = Json { ignoreUnknownKeys = true }
private fun c(v: Double): String = String.format(Locale.US, "%.6f", v)

/** `world.cameras` — surveillance & enforcement cameras nearby (OSM). */
class WorldCamerasTool(
    private val http: GeoHttp,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val endpoint: String = GeoNearbyTool.OVERPASS_ENDPOINT,
) : Tool {

    override val id = "world.cameras"
    override val description =
        "Камери поруч з реальних даних OSM: відеоспостереження (man_made=surveillance), камери швидкості (highway=speed_camera) та контролю (enforcement). Кладе шар «камери» на мапу. Показує ЛИШЕ те, що є в OSM — не вигадуй камери."
    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_m", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.world.cameras", "Камери")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val (lat, lon) = resolveWorldCoords(args, locationProvider)
            ?: return ToolResult.Err(
                "no_location",
                "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — знайди місце через geo.search і передай його lat/lon сюди.",
                elapsed(),
            )
        val radius = (args["radius_m"]?.jsonPrimitive?.intOrNull ?: 800).coerceIn(100, 5_000)

        val ql = "[out:json][timeout:20];(" +
            "node[\"man_made\"=\"surveillance\"](around:$radius,${c(lat)},${c(lon)});" +
            "node[\"highway\"=\"speed_camera\"](around:$radius,${c(lat)},${c(lon)});" +
            "node[\"enforcement\"](around:$radius,${c(lat)},${c(lon)});" +
            ");out body 120;"
        val body = http.postText(endpoint, "data=" + java.net.URLEncoder.encode(ql, "UTF-8"))
            ?: return ToolResult.Err(
                "geo_unavailable",
                "Мапа не відповіла — камер зараз не дістати. Скажи це прямо і повтори за хвилину; " +
                    "камери з голови не називай.",
                elapsed(),
            )

        val parsed = runCatching {
            worldJson.parseToJsonElement(body).jsonObject["elements"]?.jsonArray
                ?.mapNotNull { el ->
                    val o = el.jsonObject
                    val pLat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val pLon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                    val tags = o["tags"]?.jsonObject
                    val isSpeed = tags?.get("highway")?.jsonPrimitive?.content == "speed_camera" ||
                        tags?.get("enforcement") != null
                    val kind = if (isSpeed) "радар" else "камера"
                    val sType = tags?.get("surveillance:type")?.jsonPrimitive?.content
                    val dir = tags?.get("direction")?.jsonPrimitive?.content
                    WorldPoint(
                        lat = pLat, lon = pLon,
                        label = kind,
                        glyph = kind,
                        detail = buildString {
                            append(if (isSpeed) "камера швидкості/контролю" else "відеоспостереження")
                            sType?.let { append(" · ").append(it) }
                            dir?.let { append(" · напрям ").append(it) }
                        },
                        color = if (isSpeed) CAMERA_SPEED else CAMERA_CCTV,
                    )
                }.orEmpty()
        }
        val points = parsed.getOrElse {
            return ToolResult.Err(
                "geo_unreadable",
                "Відповідь мапи не прочиталась — це НЕ означає, що камер нема. Повтори виклик.",
                elapsed(),
            )
        }

        if (points.isEmpty()) {
            return ToolResult.Ok(
                "У радіусі $radius м на мапі немає жодної камери. Камери на мапу вносять вибірково, " +
                    "тож це межа даних, а не гарантія, що камер нема — не кажи оператору «камер немає».",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("radius_m", radius)
                    put("confidence", "низька: OSM покриває камери вибірково")
                    putProvenance("OSM · Overpass", start)
                },
                elapsed(),
            )
        }

        val speed = points.count { it.color == CAMERA_SPEED }
        val cctv = points.size - speed
        val layerId = sink.pushWorldLayer(
            title = "камери", kind = "cameras", points = points,
            color = CAMERA_CCTV, glyph = "камери",
            ownerThreadId = currentThreadId(), ttlMs = 60 * 60_000L,
        )
        val structured = buildJsonObject {
            layerId?.let { put("layer_id", it) }
            put("results", worldResults(points))
            put("count", points.size)
            put("speed_cameras", speed)
            put("surveillance", cctv)
            put("radius_m", radius)
            put("confidence", "неповна: OSM фіксує камери вибірково")
            putProvenance("OSM · Overpass", start)
            put("map", worldMapObject(lat, lon, 15.0, points))
        }
        return ToolResult.Ok(
            "Камери у радіусі $radius м: $cctv відеоспостереження, $speed швидкості/контролю. " +
                "Поклав шар «камери». Це лише ті, що є на мапі — насправді їх може бути більше.",
            structured,
            elapsed(),
        )
    }

    companion object {
        const val CAMERA_CCTV = "#EF4444"  // red — surveillance
        const val CAMERA_SPEED = "#F59E0B" // amber — speed/enforcement
    }
}

/** `world.aircraft` — live aircraft overhead from the free ADS-B feed. */
class WorldAircraftTool(
    private val http: GeoHttp,
    private val sink: SpatialLayerSink,
    private val locationProvider: LocationContextProvider?,
    private val currentThreadId: () -> String?,
    private val endpoint: String = ADSB_ENDPOINT,
) : Tool {

    override val id = "world.aircraft"
    override val description =
        "Літаки й інші літаючі обʼєкти поруч у реальному часі з відкритого ADS-B (adsb.lol, без ключа): позивний, висота, швидкість, курс. Кладе живий шар «літаки поруч» на мапу (сам зникає за кілька хвилин). radius_km за замовчуванням 50. Показує лише те, що реально передає ADS-B."
    override val argsSchema: JsonObject = buildJsonObject {
        put("lat", JsonPrimitive("number?"))
        put("lon", JsonPrimitive("number?"))
        put("radius_km", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> =
        setOf(ToolPermission.NETWORK, ToolPermission.LOCATION)
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = worldManifest("tool.world.aircraft", "Літаки поруч")

    override suspend fun execute(
        args: JsonObject,
        context: ToolContext,
        emit: suspend (ToolEvent) -> Unit,
    ): ToolResult {
        val start = System.currentTimeMillis()
        val elapsed: () -> Long = { System.currentTimeMillis() - start }
        val (lat, lon) = resolveWorldCoords(args, locationProvider)
            ?: return ToolResult.Err(
                "no_location",
                "немає координат: локація вимкнена і lat/lon не передані. " +
                    "Наступний крок — знайди місце через geo.search і передай його lat/lon сюди.",
                elapsed(),
            )
        val radiusKm = (args["radius_km"]?.jsonPrimitive?.doubleOrNull ?: 50.0).coerceIn(5.0, 250.0)
        val nm = (radiusKm / 1.852).toInt().coerceIn(1, 250)

        val url = "$endpoint/v2/lat/${c(lat)}/lon/${c(lon)}/dist/$nm"
        val body = http.getText(url)
            ?: return ToolResult.Err(
                "adsb_unavailable",
                "Дані про літаки зараз не прийшли. Скажи це прямо і повтори за хвилину; " +
                    "бортів з голови не називай.",
                elapsed(),
            )

        val parsed = runCatching {
            val arr = worldJson.parseToJsonElement(body).jsonObject["ac"]?.jsonArray
                ?: worldJson.parseToJsonElement(body).jsonObject["aircraft"]?.jsonArray
            arr?.mapNotNull { el ->
                val o = el.jsonObject
                val pLat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val pLon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
                val flight = o["flight"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
                    ?: o["r"]?.jsonPrimitive?.content?.trim()
                    ?: o["hex"]?.jsonPrimitive?.content ?: "борт"
                val track = o["track"]?.jsonPrimitive?.doubleOrNull ?: o["true_heading"]?.jsonPrimitive?.doubleOrNull
                val altBaro = o["alt_baro"]?.jsonPrimitive?.let { it.doubleOrNull ?: if (it.content == "ground") 0.0 else null }
                val gs = o["gs"]?.jsonPrimitive?.doubleOrNull
                val type = o["t"]?.jsonPrimitive?.content
                WorldPoint(
                    lat = pLat, lon = pLon,
                    label = flight,
                    glyph = "літак",
                    bearing = track,
                    // A leading « · » used to appear whenever only ground speed came back.
                    detail = listOfNotNull(
                        type,
                        altBaro?.let { "${it.toInt()} ft" },
                        gs?.let { "${it.toInt()} kt" },
                    ).joinToString(" · ").ifBlank { "ADS-B" },
                    color = AIRCRAFT_COLOR,
                )
            }.orEmpty()
        }
        val points = parsed.getOrElse {
            return ToolResult.Err(
                "adsb_unreadable",
                "Дані про літаки не прочитались — це НЕ означає, що в повітрі нікого. Повтори виклик.",
                elapsed(),
            )
        }

        if (points.isEmpty()) {
            return ToolResult.Ok(
                "У радіусі ${radiusKm.toInt()} км зараз не видно жодного літака. Видно лише тих, " +
                    "хто сам себе передає, — військові й безпілотники сюди не потрапляють.",
                buildJsonObject {
                    put("results", buildJsonArray {})
                    put("radius_km", radiusKm.toInt())
                    put("confidence", "низька: без ADS-B-передавача борт не видно")
                    putProvenance("adsb.lol", start, "лише борти з увімкненим ADS-B")
                },
                elapsed(),
            )
        }

        val layerId = sink.pushWorldLayer(
            title = "літаки поруч", kind = "aircraft", points = points,
            color = AIRCRAFT_COLOR, glyph = "літаки",
            ownerThreadId = currentThreadId(), ttlMs = 4 * 60_000L,
        )
        val structured = buildJsonObject {
            layerId?.let { put("layer_id", it) }
            put("results", worldResults(points))
            put("count", points.size)
            put("radius_km", radiusKm.toInt())
            put("confidence", "неповна: без ADS-B-передавача борт не видно")
            putProvenance("adsb.lol", start, "позиції застарівають за секунди")
            put("map", worldMapObject(lat, lon, 9.0, points))
        }
        return ToolResult.Ok(
            "У повітрі в радіусі ${radiusKm.toInt()} км — ${points.size} бортів: " +
                points.take(5).joinToString { it.label } +
                ". Поклав живий шар «літаки поруч» (оновлюй викликом ще раз). Позиції — на момент запиту, " +
                "за хвилину вони вже інші; військових і безпілотників тут не видно.",
            structured,
            elapsed(),
        )
    }

    companion object {
        const val ADSB_ENDPOINT = "https://api.adsb.lol"
        const val AIRCRAFT_COLOR = "#0EA5E9" // sky
    }
}
