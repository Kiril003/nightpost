package local.phantom.companion.ui.messenger

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * `/messenger/reminders` — нагадування, поставлене з листа у веб-месенджері,
 * лягає в ту саму таблицю, що й нагадування PHANTOM. Будильник ставить не цей
 * клас: ReminderAlarmSync дзеркалить таблицю в AlarmManager, тож нагадування
 * дзвонить і тоді, коли застосунок закрито.
 */
class WebMessengerReminders(private val store: Store, private val now: () -> Long = System::currentTimeMillis) {

    interface Store {
        suspend fun add(text: String, atMs: Long): String
        suspend fun pending(): List<Row>
        suspend fun remove(id: String): Boolean
    }

    data class Row(val id: String, val text: String, val atMs: Long)

    companion object {
        const val CHANGED_JS = "window.__phantomRemindersChanged && window.__phantomRemindersChanged();"

        /** Один поштовх на справжню зміну таблиці; перше читання веб робить сам. */
        fun changes(pending: Flow<List<Pair<String, Long>>>): Flow<Unit> = pending.distinctUntilChanged().drop(1).map { }
    }

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == "reminders"

    suspend fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        run {
            when {
                seg.size == 2 && seg[1] == "reminders" && method == "GET" -> ok(list())
                seg.size == 2 && seg[1] == "reminders" && method == "POST" -> add(body)
                seg.size == 3 && seg[1] == "reminders" && method == "DELETE" ->
                    if (store.remove(seg[2])) ok(buildJsonObject { put("removed", true) }) else fail(404, "gone")
                seg.size == 4 && seg[1] == "reminders" && seg[3] == "snooze" && method == "POST" ->
                    snooze(seg[2], body)
                else -> fail(404, "not_found")
            }
        }
    }.getOrElse { fail(500, "reminders_error") }

    private suspend fun list(): JsonElement = buildJsonArray {
        store.pending().forEach { r ->
            add(
                buildJsonObject {
                    put("id", r.id)
                    put("text", r.text)
                    put("at_ms", r.atMs)
                },
            )
        }
    }

    private suspend fun add(body: String?): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val text = o.str("text").take(500)
        val at = (o["at_ms"] as? JsonPrimitive)?.longOrNull ?: return fail(400, "bad_body")
        if (text.isEmpty()) return fail(400, "empty")
        if (at <= now()) return fail(422, "past")
        val id = store.add(text, at)
        if (id.isEmpty()) return fail(400, "empty")
        return ok(
            buildJsonObject {
                put("id", id)
                put("at_ms", at)
            },
        )
    }

    private suspend fun snooze(id: String, body: String?): String {
        val minutes = if (body.isNullOrBlank()) {
            10
        } else {
            val o = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return fail(400, "bad_body")
            val raw = o["minutes"]
            if (raw == null || raw is kotlinx.serialization.json.JsonNull) {
                10
            } else {
                val prim = raw as? JsonPrimitive ?: return fail(400, "bad_body")
                if (prim.isString) return fail(400, "bad_body")
                val parsed = prim.content.toIntOrNull() ?: return fail(400, "bad_body")
                if (parsed !in 1..1440) return fail(400, "bad_body")
                parsed
            }
        }
        val row = store.pending().firstOrNull { it.id == id } ?: return fail(404, "gone")
        val at = now() + minutes * 60_000L
        val newId = store.add(row.text, at)
        if (newId.isEmpty()) return fail(400, "empty")
        store.remove(id)
        return ok(
            buildJsonObject {
                put("id", newId)
                put("at_ms", at)
            },
        )
    }

    private fun JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
        put("detail", "")
    }.toString()
}
