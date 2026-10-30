package local.phantom.companion.data.erase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import local.phantom.companion.BuildConfig
import local.phantom.companion.data.ClaimSigner
import local.phantom.companion.data.DeviceClaim
import local.phantom.companion.data.PeerMesh
import local.phantom.companion.data.VenueClient

class EraseClient(
    private val claims: ClaimSigner,
    private val rootSigner: EraseSigner,
    private val http: VenueClient.Http = VenueClient.urlConnection(),
) : EraseEverything.EraseServer {

    override suspend fun claim(): EraseEverything.EraseServer.Result {
        val proof = claims.prove(DeviceClaim.Erase) ?: return EraseEverything.EraseServer.Result.Unreachable
        return post("${BuildConfig.LICENSE_BASE_URL}/api/erase/claim", bodyOf(proof))
    }

    override suspend fun root(): EraseEverything.EraseServer.Result {
        val proof = rootSigner.prove() ?: return EraseEverything.EraseServer.Result.Unreachable
        return post("${BuildConfig.LICENSE_BASE_URL}/api/erase", bodyOf(proof))
    }

    override suspend fun cancel(): EraseEverything.EraseServer.Result {
        val proof = rootSigner.cancel() ?: return EraseEverything.EraseServer.Result.Unreachable
        return post("${BuildConfig.LICENSE_BASE_URL}/api/erase/cancel", bodyOf(proof))
    }

    private suspend fun post(url: String, body: String): EraseEverything.EraseServer.Result {
        val (status, text) = http.call("POST", url, body) ?: return EraseEverything.EraseServer.Result.Unreachable
        if (status in 200..299) {
            return EraseEverything.EraseServer.Result.Ok(parseDueAt(text))
        }
        return EraseEverything.EraseServer.Result.Refused(status)
    }

    companion object {
        internal fun bodyOf(proof: PeerMesh.AttachmentProof): String = buildJsonObject {
            put("pub_key", proof.pubKey)
            put("nonce", proof.nonce)
            put("issued_ms", proof.issuedMs)
            put("signature", proof.signature)
        }.toString()

        internal fun parseDueAt(text: String): Long {
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return 0L
            (obj["due_at_ms"] as? JsonPrimitive)?.longOrNull?.let { return it }
            val raw = obj["due_at"] as? JsonPrimitive ?: return 0L
            raw.longOrNull?.let { return it * 1000L }
            return runCatching { java.time.Instant.parse(raw.content).toEpochMilli() }.getOrDefault(0L)
        }
    }
}
