package local.phantom.companion.core.net.map

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Locale
import java.util.TimeZone

/**
 * One normalized event, regardless of whether the source feed was a JSON
 * array, a name-keyed JSON object (ubilling's `states`), or a GeoJSON
 * feature list. Fields degrade to their documented default instead of
 * throwing when a feed doesn't carry them — Law 3 (Honesty): an absent
 * field is an absent value, never an invented one.
 */
data class NormalizedSpatialEvent(
    val id: String,
    val coordinates: List<Double>? = null,
    val isActive: Boolean = false,
    val timestamp: Long = 0L,
    val primaryType: String = "unknown",
    val contextualPayload: Map<String, String> = emptyMap(),
)

/**
 * Declarative ingestion instructions for [UniversalContentAdapter]. Point
 * the same engine at a different keyless feed by writing a new schema —
 * never by writing a new parser.
 *
 * @param rootPath dot-notation path to the collection of items ("data.items",
 *   "elements"), or blank if the response body itself is the collection.
 *   Resolves to either a JSON array (each element is one item) or a JSON
 *   object (each entry is one item, keyed by its own id — e.g. ubilling's
 *   `states` map keyed by oblast name).
 * @param fieldMapping core field name → ordered fallback list of JSON
 *   property names to look for on each item, e.g.
 *   `"text" to listOf("notes", "message", "description", "details")`.
 *   Recognized core fields: "id", "isActive", "timestamp", "text", "lat", "lon".
 * @param classificationRules standardized category name → case-insensitive
 *   keyword triggers, checked against the resolved "text" field in
 *   iteration order; the first category with a matching keyword wins.
 * @param timestampFormat an explicit `SimpleDateFormat` pattern for this
 *   feed's timestamp field (e.g. ubilling's naive local "yyyy-MM-dd
 *   HH:mm:ss"). Not part of the original directive's field list, but
 *   without it a feed using a naive local stamp can't be parsed correctly —
 *   left null, the engine still tries epoch-seconds/millis and ISO-8601.
 * @param timeZoneId time zone [timestampFormat] is interpreted in.
 */
data class AdapterSchema(
    val sourceUrl: String,
    val rootPath: String,
    val fieldMapping: Map<String, List<String>> = emptyMap(),
    val classificationRules: Map<String, List<String>> = emptyMap(),
    val timestampFormat: String? = null,
    val timeZoneId: String = "UTC",
    val userAgent: String = "PhantomCompanion/0.1 (universal content adapter)",
)

/**
 * Defensive dynamic key-path lookup. Tries each path in [paths] in order,
 * splitting each on "." for nested traversal (e.g. "data.items"), and
 * returns the first one that resolves to a value. Never throws: a missing
 * key, a path that runs through a non-object, or an empty [paths] list all
 * just fall through to null instead of crashing the caller's lifecycle.
 */
fun JsonElement.findFieldByPaths(paths: List<String>): JsonElement? {
    for (path in paths) {
        var current: JsonElement? = this
        for (segment in path.split(".")) {
            if (segment.isEmpty()) continue
            current = (current as? JsonObject)?.get(segment)
            if (current == null) break
        }
        if (current != null) return current
    }
    return null
}

private fun JsonElement?.textOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull
private fun JsonElement?.boolOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
private fun JsonElement?.numberOrNull(): Double? = (this as? JsonPrimitive)?.doubleOrNull

/**
 * Schema-driven, context-agnostic feed normalizer. RSS/XML feeds are out of
 * scope — a fundamentally different wire format needing an XML parser, not
 * [JsonElement] traversal — this engine speaks the JSON/GeoJSON family only.
 */
