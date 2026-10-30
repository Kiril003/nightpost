package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.data.BodyLadder

/**
 * Профіль › PHANTOM на телефоні: `/messenger/devices/feed` і `/messenger/devices/stop`
 * тим самим контрактом, що й на ПК (`routes_messenger_devices.py`).
 *
 * Стрічку «Що я роблю» веде ПК (`symbiote/feed.py`, незмінний ланцюжок) — сюди
 * вона приходить як є, разом із перевіркою ланцюжка; телефон її не переписує.
 * ПК не на звʼязку — так і кажемо (`offline`), а не малюємо порожню стрічку.
 *
 * «Стоп усе» — вимикач ЦЬОГО телефона ([BodyLadder]): діє одразу, навіть без
 * ПК; компʼютеру лише кажемо «зупинись», і від того, чи він почув, зупинка тут
 * не залежить. Знову дозволити — лише людина з цього екрана; ПК знімає свій
 * вимикач сам, людина за ним.
 */
class WebPhantomProfile(
    private val ladder: BodyLadder,
    /** (статус, тіло); статус 0 — до ПК не докричались. */
    private val get: suspend (String) -> Pair<Int, String>,
    private val post: suspend (String, JsonObject) -> Pair<Int, String>,
) {

    fun claims(seg: List<String>): Boolean =
        seg.size == 3 && seg[1] == SEGMENT && seg[2] in setOf(FEED, STOP)

    suspend fun handle(method: String, seg: List<String>, body: String?): JsonObject? = when {
        seg[2] == FEED && method == "GET" -> feed()
        seg[2] == STOP && method == "POST" -> stop(body?.let { obj(it) })
        else -> null
    }

    private suspend fun feed(): JsonObject {
        val (status, text) = get("/api/v1/symbiote/feed?limit=100")
        val pc = if (status == 200) obj(text) else null
        return buildJsonObject {
            put("stopped", ladder.stopped.value)
            put("offline", pc == null)
            put(
                "chain",
                (pc?.get("chain") as? JsonObject) ?: buildJsonObject {
                    put("ok", true)
                    put("count", 0)
                    put("broken_at", kotlinx.serialization.json.JsonNull)
                    put("why", "")
                },
            )
            put("entries", (pc?.get("entries") as? JsonArray) ?: buildJsonArray { })
            // Доручення веде ПК («Кузня»): телефон їх лише показує, як є.
            (pc?.get("missions") as? JsonArray)?.let { put("missions", it) }
        }
    }

    private suspend fun stop(body: JsonObject?): JsonObject {
        val on = (body?.get("on") as? JsonPrimitive)?.booleanOrNull ?: true
        if (on) {
            ladder.stopAll()
            post("/api/v1/symbiote/stop", buildJsonObject { put("on", true) })
        } else {
            ladder.resume()
        }
        return buildJsonObject { put("stopped", ladder.stopped.value) }
    }

    private fun obj(s: String): JsonObject? =
        runCatching { Json.parseToJsonElement(s) as? JsonObject }.getOrNull()

    companion object {
        const val SEGMENT = "devices"
        const val FEED = "feed"
        const val STOP = "stop"
    }
}
