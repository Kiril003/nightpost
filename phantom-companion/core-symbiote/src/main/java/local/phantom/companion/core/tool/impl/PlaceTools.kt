package local.phantom.companion.core.tool.impl

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.places.SavedPlaceEntity
import local.phantom.companion.core.data.places.SavedPlaceStore
import local.phantom.companion.core.tool.Tool
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolEvent
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import local.phantom.companion.core.tool.ToolRisk

/**
 * The operator's personal saved places as verbs: `place.save`, `place.list`,
 * `place.remove`. A same-label save updates in place. This is the operator's own
 * gazetteer — separate from the OSM `poi_cache` the map draws from.
 *
 * Ids are UUIDs the operator never sees, so no answer here leads with one:
 * the words are the label, the coordinates and what actually changed.
 */

private fun SavedPlaceEntity.toJson() = buildJsonObject {
    put("id", id)
    put("label", label)
    put("category", category)
    put("lat", lat)
    put("lon", lon)
    address?.let { put("address", it) }
    note?.let { put("note", it) }
}

/** «дім» замість «home» — категорію оператор чув словом, а не ключем. */
private fun categoryWord(category: String): String = when (category.lowercase()) {
    "home" -> "дім"
    "work" -> "робота"
    "favourite", "favorite" -> "улюблене"
    "shelter" -> "укриття"
    "custom" -> "місце"
    else -> category
}

private fun coordsOutOfRange(lat: Double, lon: Double): Boolean =
    lat !in -90.0..90.0 || lon !in -180.0..180.0

