package local.phantom.companion.feature.stream.ui.map.here

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.core.truth.CellId
import local.phantom.companion.core.truth.FactKey
import local.phantom.companion.core.truth.FactKind
import local.phantom.companion.core.truth.Place
import local.phantom.companion.core.truth.Reading
import local.phantom.companion.core.truth.TruthState
import local.phantom.companion.core.truth.TruthWire
import local.phantom.companion.core.truth.Verdict

data class TruthProof(val pubKey: String, val nonce: String, val issuedMs: Long, val signature: String)

sealed interface AreaReply {
    data class Verdicts(val list: List<Verdict>) : AreaReply
    data object NotEnabled : AreaReply
    data object Unreachable : AreaReply
}

sealed interface ObserveReply {
    data object Accepted : ObserveReply
    data class Refused(val uk: String?, val en: String?) : ObserveReply
    data class Limited(val retryAfterS: Int) : ObserveReply
    data object NoKey : ObserveReply
    data object NotEnabled : ObserveReply
    data object Unreachable : ObserveReply
}

interface HereTruthPort {
    suspend fun area(area: String): AreaReply
    suspend fun observe(cell: CellId, kind: FactKind, reading: Reading): ObserveReply
}

class HereTruthHttp(
    private val baseUrl: String,
    private val prove: (cell: String, kind: String, reading: String) -> TruthProof?,
) : HereTruthPort {

    override suspend fun area(area: String): AreaReply {
        if (baseUrl.isBlank()) return AreaReply.NotEnabled
        val (code, body) = call("GET", baseUrl.trimEnd('/') + TruthWire.AREA + area, null)
            ?: return AreaReply.Unreachable
        return when (code) {
            200 -> HereWire.parseArea(body)?.let { AreaReply.Verdicts(it) } ?: AreaReply.NotEnabled
            404 -> AreaReply.NotEnabled
            else -> AreaReply.Unreachable
        }
    }

    override suspend fun observe(cell: CellId, kind: FactKind, reading: Reading): ObserveReply {
        if (baseUrl.isBlank()) return ObserveReply.NotEnabled
        val proof = withContext(Dispatchers.Default) { prove(cell.value, kind.wire, reading.wire) }
            ?: return ObserveReply.NoKey
        val json = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
            put("cell", cell.value)
            put("kind", kind.wire)
            put("claim", "assert")
            put("reading", reading.wire)
        }.toString()
        val (code, body) = call("POST", baseUrl.trimEnd('/') + TruthWire.OBSERVE, json)
            ?: return ObserveReply.Unreachable
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull()
        return when (code) {
            202, 200 -> ObserveReply.Accepted
            404 -> ObserveReply.NotEnabled
            422 -> ObserveReply.Refused(obj.text("message_uk"), obj.text("message_en"))
            429 -> ObserveReply.Limited((obj?.get("retry_after_s") as? JsonPrimitive)?.intOrNull ?: 3600)
            401 -> ObserveReply.Refused(null, null)
            else -> ObserveReply.Unreachable
        }
    }

    private suspend fun call(method: String, url: String, json: String?): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            runCatching {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.requestMethod = method
                    c.connectTimeout = 8_000
                    c.readTimeout = 8_000
                    c.setRequestProperty("Accept", "application/json")
                    if (json != null) {
                        c.doOutput = true
                        c.setRequestProperty("Content-Type", "application/json")
                        c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    }
                    val code = c.responseCode
                    val stream = if (code >= 400) c.errorStream else c.inputStream
                    code to (stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: "")
                } finally {
                    c.disconnect()
                }
            }.getOrNull()
        }
}

object HereWire {
    /** Null — відповідь не з сервера правди (порожня сторінка, HTML проксі). */
    fun parseArea(body: String): List<Verdict>? {
        val root = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val list = runCatching { root["verdicts"]!!.jsonArray }.getOrNull() ?: return null
        return list.mapNotNull { runCatching { verdict(it.jsonObject) }.getOrNull() }
    }

    private fun verdict(j: JsonObject): Verdict? {
        val kind = FactKind.byWire(j.text("kind") ?: return null) ?: return null
        val state = TruthState.entries.firstOrNull { it.name == j.text("state") } ?: return null
        val reading = j.text("reading")?.let { w -> kind.readings.firstOrNull { it.wire == w } }
        if (state != TruthState.UNKNOWN && reading == null) return null
        return Verdict(
            key = FactKey(Place(CellId(j.text("cell") ?: return null), j.text("object")), kind),
            state = state,
            reading = reading,
            probability = j.prim("probability")?.doubleOrNull ?: 0.0,
            mass = 0.0,
            supporters = j.prim("supporters")?.intOrNull ?: 0,
            lastSupportAtMs = j.prim("last_support_ms")?.longOrNull,
            newestAtMs = j.prim("newest_ms")?.longOrNull,
            dissent = j.prim("dissent")?.booleanOrNull ?: false,
            contested = j.prim("contested")?.booleanOrNull ?: false,
        )
    }
}

private fun JsonObject.prim(k: String): JsonPrimitive? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }

private fun JsonObject?.text(k: String): String? = this?.prim(k)?.contentOrNull
