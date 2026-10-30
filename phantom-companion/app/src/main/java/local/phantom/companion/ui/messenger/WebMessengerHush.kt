package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.data.ChatHush
import local.phantom.companion.data.HushRule

/**
 * `/messenger/hush` — the muted-chat set the shade obeys. The web writes here; the
 * app's notifier reads the same store, so «muted» on screen and silence in the shade
 * cannot drift apart.
 */
class WebMessengerHush(private val port: Port, private val now: () -> Long = System::currentTimeMillis) {

    interface Port {
        fun live(nowMs: Long): ChatHush
        fun set(chatId: String, untilMs: Long?, title: String?)
        fun clear(chatId: String)
        fun setRules(rules: Map<String, HushRule>) {}
    }

    fun claims(seg: List<String>): Boolean =
        seg.size in 2..3 && seg[1] == "hush" || seg.size == 2 && seg[1] == RULES_SEG

    fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        when {
            seg[1] == RULES_SEG -> if (method == "PUT") putRules(body) else fail(404, "not_found")
            seg.size == 2 && method == "GET" -> ok(rows())
            seg.size == 3 && method == "PUT" -> put(seg[2], body)
            seg.size == 3 && method == "DELETE" -> {
                port.clear(seg[2])
                ok(rows())
            }
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "hush_error") }

    private fun put(chatId: String, body: String?): String {
        if (chatId.isBlank() || chatId.length > MAX_ID) return fail(400, "bad_id")
        val o = runCatching { Json.parseToJsonElement(body ?: "{}").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val until = when (val raw = o["until"]) {
            null, JsonNull -> null
            is JsonPrimitive -> raw.takeUnless { it.isString }?.longOrNull ?: return fail(400, "bad_body")
            else -> return fail(400, "bad_body")
        }
        if (until != null && until <= now()) return fail(422, "past")
        val title = (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.take(MAX_TITLE)
        port.set(chatId, until, title)
        return ok(rows())
    }

    /** The web's rules, resolved per chat: `{chats:{id:{from?,to?,ask?}}}`; a bad row is skipped. */
    private fun putRules(body: String?): String {
        val o = runCatching { Json.parseToJsonElement(body ?: "").jsonObject }.getOrNull() ?: return fail(400, "bad_body")
        val chats = o["chats"] as? JsonObject ?: return fail(400, "bad_body")
        val out = LinkedHashMap<String, HushRule>()
        for ((id, raw) in chats) {
            if (out.size >= MAX_RULES) break
            if (id.isBlank() || id.length > MAX_ID) continue
            val r = raw as? JsonObject ?: continue
            val from = (r["from"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val to = (r["to"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val ask = (r["ask"] as? JsonPrimitive)?.booleanOrNull == true
            HushRule.of(from, to, ask)?.let { out[id] = it }
        }
        port.setRules(out)
        return ok(buildJsonObject { put("rules", out.size) })
    }

    private fun rows(): JsonElement = buildJsonObject {
        put(
            "chats",
            buildJsonObject {
                port.live(now()).until.forEach { (id, end) -> if (end == null) put(id, JsonNull) else put(id, end) }
            },
        )
    }

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

    companion object {
        /** Native changed the set (Settings › Notifications) — the web re-reads it. */
        const val CHANGED_JS = "window.__phantomHushChanged && window.__phantomHushChanged();"

        private const val RULES_SEG = "hush-rules"
        private const val MAX_RULES = 5000
        private const val MAX_ID = 200
        private const val MAX_TITLE = 120
    }
}
