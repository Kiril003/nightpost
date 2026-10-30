package local.phantom.companion.core.solana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

data class LicenceOffer(val cluster: Cluster, val recipient: SolanaKey, val prices: Map<Coin, Long>, val tier: String)

sealed interface ClaimResult {
    data class Key(val licenceKey: String) : ClaimResult
    /** Мережа ще не показала переказ — пробувати пізніше, гроші не втрачено. */
    data object NotYet : ClaimResult
    data class Refused(val reason: String) : ClaimResult
}

fun interface HttpCall {
    /** (код, тіло). GET, якщо body == null. */
    suspend fun request(url: String, body: String?): Pair<Int, String>
}

object PlainHttp : HttpCall {
    override suspend fun request(url: String, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("content-type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            conn.disconnect()
        }
    }
}

class LicenceShop(private val baseUrl: String, private val http: HttpCall = PlainHttp) {

    val configured: Boolean get() = baseUrl.isNotBlank()

    suspend fun offer(): LicenceOffer? {
        val (code, text) = runCatching { http.request(baseUrl.trimEnd('/') + "/api/solana/offer", null) }.getOrNull() ?: return null
        if (code != 200) return null
        return parseOffer(text)
    }

    suspend fun claim(reference: PurchaseReference, signature: String): ClaimResult {
        val body = buildJsonObject {
            put("reference", reference.key.base58)
            put("signature", signature)
            put("proof", reference.prove(signature))
        }.toString()
        val (code, text) = runCatching { http.request(baseUrl.trimEnd('/') + "/api/solana/claim", body) }
            .getOrElse { return ClaimResult.NotYet }
        val o = runCatching { SolanaRpc.JSON.parseToJsonElement(text).jsonObject }.getOrNull()
        return when {
            code == 200 -> o?.get("license_key")?.jsonPrimitive?.content?.let { ClaimResult.Key(it) }
                ?: ClaimResult.Refused("bad_reply")
            code == 202 || code >= 500 -> ClaimResult.NotYet
            else -> ClaimResult.Refused(o?.reason() ?: "http_$code")
        }
    }

    private fun JsonObject.reason(): String? =
        (get("detail") ?: get("reason"))?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    companion object {
        fun parseOffer(text: String): LicenceOffer? = runCatching {
            val o = SolanaRpc.JSON.parseToJsonElement(text).jsonObject
            val cluster = Cluster.of(o["cluster"]?.jsonPrimitive?.content) ?: return null
            val recipient = SolanaKey.parse(o["recipient"]?.jsonPrimitive?.content.orEmpty()) ?: return null
            val prices = (o["prices"] as? JsonObject).orEmpty().mapNotNull { (k, v) ->
                val coin = Coin.of(k)?.takeIf { it.availableOn(cluster) } ?: return@mapNotNull null
                Amounts.toUnits(v.jsonPrimitive.content, coin)?.let { coin to it }
            }.toMap()
            if (prices.isEmpty()) return null
            LicenceOffer(cluster, recipient, prices, o["tier"]?.jsonPrimitive?.content ?: "personal")
        }.getOrNull()
    }
}
