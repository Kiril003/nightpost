package local.phantom.companion.core.license

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

object SignedDocument {

    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    fun canonicalBytes(document: JsonObject): ByteArray {
        val sb = StringBuilder()
        writeObject(sb, document, drop = "sig")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun verify(document: JsonObject, publicKeyHex: String): Boolean {
        val sig = (document["sig"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return false
        val signature = runCatching { java.util.Base64.getDecoder().decode(sig) }
            .getOrNull() ?: return false
        val key = runCatching { hexToBytes(publicKeyHex) }.getOrNull() ?: return false
        if (key.size != Ed25519PublicKeyParameters.KEY_SIZE) return false
        return runCatching {
            val verifier = Ed25519Signer().apply {
                init(false, Ed25519PublicKeyParameters(key, 0))
            }
            val payload = canonicalBytes(document)
            verifier.update(payload, 0, payload.size)
            verifier.verifySignature(signature)
        }.getOrDefault(false)
    }

    fun verify(text: String, publicKeyHex: String): Boolean {
        val document = parse(text) ?: return false
        return verify(document, publicKeyHex)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        require(clean.length % 2 == 0)
        return ByteArray(clean.length / 2) {
            clean.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    private fun writeObject(sb: StringBuilder, obj: JsonObject, drop: String? = null) {
        sb.append('{')
        var first = true
        obj.keys.filter { it != drop }.sorted().forEach { key ->
            if (!first) sb.append(',')
            first = false
            writeString(sb, key)
            sb.append(':')
            writeValue(sb, obj.getValue(key))
        }
        sb.append('}')
    }

    private fun writeValue(sb: StringBuilder, element: JsonElement) {
        when {
            element is JsonNull -> sb.append("null")
            element is JsonObject -> writeObject(sb, element)
            element is JsonArray -> {
                sb.append('[')
                element.forEachIndexed { index, item ->
                    if (index > 0) sb.append(',')
                    writeValue(sb, item)
                }
                sb.append(']')
            }
            element is JsonPrimitive && element.isString -> writeString(sb, element.content)
            else -> sb.append((element as JsonPrimitive).content)
        }
    }

    private fun writeString(sb: StringBuilder, value: String) {
        sb.append('"')
        for (ch in value) {
            when {
                ch == '"' -> sb.append("\\\"")
                ch == '\\' -> sb.append("\\\\")
                ch == '\n' -> sb.append("\\n")
                ch == '\r' -> sb.append("\\r")
                ch == '\t' -> sb.append("\\t")
                ch == '\b' -> sb.append("\\b")
                ch == '' -> sb.append("\\f")
                ch < ' ' || ch > '~' -> sb.append("\\u").append("%04x".format(ch.code))
                else -> sb.append(ch)
            }
        }
        sb.append('"')
    }
}
