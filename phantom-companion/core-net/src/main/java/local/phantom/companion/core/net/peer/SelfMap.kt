package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Мапа однієї особи на кількох пристроях: операції `place`, `route`,
 * `mapself`, `world` листа собі (PH-SLF, docs/ONE_SELF.md) і їхнє злиття.
 *
 * Злиття — той самий закон, що й для рядків: обʼєднання за id, пізніша правка
 * перемагає (мить, далі пристрій — щоб два пристрої з однаковою миттю дійшли
 * одного). Стирання — теж правка: новіше стирання ховає старішу правку, новіша
 * правка після стирання — свідомо нове збереження. Клітинки «мого світу» лише
 * ростуть: перша мить — мінімум, остання й перебування — максимум, тож повтор
 * пакета нічого не додає.
 *
 * Двійник — `phantom-os/src/backend/messenger/self_map.py`; обидва судяться
 * одним вектором `self_map_vectors.json`.
 */
object SelfMap {

    const val PLACE = "place"
    const val ROUTE = "route"
    const val MAPSELF = "mapself"
    const val WORLD = "world"

    const val MAX_LABEL = 120
    const val MAX_TEXT = 400
    const val MAX_POINTS = 500

    private val CATEGORIES = setOf("home", "work", "shelter", "favourite", "custom")
    private val SETTING_KEY = Regex("^(see:[0-9a-f]{16}|ghost:(\\*|[0-9a-f]{16})|battery|world_on_devices)$")
    private val CELL_KEY = Regex("^q:\\d{1,6}:\\d{1,6}$")
    private val ORIGIN = Regex("^[0-9a-zA-Z_-]{1,64}$")

    data class Stamp(val atMs: Long, val origin: String) : Comparable<Stamp> {
        override fun compareTo(other: Stamp): Int =
            compareValuesBy(this, other, { it.atMs }, { it.origin })
    }

    sealed interface Op

    data class Place(
        val id: String,
        val stamp: Stamp,
        val gone: Boolean,
        val label: String = "",
        val category: String = "custom",
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val address: String? = null,
        val note: String? = null,
    ) : Op

    data class Route(
        val id: String,
        val stamp: Stamp,
        val gone: Boolean,
        val label: String = "",
        val points: List<Pair<Double, Double>> = emptyList(),
    ) : Op

    /** [value] null — налаштування знято. */
    data class Setting(val key: String, val stamp: Stamp, val value: String?) : Op

    data class Cell(val key: String, val firstMs: Long, val lastMs: Long, val dwellMs: Long) : Op

    data class Book(
        val places: Map<String, Place> = emptyMap(),
        val routes: Map<String, Route> = emptyMap(),
        val settings: Map<String, Setting> = emptyMap(),
        val world: Map<String, Cell> = emptyMap(),
    )

    fun apply(book: Book, op: Op): Book = when (op) {
        is Place -> book.copy(places = lww(book.places, op.id, op) { it.stamp })
        is Route -> book.copy(routes = lww(book.routes, op.id, op) { it.stamp })
        is Setting -> book.copy(settings = lww(book.settings, op.key, op) { it.stamp })
        is Cell -> book.copy(world = book.world + (op.key to grow(book.world[op.key], op)))
    }

    fun applyAll(book: Book, ops: Iterable<Op>): Book = ops.fold(book, ::apply)

    fun merge(a: Book, b: Book): Book = applyAll(a, ops(b))

    fun ops(book: Book): List<Op> =
        book.places.values + book.routes.values + book.settings.values + book.world.values

    /** Чи варто застосувати [op] поверх того, що вже лежить: false — старе або те саме. */
    fun wins(book: Book, op: Op): Boolean = apply(book, op) != book

    private inline fun <T : Op> lww(held: Map<String, T>, key: String, op: T, stamp: (T) -> Stamp): Map<String, T> {
        val old = held[key] ?: return held + (key to op)
        return if (stamp(op) > stamp(old)) held + (key to op) else held
    }

    private fun grow(old: Cell?, op: Cell): Cell = if (old == null) op else Cell(
        op.key,
        minOf(old.firstMs, op.firstMs),
        maxOf(old.lastMs, op.lastMs),
        maxOf(old.dwellMs, op.dwellMs),
    )

