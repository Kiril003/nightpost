package local.phantom.companion.core.net.peer

import kotlin.math.roundToLong
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull

/**
 * Операції мапи в конверті листа собі [PeerSelf]. Кодек вимагає в `d` лише цілі,
 * тож координати їдуть як `lat_e7`/`lon_e7`; мить правки — `at` операції,
 * пристрій — `o` у `d` (без нього дві правки однієї миті розійшлися б).
 */
object SelfMapWire {

    private const val E7 = 1e7

    val kinds: List<PeerSelf.Kind> = listOf(
        kind(SelfMap.PLACE) { (it as SelfMap.Place).id },
        kind(SelfMap.ROUTE) { (it as SelfMap.Route).id },
        kind(SelfMap.MAPSELF) { (it as SelfMap.Setting).key },
        kind(SelfMap.WORLD) { (it as SelfMap.Cell).key },
    )

    fun register() = kinds.forEach(PeerSelf::register)

    fun toOperation(op: SelfMap.Op): PeerSelf.Operation {
        val flat = SelfMap.encode(op)
        val at = when (op) {
            is SelfMap.Place -> op.stamp.atMs
            is SelfMap.Route -> op.stamp.atMs
            is SelfMap.Setting -> op.stamp.atMs
            is SelfMap.Cell -> op.lastMs
        }
        val d = buildJsonObject {
            flat.forEach { (k, v) ->
                when (k) {
                    "op", "at" -> Unit
                    "origin" -> put("o", v)
                    "lat" -> put("lat_e7", e7(v))
                    "lon" -> put("lon_e7", e7(v))
                    "pts" -> put("pts_e7", buildJsonArray { v.jsonArray.forEach { p -> add(buildJsonArray { p.jsonArray.forEach { add(e7(it)) } }) } })
                    else -> put(k, v)
                }
            }
        }
        return PeerSelf.Operation((flat["op"] as JsonPrimitive).content, at, d)
    }

    /** Null — не операція мапи або зіпсована; межу тримає [SelfMap.decode]. */
    fun fromOperation(op: PeerSelf.Operation): SelfMap.Op? {
        val flat = buildJsonObject {
            put("op", JsonPrimitive(op.op))
            if (op.op != SelfMap.WORLD) put("at", JsonPrimitive(op.atMs))
            op.data.forEach { (k, v) ->
                when (k) {
                    "o" -> put("origin", v)
                    "lat_e7" -> put("lat", de7(v) ?: return null)
                    "lon_e7" -> put("lon", de7(v) ?: return null)
                    "pts_e7" -> put("pts", JsonArray((v as? JsonArray ?: return null).map { p -> JsonArray((p as? JsonArray ?: return null).map { de7(it) ?: return null }) }))
                    else -> put(k, v)
                }
            }
        }
        return SelfMap.decode(flat)
    }

    private fun kind(wire: String, key: (SelfMap.Op) -> String) = object : PeerSelf.Kind {
        override val wire = wire
        override fun key(op: PeerSelf.Operation): String = key(fromOperation(op) ?: error("не операція мапи"))

        // Закон книги мапи: пізніша мить, далі пристрій; клітинка «мого світу» лише росте.
        override fun merge(a: PeerSelf.Operation, b: PeerSelf.Operation): PeerSelf.Operation {
            val x = fromOperation(a) ?: return b
            val y = fromOperation(b) ?: return a
            return toOperation(SelfMap.ops(SelfMap.applyAll(SelfMap.Book(), listOf(x, y))).single())
        }
    }

    private fun e7(v: JsonElement) = JsonPrimitive(((v as JsonPrimitive).content.toDouble() * E7).roundToLong())
    private fun de7(v: JsonElement): JsonPrimitive? = (v as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.let { JsonPrimitive(it / E7) }
}
