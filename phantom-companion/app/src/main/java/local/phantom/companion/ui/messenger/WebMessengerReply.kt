package local.phantom.companion.ui.messenger

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Конверт відповіді моста для класів, що збирають JSON без `org.json`
 * ([WebMessengerVenues], [WebMessengerPoints]) — тієї самої форми, що `ok()` і
 * `fail()` у [WebMessengerBridge]: `{ok, status, data}` чи `{ok, status, code, detail}`.
 */
internal object WebMessengerReply {
    fun ok(data: JsonElement): String =
        JsonObject(mapOf("ok" to JsonPrimitive(true), "status" to JsonPrimitive(200), "data" to data)).toString()

    fun fail(status: Int, code: String, detail: String): String =
        JsonObject(
            mapOf(
                "ok" to JsonPrimitive(false),
                "status" to JsonPrimitive(status),
                "code" to JsonPrimitive(code),
                "detail" to JsonPrimitive(detail),
            ),
        ).toString()
}
