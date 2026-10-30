package local.phantom.companion.core.solana

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

enum class FaucetStatus { READY, SENDING, OFF, LIMITED, RETRY }

sealed interface FaucetResult {
    data class Sent(val signature: String, val units: Long) : FaucetResult
    data object Off : FaucetResult
    data object Limited : FaucetResult
    data object Unavailable : FaucetResult
}

class FaucetClient(private val baseUrl: String, private val http: HttpCall = PlainHttp) {

    val configured: Boolean get() = baseUrl.isNotBlank()

    suspend fun drip(wallet: SolanaKey, device: String): FaucetResult {
        if (!configured) return FaucetResult.Unavailable
        val body = buildJsonObject {
            put("wallet", wallet.base58)
            put("device", device)
        }.toString()
        val (code, text) = runCatching { http.request(baseUrl.trimEnd('/') + "/api/solana/faucet", body) }
            .getOrElse { return FaucetResult.Unavailable }
        return when (code) {
            200 -> runCatching {
                val o = SolanaRpc.JSON.parseToJsonElement(text).jsonObject
                val sig = o["signature"]?.jsonPrimitive?.content?.takeIf { Base58.decode(it)?.size == 64 }
                    ?: return FaucetResult.Unavailable
                val units = o["units"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
                    ?: return FaucetResult.Unavailable
                FaucetResult.Sent(sig, units)
            }.getOrDefault(FaucetResult.Unavailable)
            503 -> FaucetResult.Off
            429 -> FaucetResult.Limited
            else -> FaucetResult.Unavailable
        }
    }
}