class UniversalContentAdapter internal constructor(
    engine: HttpClientEngine?,
    private val timeoutMs: Long,
) {
    // Public constructor deliberately keeps ktor types (HttpClientEngine)
    // off the signature — same convention as OsrmClient — so callers in
    // other modules (feature-stream) don't need ktor on their compile
    // classpath. Tests inside :core-net can still reach the `internal`
    // constructor above to inject a mock engine.
    constructor(timeoutMs: Long = 12_000L) : this(null, timeoutMs)

    private val json = Json { ignoreUnknownKeys = true }

    private val client: HttpClient = if (engine == null) {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    } else {
        HttpClient(engine) {
            install(ContentNegotiation) { json(json) }
            install(HttpTimeout) {
                requestTimeoutMillis = timeoutMs
                connectTimeoutMillis = timeoutMs
            }
        }
    }

    /**
     * Fetches [schema].sourceUrl and normalizes it on [Dispatchers.IO].
     * Emits an empty list — never throws — if the request fails, the body
     * isn't JSON, or [AdapterSchema.rootPath] doesn't resolve, so a feed
     * hiccup degrades to "nothing new" instead of crashing the collector.
     */
    fun fetchAndNormalize(schema: AdapterSchema): Flow<List<NormalizedSpatialEvent>> = flow {
        val events = runCatching {
            val body = client.get(schema.sourceUrl) {
                timeout { requestTimeoutMillis = timeoutMs }
                header(HttpHeaders.UserAgent, schema.userAgent)
            }.bodyAsText()
            val root = json.parseToJsonElement(body)
            val itemsRoot = if (schema.rootPath.isBlank()) root else root.findFieldByPaths(listOf(schema.rootPath))
            resolveItems(itemsRoot).mapNotNull { (structuralKey, item) -> buildEvent(structuralKey, item, schema) }
        }.getOrDefault(emptyList())
        emit(events)
    }.flowOn(Dispatchers.IO)

    fun close() = client.close()

    /** An array yields (null, element) per item; an object yields (key, value) per entry. */
    private fun resolveItems(root: JsonElement?): List<Pair<String?, JsonElement>> = when (root) {
        is JsonArray -> root.map { null to it }
        is JsonObject -> root.entries.map { (key, value) -> key to value }
        else -> emptyList()
    }

    private fun buildEvent(structuralKey: String?, item: JsonElement, schema: AdapterSchema): NormalizedSpatialEvent? {
        val obj = item as? JsonObject

        val id = schema.fieldMapping["id"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).textOrNull() }
            ?: structuralKey
            ?: return null // Law 3 — nothing identifies this item; don't invent an id.

        val isActive = schema.fieldMapping["isActive"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).boolOrNull() }
            ?: false

        val rawTimestamp = schema.fieldMapping["timestamp"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).textOrNull() }
        val timestamp = rawTimestamp?.let { parseTimestamp(it, schema) } ?: 0L

        val text = schema.fieldMapping["text"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).textOrNull()?.takeIf(String::isNotBlank) }

        val lat = schema.fieldMapping["lat"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).numberOrNull() }
        val lon = schema.fieldMapping["lon"].orEmpty()
            .firstNotNullOfOrNull { key -> obj?.findFieldByPaths(listOf(key)).numberOrNull() }
        val coordinates = if (lat != null && lon != null) listOf(lat, lon) else null

        return NormalizedSpatialEvent(
            id = id,
            coordinates = coordinates,
            isActive = isActive,
            timestamp = timestamp,
            primaryType = classify(text, schema.classificationRules),
            contextualPayload = buildMap { text?.let { put("text", it) } },
        )
    }

    private fun classify(text: String?, rules: Map<String, List<String>>): String {
        if (text.isNullOrBlank() || rules.isEmpty()) return "unknown"
        val lower = text.lowercase(Locale.ROOT)
        return rules.entries.firstOrNull { (_, keywords) ->
            keywords.any { keyword -> lower.contains(keyword.lowercase(Locale.ROOT)) }
        }?.key ?: "unknown"
    }

    /** Schema format first (exact per-feed semantics), then epoch, then ISO-8601. Never throws. */
    private fun parseTimestamp(raw: String, schema: AdapterSchema): Long? {
        schema.timestampFormat?.let { pattern ->
            runCatching {
                SimpleDateFormat(pattern, Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone(schema.timeZoneId) }
                    .parse(raw)?.time
            }.getOrNull()?.let { return it }
        }
        raw.toLongOrNull()?.let { epoch -> return if (epoch < 10_000_000_000L) epoch * 1000 else epoch }
        runCatching { Instant.parse(raw).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return null
    }
}
