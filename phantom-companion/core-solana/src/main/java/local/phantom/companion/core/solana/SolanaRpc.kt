package local.phantom.companion.core.solana

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

fun interface RpcTransport {
    suspend fun post(url: String, body: String): String
}

object HttpRpcTransport : RpcTransport {
    override suspend fun post(url: String, body: String): String = withContext(Dispatchers.IO) {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

class RpcError(message: String, val code: Int? = null) : Exception(message) {
    // -32001..-32016 — відповіді самого ланцюга (preflight, blockhash, підпис); той самий запит деінде дасть те саме.
    // Решта (-32000 «немає ключа», 429 у тілі, -32005 «вузол відстав», -326xx) — біда вузла, а не запиту.
    val fromChain: Boolean get() = code != null && code in -32016..-32001 && code != NODE_UNHEALTHY || code == INVALID_PARAMS

    private companion object {
        const val NODE_UNHEALTHY = -32005
        const val INVALID_PARAMS = -32602
    }
}

/** Кілька вузлів по черзі: безкоштовні тарифи Helius/Alchemy/… падають по ліміту, тоді йдемо до наступного. */
class SolanaRpc(urls: List<String>, private val transport: RpcTransport = HttpRpcTransport) {

    constructor(url: String, transport: RpcTransport = HttpRpcTransport) : this(listOf(url), transport)

    private val urls = urls.map { it.trim() }.filter { it.isNotEmpty() }.distinct().also { require(it.isNotEmpty()) }

    suspend fun call(method: String, params: JsonArray): JsonElement {
        var last: Exception? = null
        for (i in urls.indices) {
            val url = urls[(start + i) % urls.size]
            try {
                return callOne(url, method, params).also { start = (start + i) % urls.size }
            } catch (e: RpcError) {
                if (e.fromChain) throw e
                last = e
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                last = e
            }
        }
        throw last ?: java.io.IOException("no rpc")
    }

    @Volatile private var start = 0

    private suspend fun callOne(url: String, method: String, params: JsonArray): JsonElement {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", 1)
            put("method", method)
            put("params", params)
        }.toString()
        val reply = JSON.parseToJsonElement(transport.post(url, body)).jsonObject
        reply["error"]?.takeIf { it !is JsonNull }?.let {
            val o = it as? JsonObject
            throw RpcError(o?.get("message")?.jsonPrimitive?.content ?: it.toString(), o?.get("code")?.jsonPrimitive?.content?.toIntOrNull())
        }
        return reply["result"] ?: JsonNull
    }

    suspend fun latestBlockhash(): ByteArray {
        val r = call("getLatestBlockhash", buildJsonArray { add(buildJsonObject { put("commitment", "confirmed") }) })
        val hash = r.jsonObject["value"]!!.jsonObject["blockhash"]!!.jsonPrimitive.content
        return requireNotNull(Base58.decode(hash)?.takeIf { it.size == 32 }) { "bad blockhash" }
    }

    suspend fun transaction(signature: String): JsonElement = call(
        "getTransaction",
        buildJsonArray {
            add(JsonPrimitive(signature))
            add(buildJsonObject {
                put("encoding", "jsonParsed")
                put("commitment", "confirmed")
                put("maxSupportedTransactionVersion", 0)
            })
        },
    )

    suspend fun signatureStatus(signature: String): JsonElement = call(
        "getSignatureStatuses",
        buildJsonArray {
            add(buildJsonArray { add(JsonPrimitive(signature)) })
            add(buildJsonObject { put("searchTransactionHistory", true) })
        },
    ).jsonObject["value"]?.jsonArray?.firstOrNull() ?: JsonNull

    suspend fun signaturesFor(address: SolanaKey, limit: Int = 10): List<String> = call(
        "getSignaturesForAddress",
        buildJsonArray {
            add(JsonPrimitive(address.base58))
            add(buildJsonObject { put("limit", limit); put("commitment", "confirmed") })
        },
    ).jsonArray.mapNotNull { (it as? JsonObject)?.get("signature")?.jsonPrimitive?.content }

    suspend fun tokenAccounts(owner: SolanaKey, program: SolanaKey): JsonElement = call(
        "getTokenAccountsByOwner",
        buildJsonArray {
            add(JsonPrimitive(owner.base58))
            add(buildJsonObject { put("programId", program.base58) })
            add(buildJsonObject { put("encoding", "jsonParsed") })
        },
    )

    suspend fun accounts(keys: List<String>): JsonElement = call(
        "getMultipleAccounts",
        buildJsonArray {
            add(buildJsonArray { keys.forEach { add(JsonPrimitive(it)) } })
            add(buildJsonObject { put("encoding", "jsonParsed") })
        },
    )

    suspend fun account(key: SolanaKey): JsonElement = call(
        "getAccountInfo",
        buildJsonArray {
            add(JsonPrimitive(key.base58))
            add(buildJsonObject { put("encoding", "jsonParsed") })
        },
    )

    suspend fun sendRaw(tx: ByteArray): String = call(
        "sendTransaction",
        buildJsonArray {
            add(JsonPrimitive(java.util.Base64.getEncoder().encodeToString(tx)))
            // Блокхеш і баланси беремо на «confirmed»; preflight за замовчуванням дивиться на «finalized» і відкидає свіже.
            add(buildJsonObject { put("encoding", "base64"); put("preflightCommitment", "confirmed") })
        },
    ).jsonPrimitive.content

    suspend fun lamports(key: SolanaKey): Long = call(
        "getBalance",
        buildJsonArray { add(JsonPrimitive(key.base58)); add(buildJsonObject { put("commitment", "confirmed") }) },
    ).jsonObject["value"]!!.jsonPrimitive.content.toLong()

    /** Баланс токена в базових одиницях; 0, якщо рахунку ще немає. */
    suspend fun tokenUnits(owner: SolanaKey, mint: SolanaKey): Long = runCatching {
        call(
            "getTokenAccountBalance",
            buildJsonArray {
                add(JsonPrimitive(Programs.associatedTokenAddress(owner, mint).base58))
                add(buildJsonObject { put("commitment", "confirmed") })
            },
        ).path("value", "amount")!!.jsonPrimitive.content.toLong()
    }.getOrElse { if (it is RpcError) 0L else throw it }

    suspend fun units(owner: SolanaKey, coin: Coin, cluster: Cluster): Long =
        if (coin.native) lamports(owner) else tokenUnits(owner, coin.mint(cluster)!!)

    suspend fun requestAirdrop(key: SolanaKey, lamports: Long): String = call(
        "requestAirdrop",
        buildJsonArray { add(JsonPrimitive(key.base58)); add(JsonPrimitive(lamports)) },
    ).jsonPrimitive.content

    suspend fun rentExempt(space: Int): Long = call(
        "getMinimumBalanceForRentExemption",
        buildJsonArray { add(JsonPrimitive(space)) },
    ).jsonPrimitive.content.toLong()

    /** Збережений у nonce-рахунку «блокхеш»; null — рахунку ще немає. */
    suspend fun nonceValue(nonce: SolanaKey): ByteArray? {
        val v = account(nonce).jsonObject["value"] as? JsonObject ?: return null
        val hash = v.path("data", "parsed", "info", "blockhash")?.jsonPrimitive?.content ?: return null
        return Base58.decode(hash)?.takeIf { it.size == 32 }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        /** Наш проксі першим: ключ платного вузла живе на сервері, а не в APK. Далі вузли з local.properties, публічний — останнім. */
        fun ladder(serverBase: String, keyed: String, cluster: Cluster): List<String> =
            listOfNotNull(serverBase.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it/api/solana/rpc/${cluster.wire}" }) +
                keyed.split(',').map { it.trim() }.filter { it.startsWith("https://") } + cluster.publicRpc
    }
}
