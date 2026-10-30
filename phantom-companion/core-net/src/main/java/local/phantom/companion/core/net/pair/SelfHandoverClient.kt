package local.phantom.companion.core.net.pair

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.OwnSelf
import local.phantom.companion.core.net.relay.NodeRoutes

/**
 * Передача моєї особи ПК: POST /api/v1/pair/self (docs/ONE_SELF.md, крок 1).
 *
 * Окремо від [PairFlowClient]: там спарювання, тут — насіння, і шлях у них
 * різний. Лише QR «особа», лише свіжий; сеанс на ПК одноразовий, тож після
 * будь-якої HTTP-відповіді інша адреса не пробується. «Передано» — лише коли
 * ПК довів прийом ключем сеансу.
 */
class SelfHandoverClient(
    engine: HttpClientEngineFactory<*>? = null,
    private val timeoutMs: Long = PairFlowClient.DEFAULT_TIMEOUT_MS,
) {
    sealed interface Result {
        data class Done(val selfId: String, val nodeId: String) : Result
        data class Refused(val code: String) : Result
    }

    @Serializable
    private data class Request(
        @SerialName("pair_id") val pairId: String,
        @SerialName("client_pub") val clientPub: String,
        @SerialName("nonce_echo") val nonceEcho: String,
        @SerialName("self_id") val selfId: String,
        @SerialName("node_id") val nodeId: String,
        val iv: String,
        val sealed: String,
    )

    @Serializable
    private data class Response(
        @SerialName("self_id") val selfId: String,
        @SerialName("node_id") val nodeId: String,
        val proof: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val pin = AtomicReference("")

    private val http: HttpClient =
        if (engine != null) {
            HttpClient(engine) { configure() }
        } else {
            HttpClient(OkHttp) {
                engine {
                    preconfigured = PinnedTrust.apply(okhttp3.OkHttpClient.Builder()) { pin.get() }.build()
                }
                configure()
            }
        }

    constructor(engineInstance: HttpClientEngine, timeoutMs: Long = PairFlowClient.DEFAULT_TIMEOUT_MS) : this(
        engine = object : HttpClientEngineFactory<HttpClientEngineConfig> {
            override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = engineInstance
        },
        timeoutMs = timeoutMs,
    )

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = timeoutMs
        }
        expectSuccess = false
    }

    suspend fun handOver(qr: PairingQr, keys: OwnSelf.Keys, routes: NodeRoutes? = null): Result {
        if (qr.purpose != PURPOSE_SELF || qr.nodeId.isBlank()) return Result.Refused("not_self_qr")
        if (qr.isExpired()) return Result.Refused("expired")
        if (!keys.isWhole()) return Result.Refused("bad_identity")

        val sealed = OwnSelf.seal(qr.serverPubB64, qr.nonce, qr.pairId, qr.nodeId, keys)
        val request = Request(
            pairId = qr.pairId,
            clientPub = sealed.clientPubB64,
            nonceEcho = qr.nonce,
            selfId = keys.selfId,
            nodeId = qr.nodeId,
            iv = sealed.ivB64,
            sealed = sealed.sealedB64,
        )
        pin.set(qr.serverCertSha256)
        val candidates = routes?.resolveAll(qr)?.takeIf { it.isNotEmpty() } ?: qr.candidateUrls()
        for (baseUrl in candidates) {
            val response = try {
                http.post("$baseUrl/api/v1/pair/self") {
                    contentType(ContentType.Application.Json)
                    setBody(request)
                }
            } catch (_: java.io.IOException) {
                continue
            } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
                continue
            }
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            if (response.status != HttpStatusCode.OK) return Result.Refused(codeOf(text))
            val done = runCatching { json.decodeFromString(Response.serializer(), text) }.getOrNull()
                ?: return Result.Refused("bad_proof")
            val proven = done.selfId == keys.selfId && done.nodeId == qr.nodeId &&
                OwnSelf.verifyDone(sealed.key, qr.pairId, keys.selfId, qr.nodeId, done.proof)
            return if (proven) Result.Done(keys.selfId, qr.nodeId) else Result.Refused("bad_proof")
        }
        return Result.Refused("unreachable")
    }

    private fun codeOf(text: String): String = runCatching {
        json.parseToJsonElement(text).jsonObject["detail"]!!.jsonObject["code"]!!.jsonPrimitive.content
    }.getOrDefault("http")

    fun close() {
        runCatching { http.close() }
    }

    companion object {
        const val PURPOSE_SELF = "self"
    }
}
