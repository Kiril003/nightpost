package local.phantom.companion.assistant

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.MapViewportContextFormatter
import local.phantom.companion.core.ai.context.MapViewportSnapshot
import local.phantom.companion.core.constitution.FeatureManifest
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

class ProductSources(
    val day: (suspend () -> JsonElement)? = null,
    val circles: (() -> List<CircleRow>)? = null,
    val where: (() -> List<WhereRow>)? = null,
    val wallet: (() -> WalletRow?)? = null,
    val viewport: (() -> MapViewportSnapshot?)? = null,
    val english: () -> Boolean = { true },
)

data class CircleRow(val name: String, val circle: String)

data class WhereRow(val name: String, val level: String, val ageMin: Long?)

data class WalletRow(
    val shortAddress: String?,
    val balances: List<Pair<String, String>>,
    val cluster: String,
)

internal object ProductReadTools {
    private const val MAX_BYTES = 2048

    fun all(sources: ProductSources): List<Tool> = listOf(
        DayTodayTool(sources),
        CirclesListTool(sources),
        WherePeopleTool(sources),
        WalletBalanceTool(sources),
        MapViewportTool(sources),
    )

    internal abstract class BaseReadTool(
        final override val id: String,
        final override val description: String,
    ) : Tool {
        final override val argsSchema: JsonObject = JsonObject(emptyMap())
        final override val permissions: Set<ToolPermission> = emptySet()
        final override val risk: ToolRisk = ToolRisk.READ
        final override val streaming: Boolean = false
        final override val manifest: FeatureManifest = FeatureManifest(
            id = "tool.$id",
            tier = 1,
            displayName = id,
        )

        protected fun unavailable(reason: String): ToolResult.Err =
            ToolResult.Err("unavailable", reason, 0L)

        protected fun okJson(obj: JsonObject, durationMs: Long = 0L): ToolResult.Ok =
            ToolResult.Ok(output = obj.toString(), structured = obj, durationMs = durationMs)

        protected fun fitListJson(
            arrayKey: String,
            rows: List<JsonObject>,
            maxCount: Int = rows.size,
            extra: JsonObject = JsonObject(emptyMap()),
            durationMs: Long = 0L,
        ): ToolResult.Ok {
            var takeCount = rows.size.coerceAtMost(maxCount)
            while (takeCount >= 0) {
                val dropped = rows.size - takeCount
                val candidate = buildJsonObject {
                    extra.forEach { (k, v) -> put(k, v) }
                    put(arrayKey, JsonArray(rows.take(takeCount)))
                    if (dropped > 0) put("more", dropped)
                }
                val text = candidate.toString()
                if (text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES || takeCount == 0) {
                    return ToolResult.Ok(output = text, structured = candidate, durationMs = durationMs)
                }
                takeCount = (takeCount - 1).coerceAtMost((takeCount * 3) / 4)
            }
            val fallback = buildJsonObject { put(arrayKey, JsonArray(emptyList())) }
            return ToolResult.Ok(output = fallback.toString(), structured = fallback, durationMs = durationMs)
        }
    }

    internal class DayTodayTool(private val sources: ProductSources) : BaseReadTool(
        id = "day.today",
        description = "Read today's agenda items (reminders, promises, plans, calendar events).",
    ) {
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val provider = sources.day ?: return unavailable("Day agenda is unavailable.")
            val raw = runCatching { provider() }.getOrNull() as? JsonObject
                ?: return unavailable("Day agenda is unavailable.")
            val nowMs = raw["now_ms"] ?: JsonPrimitive(0L)
            val rawItems = (raw["items"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val cleaned = rawItems.map { item ->
                buildJsonObject {
                    item["kind"]?.let { put("kind", it) }
                    (item["title"] as? JsonPrimitive)?.let { put("title", clip(it.content, 120)) }
                    item["at_ms"]?.let { put("at_ms", it) }
                    item["overdue"]?.let { put("overdue", it) }
                }
            }
            return fitListJson(
                arrayKey = "items",
                rows = cleaned,
                maxCount = 20,
                extra = buildJsonObject { put("now_ms", nowMs) },
            )
        }
    }

    internal class CirclesListTool(private val sources: ProductSources) : BaseReadTool(
        id = "circles.list",
        description = "List people and the social circle each belongs to.",
    ) {
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val provider = sources.circles ?: return unavailable("Circles are unavailable.")
            val rows = runCatching { provider() }.getOrNull() ?: return unavailable("Circles are unavailable.")
            val objects = rows.map { row ->
                buildJsonObject {
                    put("name", clip(row.name, 64))
                    put("circle", clip(row.circle, 32))
                }
            }
            return fitListJson(arrayKey = "circles", rows = objects)
        }
    }

    internal class WherePeopleTool(private val sources: ProductSources) : BaseReadTool(
        id = "where.people",
        description = "Read coarse sharing level and freshness for people sharing location.",
    ) {
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val provider = sources.where ?: return unavailable("Location sharing is unavailable.")
            val rows = runCatching { provider() }.getOrNull() ?: return unavailable("Location sharing is unavailable.")
            val objects = rows.map { row ->
                buildJsonObject {
                    put("name", clip(sanitizeNoCoords(row.name), 64))
                    put("level", clip(sanitizeNoCoords(row.level), 24))
                    if (row.ageMin != null) put("age_min", row.ageMin)
                }
            }
            return fitListJson(arrayKey = "people", rows = objects)
        }

        private fun sanitizeNoCoords(text: String): String =
            text.replace(Regex("""-?\d+\.\d+"""), "").trim()
    }

    internal class WalletBalanceTool(private val sources: ProductSources) : BaseReadTool(
        id = "wallet.balance",
        description = "Read own short wallet address and coin balances.",
    ) {
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val provider = sources.wallet ?: return unavailable("Wallet is unavailable.")
            val wallet = runCatching { provider() }.getOrNull() ?: return unavailable("Wallet is unavailable.")
            val shortAddr = wallet.shortAddress?.let(::shortenAddress)
            val rows = wallet.balances.map { (coin, amount) ->
                buildJsonObject {
                    put("coin", clip(coin, 16))
                    put("amount", clip(amount, 32))
                }
            }
            val extra = buildJsonObject {
                if (!shortAddr.isNullOrBlank()) put("address", shortAddr)
                put("cluster", clip(wallet.cluster, 24))
            }
            return fitListJson(arrayKey = "balances", rows = rows, extra = extra)
        }

        private fun shortenAddress(addr: String): String {
            val trimmed = addr.trim()
            if (trimmed.length <= 11) return trimmed
            return trimmed.take(4) + "…" + trimmed.takeLast(4)
        }
    }

    internal class MapViewportTool(private val sources: ProductSources) : BaseReadTool(
        id = "map.viewport",
        description = "Read the active map viewport summary when the map is open.",
    ) {
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult {
            val provider = sources.viewport ?: return unavailable("Map is closed.")
            val snap = runCatching { provider() }.getOrNull() ?: return unavailable("Map is closed.")
            val en = runCatching { sources.english() }.getOrDefault(true)
            val formatted = MapViewportContextFormatter.format(snap, english = en)
                ?: return unavailable("Map is closed.")
            val clipped = clipUtf8(formatted, MAX_BYTES - 32)
            return okJson(buildJsonObject { put("view", clipped) })
        }
    }

    private fun clip(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take(maxChars)

    private fun clipUtf8(text: String, maxBytes: Int): String {
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        var end = text.length.coerceAtMost(maxBytes)
        while (end > 0 && text.substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) {
            end -= 16
        }
        return text.substring(0, end.coerceAtLeast(0))
    }
}
