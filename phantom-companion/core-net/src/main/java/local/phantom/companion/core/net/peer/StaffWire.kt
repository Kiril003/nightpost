package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Пропуск працівника бізнесу: `PH-STF:` + JSON. Несе лише те, що дозволяє D7 —
 * імʼя клієнта й рядки доручених розмов; порожній перелік означає «відкликано».
 */
object StaffWire {
    const val TAG = "PH-STF:"
    const val THREADS_MAX = 20
    const val LINES_MAX = 12
    const val TEXT_MAX = 280
    const val NAME_MAX = 60
    const val FRAME_MAX = 16_000

    data class Line(val mine: Boolean, val text: String, val atMs: Long)
    data class Thread(val id: String, val client: String, val lines: List<Line>)
    data class Pass(val business: String, val rev: Long, val threads: List<Thread>)

    private val json = Json { ignoreUnknownKeys = true }

    fun isStaff(body: String): Boolean = body.startsWith(TAG)

    fun encode(pass: Pass): String? {
        val business = pass.business.trim().take(NAME_MAX)
        if (business.isEmpty() || pass.rev <= 0) return null
        var threads = pass.threads
            .filter { it.id.isNotBlank() && it.id.length <= 80 }
            .distinctBy { it.id }
            .take(THREADS_MAX)
            .map { t ->
                Thread(
                    t.id,
                    t.client.trim().take(NAME_MAX),
                    t.lines.filter { it.text.isNotBlank() }.takeLast(LINES_MAX).map { Line(it.mine, it.text.take(TEXT_MAX), it.atMs) },
                )
            }
        var body = frame(business, pass.rev, threads)
        while (body.length > FRAME_MAX) {
            val fat = threads.maxByOrNull { it.lines.size } ?: return null
            threads = if (fat.lines.isEmpty()) threads.dropLast(1) else threads.map { if (it === fat) it.copy(lines = it.lines.drop(1)) else it }
            body = frame(business, pass.rev, threads)
        }
        return body
    }

    fun decode(body: String): Pass? {
        if (!isStaff(body) || body.length > FRAME_MAX) return null
        val o = runCatching { json.parseToJsonElement(body.substring(TAG.length)) as? JsonObject }.getOrNull() ?: return null
        val business = o.str("b")?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() } ?: return null
        val rev = o.long("rev")?.takeIf { it > 0 } ?: return null
        val threads = (o["t"] as? JsonArray).orEmpty().mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val id = t.str("id")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: return@mapNotNull null
            val lines = (t["l"] as? JsonArray).orEmpty().mapNotNull { l ->
                val x = l as? JsonObject ?: return@mapNotNull null
                val text = x.str("x")?.take(TEXT_MAX)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Line(runCatching { x["m"]?.jsonPrimitive?.booleanOrNull }.getOrNull() == true, text, x.long("at") ?: 0L)
            }.takeLast(LINES_MAX)
            Thread(id, t.str("c")?.trim()?.take(NAME_MAX).orEmpty(), lines)
        }.distinctBy { it.id }.take(THREADS_MAX)
        return Pass(business, rev, threads)
    }

    private fun frame(business: String, rev: Long, threads: List<Thread>): String = TAG + buildJsonObject {
        put("b", business)
        put("rev", rev)
        put("t", buildJsonArray {
            threads.forEach { t ->
                add(buildJsonObject {
                    put("id", t.id)
                    put("c", t.client)
                    put("l", buildJsonArray {
                        t.lines.forEach { l -> add(buildJsonObject { put("m", l.mine); put("x", l.text); put("at", l.atMs) }) }
                    })
                })
            }
        })
    }.toString()

    private fun JsonObject.str(k: String): String? = runCatching { this[k]?.jsonPrimitive?.contentOrNull }.getOrNull()
    private fun JsonObject.long(k: String): Long? = runCatching { this[k]?.jsonPrimitive?.longOrNull }.getOrNull()
}
