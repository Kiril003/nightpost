package local.phantom.companion.core.net.peer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.core.net.crypto.b64ToBytes
import local.phantom.companion.core.net.crypto.bytesToB64
import local.phantom.companion.core.net.crypto.bytesToHex
import local.phantom.companion.core.net.crypto.hexToBytes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Мова Наміру — на тому самому векторі, який рахує ПК
 * (`phantom-os/src/backend/scripts/intent_wire_vector.py`, звіряє
 * `tests/test_intents_wire_vector.py`).
 *
 * Розбіжність тут не дає помилки на апараті: ПК мовчки не відчинить Намір
 * (для нього це «чужий кадр»), а телефон — відповідь.
 */
class PeerIntentVectorTest {

    private val v: JsonObject = run {
        val stream = checkNotNull(javaClass.classLoader?.getResourceAsStream(VECTOR)) {
            "вектор $VECTOR не знайдено у test resources"
        }
        Json.parseToJsonElement(stream.reader(Charsets.UTF_8).readText()).jsonObject
    }

    private fun s(key: String, node: JsonObject = v) = node[key]!!.jsonPrimitive.content
    private val pairKey get() = b64ToBytes(s("pair_key_b64"))
    private val phone get() = s("phone_id")
    private val pc get() = s("pc_id")
    private val cases get() = v["cases"]!!.jsonArray.map { it.jsonObject }

    private fun intentOf(c: JsonObject) = PeerIntent.Intent(
        id = hexToBytes(s("id_hex", c)),
        op = c["op"]!!.jsonPrimitive.int,
        createdMin = c["created_min"]!!.jsonPrimitive.int,
        deadlineMin = c["deadline_min"]!!.jsonPrimitive.int,
        flags = c["flags"]!!.jsonPrimitive.int,
        replyTo = c["reply_to"]!!.jsonPrimitive.int,
        args = hexToBytes(s("args_hex", c)),
    )

    @Test fun `епоха, підказка пари, ключ Наміру й тег контакту — як на ПК`() {
        assertEquals(v["epoch_ms"]!!.jsonPrimitive.long, PeerIntent.EPOCH_MS)
        assertEquals(s("pair_hint_hex"), bytesToHex(PeerIntent.pairHint(phone, pc)))
        assertEquals(s("intent_key_b64"), bytesToB64(PeerIntent.intentKey(pairKey, phone, pc)))
        val tag = v["contact_tag"]!!.jsonObject
        assertEquals(s("tag_hex", tag), bytesToHex(PeerIntent.contactTag(pairKey, s("peer_node_id", tag))))
    }

    @Test fun `reply_to, бюджети, коди стану й порядок областей — ті самі`() {
        val r = v["reply_to"]!!.jsonObject
        assertEquals(r["sms1"]!!.jsonPrimitive.int, PeerIntent.Lane.replyTo(smsParts = 1))
        assertEquals(r["sms3_relay"]!!.jsonPrimitive.int, PeerIntent.Lane.replyTo(smsParts = 3, relay = true))
        assertEquals(r["relay_lan"]!!.jsonPrimitive.int, PeerIntent.Lane.replyTo(relay = true, lan = true))
        val budgets = v["budgets"]!!.jsonObject
        val sms = budgets["sms"]!!.jsonObject.mapKeys { it.key.toInt() }.mapValues { it.value.jsonPrimitive.int }
        assertEquals(sms, PeerIntent.Lane.SMS_BUDGET)
        assertEquals(budgets["relay"]!!.jsonPrimitive.int, PeerIntent.Lane.RELAY_BUDGET)
        assertEquals(v["oblast_order"]!!.jsonArray.map { it.jsonPrimitive.content }, PeerIntent.OBLAST_ORDER)
        val st = v["status"]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        assertEquals(
            mapOf(
                "OK" to PeerIntent.Status.OK, "SOURCE_SILENT" to PeerIntent.Status.SOURCE_SILENT,
                "NOT_FOUND" to PeerIntent.Status.NOT_FOUND, "REFUSED" to PeerIntent.Status.REFUSED,
                "TIMEOUT" to PeerIntent.Status.TIMEOUT, "UNSUPPORTED" to PeerIntent.Status.UNSUPPORTED,
                "EXPIRED" to PeerIntent.Status.EXPIRED, "FAILED" to PeerIntent.Status.FAILED,
            ),
            st,
        )
    }

