package local.phantom.companion.ui.messenger

import local.phantom.companion.data.VisibilityStore
import org.json.JSONObject

/** `GET|PATCH /messenger/privacy`: звіти про прочитання, «Був(ла) в мережі», «Фото профілю», «Дзвінки». */
internal class WebMessengerPrivacy(
    private val receipts: () -> Boolean,
    private val setReceipts: (Boolean) -> Unit,
    private val visibility: () -> VisibilityStore.Choice,
    private val setVisibility: (VisibilityStore.Choice) -> Unit,
) {
    fun claims(seg: List<String>): Boolean = seg.size == 2 && seg[1] == SEGMENT

    fun handle(method: String, body: String?): String = when (method) {
        "GET" -> ok()
        "PATCH" -> patch(body)
        else -> fail(405, "method")
    }

    private fun patch(body: String?): String {
        val o = body?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return fail(400, "bad_body")
        if (KEYS.none(o::has)) return fail(400, "bad_body")
        var next = visibility()
        if (o.has(SEEN)) next = next.copy(lastSeen = VisibilityStore.Level.of(o.optString(SEEN)) ?: return fail(400, "bad_level"))
        if (o.has(PHOTO)) next = next.copy(photo = VisibilityStore.Level.of(o.optString(PHOTO)) ?: return fail(400, "bad_level"))
        if (o.has(CALLS)) next = next.copy(calls = VisibilityStore.Level.of(o.optString(CALLS)) ?: return fail(400, "bad_level"))
        if (o.has(RECEIPTS)) setReceipts(o.optBoolean(RECEIPTS, true))
        if (next != visibility()) setVisibility(next)
        return ok()
    }

    private fun ok(): String {
        val v = visibility()
        val data = JSONObject().put(RECEIPTS, receipts()).put(SEEN, v.lastSeen.wire).put(PHOTO, v.photo.wire).put(CALLS, v.calls.wire)
        return JSONObject().put("ok", true).put("status", 200).put("data", data).toString()
    }

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    companion object {
        const val SEGMENT = "privacy"
        private const val RECEIPTS = "read_receipts"
        private const val SEEN = "last_seen"
        private const val PHOTO = "photo"
        private const val CALLS = "calls"
        private val KEYS = listOf(RECEIPTS, SEEN, PHOTO, CALLS)
    }
}
