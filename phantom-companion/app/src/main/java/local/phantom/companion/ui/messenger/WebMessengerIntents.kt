package local.phantom.companion.ui.messenger

import android.content.pm.PackageManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.R
import local.phantom.companion.core.net.intent.IntentView
import local.phantom.companion.data.AppContainer
import local.phantom.companion.data.intent.IntentPermit
import local.phantom.companion.data.intent.IntentSmsFlavor

/**
 * `/messenger/intents*` — «Мої прохання» і екран «Без інтернету» (розд. 07).
 *
 *   GET  /messenger/intents          журнал (IntentView, форма intentsApi.ts)
 *   GET  /messenger/intents/setup    що є: збірка з SMS, дозволи, номер шлюзу,
 *                                    «цей телефон — домашній шлюз», лічильник шлюзу
 *   POST /messenger/intents/setup    змінити налаштування
 *   POST /messenger/intents/permit   попросити дозволи SMS (єдине місце в застосунку)
 *   POST /messenger/intents/test     одне справжнє прохання до ПК — подивитись, якою смугою прийде
 */
class WebMessengerIntents(private val container: AppContainer) {

    fun claims(seg: List<String>): Boolean = seg.size >= 2 && seg[1] == SEGMENT

    fun handle(method: String, seg: List<String>, body: String?): String = runCatching {
        val wiring = container.intents
        when {
            seg.size == 2 && method == "GET" -> okRaw(IntentView.page(wiring.client.journal.value))
            seg.getOrNull(2) == "setup" && method == "GET" -> ok(setup())
            seg.getOrNull(2) == "setup" && method == "POST" -> {
                val o = body?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    ?: return fail(400, "bad_body")
                o.str("gateway_number")?.let { wiring.settings.gatewayNumber = it }
                o.bool("personal")?.let { wiring.settings.personalAllowed = it }
                o.bool("quiet")?.let { wiring.settings.quiet = it }
                if (IntentSmsFlavor.SMS) {
                    o.bool("home_gateway")?.let { wiring.settings.homeGateway = it }
                    (o["allowed"] as? JsonArray)?.let { arr ->
                        wiring.settings.allowed = arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    }
                }
                ok(setup())
            }
            seg.getOrNull(2) == "permit" && method == "POST" -> {
                if (!IntentSmsFlavor.SMS) return fail(409, "no_sms_build")
                IntentPermit.ask()
                ok(buildJsonObject { put("asked", true) })
            }
            seg.getOrNull(2) == "test" && method == "POST" -> {
                val r = wiring.client.question(container.applicationContext.getString(R.string.intents_test_question)) ?: return fail(409, "no_pc")
                ok(buildJsonObject { put("id", r.idHex) })
            }
            else -> fail(404, "not_found")
        }
    }.getOrElse { fail(500, "intents_failed") }

    private fun permitted(): Boolean = IntentSmsFlavor.SMS && IntentPermit.PERMISSIONS.all {
        container.applicationContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun setup(): JsonObject {
        val s = container.intents.settings
        val ledger = container.intents.gateway?.ledger()
        return buildJsonObject {
            put("sms_build", IntentSmsFlavor.SMS)
            put("permitted", permitted())
            put("pc_paired", runCatching { container.store.load()?.nodePeerId }.getOrNull()?.isNotBlank() == true)
            put("gateway_number", s.gatewayNumber)
            put("personal", s.personalAllowed)
            put("quiet", s.quiet)
            put("home_gateway", IntentSmsFlavor.SMS && s.homeGateway)
            put("allowed", buildJsonArray { s.allowed.forEach { add(JsonPrimitive(it)) } })
            if (ledger != null) {
                put(
                    "ledger",
                    buildJsonObject {
                        put("received", ledger.received)
                        put("forwarded", ledger.forwarded)
                        put("sms_sent", ledger.smsSent)
                        put("refused", ledger.refused)
                        put("cap", container.intents.gateway?.cap ?: 0)
                    },
                )
            }
        }
    }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (get(key) as? JsonPrimitive)?.booleanOrNull

    private fun ok(data: JsonElement): String = buildJsonObject {
        put("ok", true)
        put("status", 200)
        put("data", data)
    }.toString()

    private fun okRaw(json: String): String = ok(Json.parseToJsonElement(json))

    private fun fail(status: Int, code: String): String = buildJsonObject {
        put("ok", false)
        put("status", status)
        put("code", code)
    }.toString()

    companion object {
        const val SEGMENT = "intents"
    }
}
