package local.phantom.companion.ui.messenger

import android.content.Context
import android.util.Base64
import java.io.File
import local.phantom.companion.share.ShareInbox
import org.json.JSONArray
import org.json.JSONObject

internal class WebMessengerShareIn(private val context: Context) {

    fun claims(seg: List<String>): Boolean =
        seg.size >= 3 && seg[1] == SEGMENT && (seg[2] == "pending" || seg[2] == "item")

    fun handle(method: String, seg: List<String>, @Suppress("UNUSED_PARAMETER") body: String?): String = runCatching {
        when {
            seg.size == 3 && seg[2] == "pending" && method == "GET" -> pendingJson()
            seg.size == 3 && seg[2] == "pending" && method == "DELETE" -> {
                ShareInbox.clear(context)
                ok(JSONObject())
            }
            seg.size == 4 && seg[2] == "item" && method == "GET" -> {
                val index = seg[3].toIntOrNull() ?: return fail(404, "no_item")
                itemJson(index)
            }
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "share_failed") }

    private fun pendingJson(): String {
        val batch = ShareInbox.pending.value
        if (batch == null) {
            return ok(
                JSONObject()
                    .put("id", JSONObject.NULL)
                    .put("items", JSONArray())
                    .put("skipped", 0),
            )
        }
        val arr = JSONArray()
        for (item in batch.items) {
            val row = JSONObject()
                .put("index", item.index)
                .put("kind", item.kind)
                .put("text", item.text ?: JSONObject.NULL)
                .put("mime", item.mime ?: JSONObject.NULL)
                .put("name", item.name ?: JSONObject.NULL)
                .put("size", item.size)
            if (item.lat != null && item.lon != null) row.put("lat", item.lat).put("lon", item.lon)
            arr.put(row)
        }
        return ok(
            JSONObject()
                .put("id", batch.id)
                .put("items", arr)
                .put("skipped", batch.skipped),
        )
    }

    private fun itemJson(index: Int): String {
        val batch = ShareInbox.pending.value ?: return fail(404, "no_item")
        val item = batch.items.firstOrNull { it.index == index && it.kind == "image" } ?: return fail(404, "no_item")
        val path = item.path ?: return fail(404, "no_item")
        val file = File(path)
        if (!file.isFile) return fail(404, "no_item")
        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        return ok(
            JSONObject()
                .put("mime", item.mime ?: "image/jpeg")
                .put("name", item.name ?: file.name)
                .put("base64", encoded),
        )
    }

    private fun ok(data: JSONObject): String =
        JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    companion object {
        const val SEGMENT = "share"
    }
}
