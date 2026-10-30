package local.phantom.companion.ui.messenger

import android.net.Uri
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Події простору для веба (картка msg-x 05).
 *
 * `GET /messenger/spaces/{id}/events` — події, які журнал гілки прийняв від
 * тих, хто на ту мить був у складі; `actor` — підписант листа.
 * `POST /messenger/spaces/{id}/events` `{event}` — подія їде актом гілки.
 * Права судить рушій простору у вебі; тут лише склад і розмір.
 */
internal class WebMessengerSpaces(
    private val log: () -> List<PeerMessage>,
    private val me: () -> String,
    private val sendAct: (PeerGroup.Group, PeerGroup.Act) -> List<PeerMessage>,
) {
    fun claims(seg: List<String>): Boolean = seg.size == 4 && seg[1] == SEGMENT && seg[3] == "events"

    fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        val id = Uri.decode(seg[2])
        when (method) {
            "GET" -> list(id)
            "POST" -> post(id, body)
            else -> fail(405, "method")
        }
    }.getOrElse { fail(500, "spaces_failed") }

    private fun list(id: String): String {
        val state = PeerGroup.stateOf(log(), id) ?: return fail(404, "no_space")
        if (!state.isSpace) return fail(409, "not_a_space")
        val arr = JSONArray()
        wire(state).forEach { e ->
            arr.put(JSONObject().put("actor", e.actor).put("at", e.atMs).put("event", e.json))
        }
        return ok(JSONObject().put("events", arr).put("members", JSONArray(state.group.memberIds)))
    }

    private fun post(id: String, body: String?): String {
        val all = log()
        val state = PeerGroup.stateOf(all, id) ?: return fail(404, "no_space")
        if (!state.isSpace) return fail(409, "not_a_space")
        val self = me()
        if (self.isBlank() || PeerGroup.standing(all, state.group, self) != PeerGroup.Standing.IN) {
            return fail(409, "not_in_group")
        }
        val event = body?.let { runCatching { JSONObject(it).optJSONObject("event") }.getOrNull() }
            ?: return fail(400, "bad_body")
        val json = event.toString()
        if (json.length > PeerGroup.SPACE_MAX) return fail(413, "too_big")
        if (sendAct(state.group, PeerGroup.Act.Space(json)).isEmpty()) return fail(503, "unsent")
        return ok(JSONObject().put("sent", true))
    }

    private fun ok(data: JSONObject): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    data class Wire(val actor: String, val atMs: Long, val json: String)

    companion object {
        const val SEGMENT = "spaces"

        /** Подія — рядком JSON, автор — підписант; поле `actor` у самому JSON нічого не важить. */
        fun wire(state: PeerGroup.State): List<Wire> = state.spaceEvents.map { Wire(it.actorId, it.atMs, it.json) }
    }
}