/** `place.save` — save (or update) a named place. */
class PlaceSaveTool(private val store: SavedPlaceStore) : Tool {
    override val id = "place.save"
    override val description =
        "Зберегти місце оператора. label — назва (Дім/Робота/…); lat, lon — координати; " +
            "category — home/work/favourite/shelter/custom; address — адреса (опц.); note — нотатка (опц.). " +
            "Збереження з тією ж назвою ОНОВЛЮЄ наявне — інструмент скаже, створив він чи переписав. " +
            "Координати звіряй з тим, що просив оператор: перезаписаний «Дім» не відкотиш."
    override val argsSchema: JsonObject = buildJsonObject {
        put("label", JsonPrimitive("string"))
        put("lat", JsonPrimitive("number"))
        put("lon", JsonPrimitive("number"))
        put("category", JsonPrimitive("string?"))
        put("address", JsonPrimitive("string?"))
        put("note", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.place.save", "Зберегти місце")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val label = args["label"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
            ?: return ToolResult.Err(
                "bad_args",
                "Місце без назви оператор потім не знайде. Передай label — як він сам його називає («Дім», «Схрон північ»).",
                dur(),
            )
        val lat = args["lat"]?.jsonPrimitive?.doubleOrNull
        val lon = args["lon"]?.jsonPrimitive?.doubleOrNull
        if (lat == null || lon == null) {
            return ToolResult.Err(
                "bad_args",
                "Не знаю, де це. Передай lat і lon у десяткових градусах — візьми їх із map.search або з мітки на мапі.",
                dur(),
            )
        }
        if (coordsOutOfRange(lat, lon)) {
            return ToolResult.Err(
                "bad_coords",
                "Координати ($lat, $lon) поза мапою: широта −90…90, довгота −180…180. " +
                    "Схоже, широту й довготу переставлено місцями — передай ще раз, широта перша.",
                dur(),
            )
        }
        val existing = runCatching { store.byLabel(label) }.getOrNull()
        val id = runCatching {
            store.save(
                label = label,
                lat = lat,
                lon = lon,
                category = args["category"]?.jsonPrimitive?.content,
                address = args["address"]?.jsonPrimitive?.content,
                note = args["note"]?.jsonPrimitive?.content,
            )
        }.getOrElse { e ->
            return ToolResult.Err(
                "store_error",
                "Не вдалось зберегти місце «$label» (${e.message ?: "сховище відмовило"}). " +
                    "Спробуй ще раз; якщо повториться — збережи його вручну на екрані мапи.",
                dur(),
            )
        }
        val saved = runCatching { store.byLabel(label) }.getOrNull()
        return ToolResult.Ok(
            output = buildString {
                append(if (existing != null) "Переписав місце «$label»" else "Зберіг місце «$label»")
                append(" — ${"%.5f".format(lat)}, ${"%.5f".format(lon)}")
                saved?.category?.let { append(", категорія: ${categoryWord(it)}") }
                if (existing != null) append(". Попередні координати цієї назви замінено.")
                else append(".")
            },
            structured = buildJsonObject {
                put("id", id)
                put("label", label)
                put("lat", lat)
                put("lon", lon)
                saved?.category?.let { put("category", it) }
                put("action", if (existing != null) "оновлено" else "створено")
                if (existing != null) {
                    put("replaced_lat", existing.lat)
                    put("replaced_lon", existing.lon)
                }
            },
            durationMs = dur(),
        )
    }
}

/** `place.list` — saved places, optionally filtered by category. */
class PlaceListTool(private val store: SavedPlaceStore) : Tool {
    override val id = "place.list"
    override val description =
        "Показати збережені місця оператора. category — фільтр (home/work/favourite/shelter/custom); " +
            "limit — скільки (типово 30). Якщо під фільтр не підпало нічого, інструмент скаже, які категорії є."
    override val argsSchema: JsonObject = buildJsonObject {
        put("category", JsonPrimitive("string?"))
        put("limit", JsonPrimitive("number?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.READ
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.place.list", "Список місць")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        fun dur() = System.currentTimeMillis() - start
        val category = args["category"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val limit = (args["limit"]?.jsonPrimitive?.intOrNull ?: 30).coerceIn(1, 200)
        val matched = runCatching { store.list(category) }
            .getOrElse { e ->
                return ToolResult.Err(
                    "store_error",
                    "Не вдалось прочитати збережені місця (${e.message ?: "сховище відмовило"}). " +
                        "Спробуй ще раз або відкрий список місць на екрані мапи.",
                    dur(),
                )
            }
        val places = matched.take(limit)
        if (places.isEmpty()) {
            val all = runCatching { store.list(null) }.getOrDefault(emptyList())
            val cats = all.map { categoryWord(it.category) }.distinct()
            return ToolResult.Ok(
                output = when {
                    all.isEmpty() -> "Збережених місць нема жодного. Зберегти можна через place.save — потрібні назва й координати."
                    category != null -> "У категорії «${categoryWord(category)}» нічого нема. " +
                        "Є ${all.size} збережених місць у категоріях: ${cats.joinToString(", ")}."
                    else -> "Збережених місць нема."
                },
                structured = buildJsonObject {
                    category?.let { put("category", it) }
                    put("places", buildJsonArray { })
                    put("total", all.size)
                    put("shown", 0)
                },
                durationMs = dur(),
            )
        }
        return ToolResult.Ok(
            output = places.joinToString("\n") { "• ${it.label} (${categoryWord(it.category)})" } +
                if (matched.size > places.size) "\nПоказав ${places.size} з ${matched.size}." else "",
            structured = buildJsonObject {
                category?.let { put("category", it) }
                put("places", buildJsonArray { places.forEach { add(it.toJson()) } })
                put("shown", places.size)
                put("total", matched.size)
                if (matched.size > places.size) put("truncated", true)
            },
            durationMs = dur(),
        )
    }
}

/** `place.remove` — forget a saved place by label or id. */
class PlaceRemoveTool(private val store: SavedPlaceStore) : Tool {
    override val id = "place.remove"
    override val description =
        "Прибрати збережене місце. label — назва місця («Дім»), НАДІЙНІШИЙ спосіб: id оператор не бачить. " +
            "id — точний ідентифікатор з place.list/place.save, якщо він у тебе є. " +
            "Якщо під label підходить кілька — інструмент поверне їх список, перепитай оператора."
    override val argsSchema: JsonObject = buildJsonObject {
        put("label", JsonPrimitive("string?"))
        put("id", JsonPrimitive("string?"))
    }
    override val permissions: Set<ToolPermission> = emptySet()
    override val risk: ToolRisk = ToolRisk.OUTWARD
    override val askEveryCall: Boolean = true
    override val streaming: Boolean = false
    override val manifest = dataSystemManifest("tool.place.remove", "Прибрати місце")

    override suspend fun execute(args: JsonObject, context: ToolContext, emit: suspend (ToolEvent) -> Unit): ToolResult {
        val start = System.currentTimeMillis()
        val id = args["id"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        val label = args["label"]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
        fun dur() = System.currentTimeMillis() - start
        if (id == null && label == null) {
            return ToolResult.Err(
                "bad_args",
                "Не сказав, яке місце прибрати. Передай label — назву, як її чув оператор; " +
                    "спершу можеш подивитись place.list.",
                dur(),
            )
        }
        return runCatching {
            if (id != null) {
                val row = store.list(null).firstOrNull { it.id == id }
                if (row != null && store.remove(id)) {
                    return@runCatching ToolResult.Ok(
                        output = "Прибрав місце «${row.label}» (${"%.5f".format(row.lat)}, ${"%.5f".format(row.lon)}).",
                        structured = buildJsonObject {
                            put("removed", 1)
                            put("id", id)
                            put("label", row.label)
                            put("lat", row.lat)
                            put("lon", row.lon)
                        },
                        durationMs = dur(),
                    )
                }
            }
            // A model with no id often puts the words in `id` — treat them as words.
            val needle = label ?: id!!
            val matches = matchByText(store.list(null), needle) { it.label }
            when (matches.size) {
                0 -> ToolResult.Err(
                    "not_found",
                    "Місця «$needle» серед збережених немає. Подивись place.list і назви точну назву.",
                    dur(),
                )
                1 -> {
                    val row = matches[0]
                    store.remove(row.id)
                    ToolResult.Ok(
                        output = "Прибрав місце «${row.label}» (${"%.5f".format(row.lat)}, ${"%.5f".format(row.lon)}).",
                        structured = buildJsonObject {
                            put("removed", 1)
                            put("id", row.id)
                            put("label", row.label)
                            put("lat", row.lat)
                            put("lon", row.lon)
                        },
                        durationMs = dur(),
                    )
                }
                else -> ToolResult.Err(
                    "ambiguous",
                    "Підходить кілька: " + matches.joinToString("; ") { "«${it.label}»" } +
                        ". Спитай оператора, яке саме прибрати — вгадувати не буду.",
                    dur(),
                )
            }
        }.getOrElse { e ->
            ToolResult.Err(
                "store_error",
                "Не вдалось прибрати місце (${e.message ?: "сховище відмовило"}). " +
                    "Спробуй ще раз або зроби це вручну в списку місць.",
                dur(),
            )
        }
    }
}
