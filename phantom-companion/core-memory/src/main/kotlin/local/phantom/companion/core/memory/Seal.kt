package local.phantom.companion.core.memory

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Шифрування памʼяті на диску — те саме, що `crypto.py` ПК: AES-256-GCM по
 * записах (AAD = id: шифротекст не переставити в інший рядок), ключі HKDF-SHA256
 * з секрету вузла з тією самою сіллю й `info`, відбитки — HMAC-SHA256.
 * Секрет телефона свій (лежить в EncryptedSharedPreferences), тож і відбитки
 * свої: на дріт вони не йдуть (SYNC.md, правило 4).
 */
class Seal(val dataKey: ByteArray, val fpKey: ByteArray) {

    fun seal(payload: JsonObject, aad: String, nonce: ByteArray = randomNonce()): String {
        val raw = payload.toString().toByteArray(Charsets.UTF_8)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return PREFIX + Base64.getEncoder().encodeToString(nonce + c.doFinal(raw))
    }

    fun open(token: String, aad: String): JsonObject {
        require(token.startsWith(PREFIX)) { "not a sealed memory payload" }
        val blob = Base64.getDecoder().decode(token.substring(PREFIX.length))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(dataKey, "AES"), GCMParameterSpec(128, blob, 0, 12))
        c.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val raw = c.doFinal(blob, 12, blob.size - 12)
        return Json.parseToJsonElement(raw.toString(Charsets.UTF_8)).jsonObject
    }

    fun fingerprint(vararg parts: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(fpKey, "HmacSHA256"))
        return mac.doFinal(parts.joinToString("\u001f").toByteArray(Charsets.UTF_8)).toHex().take(40)
    }

    companion object {
        private const val PREFIX = "g1:"
        private val SALT = "phantom-memory-v1".toByteArray()
        private val rnd = SecureRandom()

        fun randomNonce(): ByteArray = ByteArray(12).also { rnd.nextBytes(it) }

        /** `crypto.py::derive_keys`. */
        fun fromSecret(secret: String): Seal = Seal(
            hkdf(secret.toByteArray(Charsets.UTF_8), "phantom-os/memory/at-rest".toByteArray()),
            hkdf(secret.toByteArray(Charsets.UTF_8), "phantom-os/memory/fingerprint".toByteArray()),
        )

        /** HKDF-SHA256 (RFC 5869), 32 байти. */
        fun hkdf(ikm: ByteArray, info: ByteArray, salt: ByteArray = SALT): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            return mac.doFinal(info + byteArrayOf(1)).copyOf(32)
        }

        /** Зміст запису, що шифрується, — у тому самому порядку ключів, що `store.py::_write`. */
        fun contentPayload(it: MemoryItem): JsonObject = buildJsonObject {
            put("subject", it.subject)
            put("predicate", it.predicate)
            put("value", it.value)
            put("text", it.text)
            put("provenance", buildJsonObject {
                put("conversation_id", it.provenance.conversationId)
                put("message_id", it.provenance.messageId)
                put("ts", it.provenance.ts)
            })
        }

        fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
