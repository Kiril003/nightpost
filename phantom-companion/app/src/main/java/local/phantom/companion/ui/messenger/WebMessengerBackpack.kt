package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import local.phantom.companion.core.net.backpack.Backpack
import local.phantom.companion.data.AppContainer
import org.json.JSONObject

/**
 * `/messenger/backpack` — «Профіль › Офлайн-мапи»: що лежить у рюкзаку.
 *
 *   GET  /messenger/backpack        стан (що вдалося, що ні, справжній прогрес мапи, маяк)
 *   POST /messenger/backpack/pack   зібрати зараз (той самий бюджет, що після тривоги)
 */
class WebMessengerBackpack(private val container: AppContainer) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == SEGMENT

    fun handle(method: String, seg: List<String>): String = runCatching {
        when {
            seg.size == 2 && method == "GET" -> ok(container.backpack.view())
            seg.getOrNull(2) == "pack" && method == "POST" -> {
                scope.launch { container.backpack.pack(Backpack.Trigger.MANUAL) }
                ok(JSONObject().put("started", true))
            }
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "backpack_failed") }

    private fun ok(data: JSONObject): String = JSONObject().put("ok", true).put("status", 200).put("data", data).toString()

    private fun fail(status: Int, code: String): String =
        JSONObject().put("ok", false).put("status", status).put("code", code).toString()

    companion object {
        const val SEGMENT = "backpack"
    }
}