    fun encode(op: Op): JsonObject = buildJsonObject {
        when (op) {
            is Place -> {
                put("op", PLACE)
                put("id", op.id)
                stamp(op.stamp)
                put("gone", op.gone)
                if (!op.gone) {
                    put("label", op.label)
                    put("cat", op.category)
                    put("lat", op.lat)
                    put("lon", op.lon)
                    put("address", op.address)
                    put("note", op.note)
                }
            }
            is Route -> {
                put("op", ROUTE)
                put("id", op.id)
                stamp(op.stamp)
                put("gone", op.gone)
                if (!op.gone) {
                    put("label", op.label)
                    put("pts", buildJsonArray { op.points.forEach { (a, b) -> add(buildJsonArray { add(JsonPrimitive(a)); add(JsonPrimitive(b)) }) } })
                }
            }
            is Setting -> {
                put("op", MAPSELF)
                put("key", op.key)
                stamp(op.stamp)
                put("value", op.value)
            }
            is Cell -> {
                put("op", WORLD)
                put("cell", op.key)
                put("first", op.firstMs)
                put("last", op.lastMs)
                put("dwell", op.dwellMs)
            }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.stamp(s: Stamp) {
        put("at", s.atMs)
        put("origin", s.origin)
    }

    /** Null — не операція мапи або зіпсована. Межу системи тримає саме цей розбір. */
    fun decode(o: JsonObject): Op? = runCatching {
        when (o.str("op")) {
            PLACE -> {
                val id = o.id() ?: return null
                val stamp = o.stamp() ?: return null
                if (o.bool("gone") == true) return Place(id, stamp, gone = true)
                val label = o.str("label")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL } ?: return null
                val cat = o.str("cat")?.takeIf { it in CATEGORIES } ?: return null
                val lat = o.num("lat")?.takeIf { it in -90.0..90.0 } ?: return null
                val lon = o.num("lon")?.takeIf { it in -180.0..180.0 } ?: return null
                Place(id, stamp, false, label, cat, lat, lon, o.text("address"), o.text("note"))
            }
            ROUTE -> {
                val id = o.id() ?: return null
                val stamp = o.stamp() ?: return null
                if (o.bool("gone") == true) return Route(id, stamp, gone = true)
                val label = o.str("label")?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LABEL } ?: return null
                val pts = (o["pts"] as? JsonArray)?.takeIf { it.size in 2..MAX_POINTS } ?: return null
                val points = pts.map { p ->
                    val pair = p.jsonArray
                    if (pair.size != 2) return null
                    val lat = (pair[0] as JsonPrimitive).doubleOrNull?.takeIf { it in -90.0..90.0 } ?: return null
                    val lon = (pair[1] as JsonPrimitive).doubleOrNull?.takeIf { it in -180.0..180.0 } ?: return null
                    lat to lon
                }
                Route(id, stamp, false, label, points)
            }
            MAPSELF -> {
                val key = o.str("key")?.takeIf { SETTING_KEY.matches(it) } ?: return null
                val stamp = o.stamp() ?: return null
                val value = when (val v = o["value"]) {
                    null, JsonNull -> null
                    else -> (v as JsonPrimitive).takeIf { it.isString }?.content?.takeIf { it.length <= 64 } ?: return null
                }
                Setting(key, stamp, value)
            }
            WORLD -> {
                val key = o.str("cell")?.takeIf { CELL_KEY.matches(it) } ?: return null
                val first = o.long("first")?.takeIf { it > 0L } ?: return null
                val last = o.long("last")?.takeIf { it >= first } ?: return null
                val dwell = o.long("dwell")?.takeIf { it >= 0L } ?: return null
                Cell(key, first, last, dwell)
            }
            else -> null
        }
    }.getOrNull()

    /** Стан без історії, ключі за абеткою — однаковий у Kotlin і Python. */
    fun snapshot(book: Book): JsonObject = buildJsonObject {
        put("places", JsonArray(book.places.toSortedMap().values.map(::encode)))
        put("routes", JsonArray(book.routes.toSortedMap().values.map(::encode)))
        put("settings", JsonArray(book.settings.toSortedMap().values.map(::encode)))
        put("world", JsonArray(book.world.toSortedMap().values.map(::encode)))
    }

    fun parse(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.num(k: String): Double? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
    private fun JsonObject.text(k: String): String? = str(k)?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TEXT)
    private fun JsonObject.id(): String? = str("id")?.takeIf { it.length in 1..64 && ORIGIN.matches(it) }
    private fun JsonObject.stamp(): Stamp? {
        val at = long("at")?.takeIf { it > 0L } ?: return null
        val origin = str("origin")?.takeIf { ORIGIN.matches(it) } ?: return null
        return Stamp(at, origin)
    }
}
