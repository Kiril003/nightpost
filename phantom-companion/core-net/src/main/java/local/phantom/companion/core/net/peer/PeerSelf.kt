package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

/**
 * Лист собі: пакет операцій синхрону між моїми пристроями (docs/ONE_SELF.md §4).
 *
 *   PH-SLF:{"v":1,"origin":"<16 hex>","seq":N,"ops":[{"op":"line","at":ms,"d":{…}},…]}
 *
 * Порядок ключів фіксований, JSON компактний, не-ASCII не екранується: так ПК
 * (`node/peer_self.py`) складає ті самі байти. Числа в `d` — лише цілі
 * (координати — у ціле, напр. `lat_e7`): дробові Kotlin і Python друкують
 * по-різному.
 *
 * Роди операцій кодек не знає сам: кожен модуль реєструє свій [Kind] разом зі
 * злиттям ([register]), не правлячи цього файла. Скласти незареєстрований рід
 * не можна; прочитати — можна, а [fold] його тихо пропускає: стара збірка не
 * падає на операції новішої.
 */
object PeerSelf {

    const val TAG = "PH-SLF:"
    const val VERSION = 1

    /**
     * Рід операції. [key] — сутність, якої вона стосується (id рядка, id людини…);
     * операції з різними ключами не зливаються. [merge] мусить бути
     * комутативним, асоціативним і ідемпотентним: пакети доходять у будь-якому
     * порядку й повторюються.
     */
    interface Kind {
        val wire: String
        fun key(op: Operation): String
        fun merge(a: Operation, b: Operation): Operation
    }

    /** Готовий рід «пізніший запис перемагає»; рівний час — більший за байтами `d`. */
    fun lastWriterWins(wire: String, key: (Operation) -> String): Kind = object : Kind {
        override val wire = wire
        override fun key(op: Operation) = key(op)
        override fun merge(a: Operation, b: Operation): Operation = when {
            a.atMs != b.atMs -> if (a.atMs > b.atMs) a else b
            else -> if (a.data.toString() >= b.data.toString()) a else b
        }
    }

    private val kinds = java.util.concurrent.ConcurrentHashMap<String, Kind>()
    private val WIRE = Regex("^[a-z][a-z0-9_]{0,31}$")

    /** Повторна реєстрація того самого роду заміняє попередню. */
    fun register(kind: Kind) {
        require(WIRE.matches(kind.wire)) { "рід операції: ${kind.wire}" }
        kinds[kind.wire] = kind
    }

    fun kindOf(wire: String): Kind? = kinds[wire]

    data class Operation(val op: String, val atMs: Long, val data: JsonObject = JsonObject(emptyMap())) {
        val known: Boolean get() = kindOf(op) != null
    }

    data class Pack(val origin: String, val seq: Long, val ops: List<Operation>)

    private val json = Json { encodeDefaults = true }
    private val ID = Regex("^[0-9a-f]{16}$")

    /**
     * Злиття операцій будь-якого порядку й кратності: по одній на (рід, ключ).
     * Незареєстровані роди пропускаються мовчки.
     */
    fun fold(ops: Iterable<Operation>): Map<Pair<String, String>, Operation> {
        val out = LinkedHashMap<Pair<String, String>, Operation>()
        for (op in ops) {
            val kind = kindOf(op.op) ?: continue
            val key = runCatching { kind.key(op) }.getOrNull() ?: continue
            val slot = op.op to key
            out[slot] = out[slot]?.let { kind.merge(it, op) } ?: op
        }
        return out
    }

    fun isSelf(body: String): Boolean = body.startsWith(TAG)

    fun encode(pack: Pack): String? {
        if (!ID.matches(pack.origin) || pack.seq <= 0L || pack.ops.isEmpty()) return null
        if (pack.ops.any { !it.known || it.atMs < 0L }) return null
        val root = buildJsonObject {
            put("v", JsonPrimitive(VERSION))
            put("origin", JsonPrimitive(pack.origin))
            put("seq", JsonPrimitive(pack.seq))
            put(
                "ops",
                JsonArray(
                    pack.ops.map { o ->
                        buildJsonObject {
                            put("op", JsonPrimitive(o.op))
                            put("at", JsonPrimitive(o.atMs))
                            put("d", o.data)
                        }
                    },
                ),
            )
        }
        return TAG + json.encodeToString(JsonObject.serializer(), root)
    }

    fun decode(body: String): Pack? {
        if (!isSelf(body)) return null
        return runCatching {
            val root = json.parseToJsonElement(body.removePrefix(TAG)).jsonObject
            if (root["v"]?.jsonPrimitive?.longOrNull != VERSION.toLong()) return null
            val origin = root["origin"]!!.jsonPrimitive.content.takeIf { ID.matches(it) } ?: return null
            val seq = root["seq"]!!.jsonPrimitive.long.takeIf { it > 0L } ?: return null
            val ops = (root["ops"] as JsonArray).map { el ->
                val o = el.jsonObject
                val op = o["op"]!!.jsonPrimitive.also { require(it.isString) }.content
                val at = o["at"]!!.jsonPrimitive.long.also { require(it >= 0L) }
                Operation(op, at, o["d"]?.jsonObject ?: JsonObject(emptyMap()))
            }
            if (ops.isEmpty()) null else Pack(origin, seq, ops)
        }.getOrNull()
    }

    /**
     * Ділить операції на пакети не довші за [maxBytes] (UTF-8, разом із [TAG]),
     * нумеруючи їх від [firstSeq]. Операція, що сама не влазить, — у пакеті одна:
     * її поділ — справа того, хто її склав (null від конверта).
     */
    fun pack(origin: String, firstSeq: Long, ops: List<Operation>, maxBytes: Int): List<Pack> {
        val out = mutableListOf<Pack>()
        var current = mutableListOf<Operation>()
        var seq = firstSeq
        for (op in ops) {
            val tried = current + op
            val size = encode(Pack(origin, seq, tried))?.toByteArray(Charsets.UTF_8)?.size ?: Int.MAX_VALUE
            if (size <= maxBytes || current.isEmpty()) {
                current = tried.toMutableList()
            } else {
                out += Pack(origin, seq++, current)
                current = mutableListOf(op)
            }
        }
        if (current.isNotEmpty()) out += Pack(origin, seq, current)
        return out
    }
}
