package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ОДНА адреса для ПК і телефона — доведена спільним вектором.
 *
 * Той самий файл лежить у двох деревах (`phantom-os-beta-drop/src/backend/
 * tests/vectors/pc_pair_channel_vector.json`) і читається pytest'ом. Це
 * єдиний доказ, який ловить розходження ДО того, як воно стане мовчазним:
 * розійдись тут хоч байт солі, хоч алфавіт base64 — обидва боки далі чесно
 * рахують адреси, обидва чесно ходять у сховок, і обидва бачать порожньо.
 *
 * Вектор перевіряє те, що бачить сервер (адресу), і те, з чого вона
 * виводиться (ключ каналу й ключ сховка), бо збіг адреси при різних ключах
 * означав би, що зійшлась не арифметика, а випадковість.
 */
class PcPairChannelVectorTest {

    private val vector: JsonObject = run {
        val stream = checkNotNull(
            javaClass.classLoader?.getResourceAsStream(VECTOR),
        ) { "вектор $VECTOR не знайдено у test resources" }
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun text(vararg path: String): String {
        var node = vector
        for (i in 0 until path.size - 1) node = node[path[i]]!!.jsonObject
        return node[path.last()]!!.jsonPrimitive.content
    }

    private fun raw(vararg path: String): ByteArray =
        java.util.Base64.getDecoder().decode(text(*path))

    private val phoneId get() = text("phone", "peer_id")
    private val nodeId get() = text("node", "node_id")
    private val epoch get() = vector["epoch"]!!.jsonPrimitive.int.toLong()

    @Test fun `телефон виводить ключ каналу з вектора`() {
        val key = PeerChannel.derive(
            myPrivateKey = raw("phone", "x25519_priv_b64"),
            theirDhPubKeyB64 = text("node", "x25519_pub_b64"),
            myId = phoneId,
            theirId = nodeId,
        )
        assertNotNull("ключ каналу не вивівся з вектора", key)
        assertEquals(text("expected", "pair_key_b64"), b64(key!!))
    }

    @Test fun `бік вузла виводить ТОЙ САМИЙ ключ каналу`() {
        // Той самий виклик, лише з протилежними ролями: якби сіль залежала
        // від того, хто перший, ця перевірка була б єдиною, що це показала б.
        val key = PeerChannel.derive(
            myPrivateKey = raw("node", "x25519_priv_b64"),
            theirDhPubKeyB64 = text("phone", "x25519_pub_b64"),
            myId = nodeId,
            theirId = phoneId,
        )
        assertEquals(text("expected", "pair_key_b64"), b64(key!!))
    }

    @Test fun `ключ сховка збігається з вектором з обох боків`() {
        val pair = java.util.Base64.getDecoder().decode(text("expected", "pair_key_b64"))
        val fromPhone = PeerRelay.relayKey(pair, phoneId, nodeId)
        val fromNode = PeerRelay.relayKey(pair, nodeId, phoneId)
        assertEquals(text("expected", "relay_key_b64"), b64(fromPhone!!))
        assertEquals(text("expected", "relay_key_b64"), b64(fromNode!!))
    }

    @Test fun `адреса скриньки збігається з вектором`() {
        val relay = java.util.Base64.getDecoder().decode(text("expected", "relay_key_b64"))
        assertEquals(
            text("expected", "msg_tag_phone_to_node"),
            PeerRelay.msgTag(relay, phoneId, nodeId, epoch),
        )
        assertEquals(
            text("expected", "msg_tag_node_to_phone"),
            PeerRelay.msgTag(relay, nodeId, phoneId, epoch),
        )
        assertEquals(
            text("expected", "ack_tag_phone_to_node"),
            PeerRelay.ackTag(relay, phoneId, nodeId, epoch),
        )
    }

    @Test fun `лист і квитанція лежать у РІЗНИХ скриньках`() {
        // Однакові адреси тут означали б, що квитанція про лист перезаписує
        // сам лист — і другий бік дістав би підтвердження замість слова.
        val relay = java.util.Base64.getDecoder().decode(text("expected", "relay_key_b64"))
        val letter = PeerRelay.msgTag(relay, phoneId, nodeId, epoch)
        val receipt = PeerRelay.ackTag(relay, phoneId, nodeId, epoch)
        assertEquals(false, letter == receipt)
    }

    @Test fun `телефон відчиняє лист, який запечатав ПК`() {
        // Напрям, якого до 03.09.2026 не існувало: кадр складено Python'ом
        // (node/peer_letter.py) і мусить відчинитись ТИМИ САМИМИ дверима, що й
        // кадр із дроту, — інакше збіг адрес нічого не доставляє.
        val pair = java.util.Base64.getDecoder().decode(text("expected", "pair_key_b64"))
        val frame = text("letter_from_node", "frame")
        val message = PeerEnvelope.open(frame) { pair }
        assertNotNull("кадр ПК не відчинився ключем пари", message)
        assertEquals(text("letter_from_node", "id"), message!!.id)
        assertEquals(text("letter_from_node", "body"), message.body)
        assertEquals(nodeId, message.fromId)
        assertEquals(phoneId, message.toId)
    }

    @Test fun `підпис ПК перевіряється ключем, що приїхав у паринзі`() {
        val pair = java.util.Base64.getDecoder().decode(text("expected", "pair_key_b64"))
        val message = PeerEnvelope.open(text("letter_from_node", "frame")) { pair }!!
        val nodeKey = raw("node", "ed25519_pub_b64")
        assertEquals(true, PeerSealing.verify(message, nodeKey, expectedFromId = nodeId))
        // Ключ іншої сторони не має проходити: підпис доводить слова, реєстр —
        // кому належить ключ, і обидва мусять погодитись.
        assertEquals(false, PeerSealing.verify(message, raw("phone", "ed25519_pub_b64")))
    }

    @Test fun `квитанція за цей кадр збігається байт-у-байт`() {
        val pair = java.util.Base64.getDecoder().decode(text("expected", "pair_key_b64"))
        val message = PeerEnvelope.open(text("letter_from_node", "frame")) { pair }!!
        assertEquals(
            text("letter_from_node", "receipt_b64"),
            PeerReceipt.of(
                pair,
                message.id,
                message.fromId,
                message.toId,
                text("letter_from_node", "receipt_over_nonce_b64"),
            ),
        )
    }

    private fun b64(raw: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(raw)

    private companion object {
        const val VECTOR = "pc_pair_channel_vector.json"
    }
}
