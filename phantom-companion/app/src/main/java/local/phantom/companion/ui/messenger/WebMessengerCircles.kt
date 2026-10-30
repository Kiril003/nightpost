package local.phantom.companion.ui.messenger

import android.net.Uri
import local.phantom.companion.data.CircleRules
import local.phantom.companion.data.PeerCircle
import local.phantom.companion.data.PeerRosterStore
import org.json.JSONArray
import org.json.JSONObject

internal class WebMessengerCircles(
    private val roster: PeerRosterStore,
    private val known: (String) -> Boolean,
) {
    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == SEGMENT

    fun handle(method: String, rawSeg: List<String>, body: String?): String = runCatching {
        val seg = rawSeg.map(Uri::decode)
        when {
            seg.size == 2 && method == "GET" -> listCircles()
            seg.size == 2 && method == "POST" -> createCircle(body)
            seg.size == 3 && method == "DELETE" -> deleteCircle(seg[2])
            seg.size == 4 && seg[3] == "members" && method == "GET" -> listMembers(seg[2])
            seg.size == 4 && seg[3] == "members" && method == "POST" -> addMember(seg[2], body)
            seg.size == 5 && seg[3] == "members" && method == "DELETE" -> removeMember(seg[2], seg[4])
            seg.size == 4 && seg[3] == "share" && method == "POST" -> shareCircle(seg[2], body)
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "circles_failed") }

    private fun listCircles(): String {
        val arr = JSONArray()
        for (id in PeerCircle.ALL) {
            arr.put(
                JSONObject()
                    .put("id", id)
                    .put("name", JSONObject.NULL)
                    .put("builtin", true)
                    .put("count", roster.membersOf(id).size)
                    .put("location_default", CircleRules.locationDefault(id))
                    .put("moments_default", CircleRules.momentsDefault(id)),
            )
        }
        for (custom in roster.customCircles.value) {
            arr.put(
                JSONObject()
                    .put("id", custom.id)
                    .put("name", custom.name)
                    .put("builtin", false)
                    .put("count", roster.membersOf(custom.id).size)
                    .put("location_default", CircleRules.locationDefault(custom.id))
                    .put("moments_default", CircleRules.momentsDefault(custom.id)),
            )
        }
        return ok(JSONObject().put("circles", arr).put("max_custom", PeerCircle.MAX_CUSTOM))
    }

    private fun createCircle(body: String?): String {
        val name = parseJson(body)?.optString("name", "")?.trim().orEmpty()
        if (name.isEmpty() || name.length > 32) return fail(400, "bad_circle_name")
        if (roster.customCircles.value.size >= PeerCircle.MAX_CUSTOM) return fail(409, "too_many_circles")
        val created = roster.createCircle(name) ?: return fail(409, "too_many_circles")
        return ok(JSONObject().put("id", created.id).put("name", created.name))
    }

    private fun deleteCircle(id: String): String {
        if (id in PeerCircle.ALL) return fail(400, "builtin_circle")
        if (!roster.deleteCircle(id)) return fail(404, "no_circle")
        return ok(JSONObject())
    }

    private fun listMembers(circleId: String): String {
        if (!roster.isCircle(circleId)) return fail(404, "no_circle")
        val arr = JSONArray(roster.membersOf(circleId).toList())
        return ok(JSONObject().put("members", arr))
    }

    private fun addMember(circleId: String, body: String?): String {
        if (!roster.isCircle(circleId)) return fail(404, "no_circle")
        val peerId = parseJson(body)?.optString("peer_id", "")?.trim().orEmpty()
        if (peerId.isEmpty() || !known(peerId)) return fail(404, "no_conversation")
        roster.addToCircle(peerId, circleId)
        return ok(JSONObject().put("circles", JSONArray(orderedCirclesOf(peerId))))
    }

    private fun removeMember(circleId: String, peerId: String): String {
        if (!roster.isCircle(circleId)) return fail(404, "no_circle")
        if (peerId.isEmpty() || !known(peerId)) return fail(404, "no_conversation")
        roster.removeFromCircle(peerId, circleId)
        return ok(JSONObject().put("circles", JSONArray(orderedCirclesOf(peerId))))
    }

    private fun shareCircle(circleId: String, body: String?): String {
        if (!roster.isCircle(circleId)) return fail(404, "no_circle")
        val untilMs = parseJson(body)?.optLong("until_ms", 0L) ?: 0L
        if (untilMs <= System.currentTimeMillis()) return fail(400, "bad_until")
        val count = roster.shareCircle(circleId, untilMs)
        return ok(JSONObject().put("shared", count))
    }

    private fun orderedCirclesOf(peerId: String): List<String> {
        val set = roster.circlesOf(peerId)
        val order = PeerCircle.ALL + roster.customCircles.value.map { it.id }
        return order.filter { it in set }
    }

    private fun parseJson(body: String?): JSONObject? =
        body?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun ok(data: JSONObject): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    companion object {
        const val SEGMENT = "circles"
    }
}
