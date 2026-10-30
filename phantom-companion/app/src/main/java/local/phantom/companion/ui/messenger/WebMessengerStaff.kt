package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.StaffWire
import org.json.JSONArray
import org.json.JSONObject

/**
 * Пропуск працівника (профілі S8, D7).
 *
 * `GET /messenger/staff` — розмови, доручені цьому телефону власниками бізнесів.
 * `POST /messenger/staff/push` `{to, pass}` — власник шле пропуск своєму працівникові;
 * порожній `threads` — відкликання. Лише людині з контактів.
 */
internal class WebMessengerStaff(
    private val rows: () -> List<Pair<String, StaffWire.Pass>>,
    private val knows: (String) -> Boolean,
    private val send: (String, String) -> Boolean,
) {
    fun claims(seg: List<String>): Boolean = seg.size in 2..3 && seg[1] == SEGMENT

    fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        when {
            method == "GET" && seg.size == 2 -> list()
            method == "POST" && seg.size == 3 && seg[2] == "push" -> push(body)
            else -> fail(405, "method")
        }
    }.getOrElse { fail(500, "staff_failed") }

    private fun list(): String {
        val arr = JSONArray()
        rows().forEach { (from, pass) ->
            val threads = JSONArray()
            pass.threads.forEach { t ->
                val lines = JSONArray()
                t.lines.forEach { l -> lines.put(JSONObject().put("mine", l.mine).put("text", l.text).put("at", l.atMs)) }
                threads.put(JSONObject().put("id", t.id).put("client", t.client).put("lines", lines))
            }
            arr.put(JSONObject().put("from", from).put("business", pass.business).put("rev", pass.rev).put("threads", threads))
        }
        return ok(JSONObject().put("passes", arr))
    }

    private fun push(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return fail(400, "bad_body")
        val to = o.optString("to").takeIf { it.isNotBlank() } ?: return fail(400, "bad_body")
        val p = o.optJSONObject("pass") ?: return fail(400, "bad_body")
        if (!knows(to)) return fail(409, "not_contact")
        val threads = mutableListOf<StaffWire.Thread>()
        val arr = p.optJSONArray("threads") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val lines = mutableListOf<StaffWire.Line>()
            val la = t.optJSONArray("lines") ?: JSONArray()
            for (j in 0 until la.length()) {
                val l = la.optJSONObject(j) ?: continue
                lines += StaffWire.Line(l.optBoolean("mine"), l.optString("text"), l.optLong("at"))
            }
            threads += StaffWire.Thread(t.optString("id"), t.optString("client"), lines)
        }
        val frame = StaffWire.encode(StaffWire.Pass(p.optString("business"), p.optLong("rev"), threads))
            ?: return fail(400, "bad_pass")
        if (!send(to, frame)) return fail(503, "unsent")
        return ok(JSONObject().put("sent", true))
    }

    private fun ok(data: JSONObject): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    companion object {
        const val SEGMENT = "staff"
    }
}