    @Test fun `телефон запечатує Намір байт у байт як ПК`() {
        for (c in cases) {
            val intent = intentOf(c)
            assertEquals(s("plain_hex", c), bytesToHex(intent.toBytes()))
            val sealed = PeerIntent.sealIntent(intent, pairKey, phone, pc, c["counter"]!!.jsonPrimitive.long)
            assertEquals(s("sealed_b64", c), bytesToB64(sealed))
            val back = PeerIntent.openIntent(sealed, pairKey, phone, pc)
            assertNotNull(back)
            assertArrayEquals(intent.toBytes(), back!!.toBytes())
        }
    }

    @Test fun `телефон відчиняє обидва щаблі відповіді ПК`() {
        for (c in cases) {
            val id = hexToBytes(s("id_hex", c))
            for ((rung, a) in c["answer"]!!.jsonObject) {
                val ans = a.jsonObject
                val kind = ans["kind"]!!.jsonPrimitive.int
                assertEquals(s("key_b64", ans), bytesToB64(PeerIntent.answerKey(pairKey, phone, pc, id, kind)))
                val frame = b64ToBytes(s("sealed_b64", ans))
                val opened = PeerIntent.openAnswer(frame, pairKey, phone, pc, id)
                assertNotNull("щабель $rung не відчинився", opened)
                val plain = hexToBytes(s("plain_hex", ans))
                assertEquals(rung.toInt(), opened!!.rung)
                assertEquals(plain[0].toInt() and 0x0F, opened.status)
                assertArrayEquals(plain.copyOfRange(1, plain.size), opened.body)
                assertEquals(c["counter"]!!.jsonPrimitive.long, opened.counter)
                // І в зворотний бік: ПК-половина тут дає ті самі байти.
                val resealed = PeerIntent.sealAnswer(
                    plain, pairKey, phone, pc, id, c["counter"]!!.jsonPrimitive.long, rung.toInt(),
                )
                assertEquals(s("sealed_b64", ans), bytesToB64(resealed))
            }
        }
    }

    @Test fun `відповідь тривог і обрізаний текст розбираються`() {
        val alerts = cases.first { it["op"]!!.jsonPrimitive.int == PeerIntent.OP_ALERTS }
        val body = hexToBytes(s("plain_hex", alerts["answer"]!!.jsonObject["1"]!!.jsonObject)).let {
            it.copyOfRange(1, it.size)
        }
        val decoded = PeerIntent.decodeAlerts(body)!!
        assertEquals(setOf("kharkiv", "sumy"), decoded.oblasts)
        assertEquals(1, decoded.myType)
        assertEquals(11, decoded.sinceMin)
        assertEquals(20, decoded.ageS)

        val ask = cases.first { it["op"]!!.jsonPrimitive.int == PeerIntent.OP_ASK }
        val sms = hexToBytes(s("plain_hex", ask["answer"]!!.jsonObject["1"]!!.jsonObject))
        assertTrue(sms.size <= PeerIntent.Lane.SMS_BUDGET.getValue(1))
        assertTrue(String(sms.copyOfRange(1, sms.size), Charsets.UTF_8).endsWith("…"))
    }

    @Test fun `чужий ключ, чужий Намір і зіпсований байт — null, а не виняток`() {
        val c = cases.first()
        val id = hexToBytes(s("id_hex", c))
        val sealed = b64ToBytes(s("sealed_b64", c))
        assertNull(PeerIntent.openIntent(sealed, ByteArray(32) { 7 }, phone, pc))
        val tampered = sealed.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertNull(PeerIntent.openIntent(tampered, pairKey, phone, pc))
        val answer = b64ToBytes(s("sealed_b64", c["answer"]!!.jsonObject["1"]!!.jsonObject))
        assertNull(PeerIntent.openAnswer(answer, pairKey, phone, pc, ByteArray(8)))
        assertNull(PeerIntent.openAnswer(sealed, pairKey, phone, pc, id))
        assertNull(PeerIntent.openAnswer(ByteArray(3), pairKey, phone, pc, id))
    }

    @Test fun `кадр сховком — службовий, у розмову не потрапляє`() {
        val sealed = b64ToBytes(s("sealed_b64", cases.first()))
        val body = PeerIntent.encodeIntent(sealed)
        assertFalse(body.endsWith("="))
        assertEquals(PeerFrames.Kind.INTENT, PeerFrames.kindOf(body))
        val answer = PeerIntent.encodeAnswer(sealed)
        assertArrayEquals(sealed, PeerIntent.answerFrame(answer))
        val letter = PeerMessage(id = "m", fromId = pc, toId = phone, body = answer, sentAtMs = 1L)
        assertFalse(PeerOutbox.isSpoken(letter))
        assertNull(PeerIntent.answerFrame("PH-ANS:!!!"))
    }

    private companion object {
        const val VECTOR = "intent_wire_vector.json"
    }
}
