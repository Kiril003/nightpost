package local.phantom.companion.core.net.intent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.peer.PeerIntent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Форма журналу для вебу — та, яку читає intentsApi.ts (IntentRow). */
class IntentViewTest {

    private fun record(op: Int, answer: AnswerRecord?, replyTo: Int = PeerIntent.Lane.replyTo(smsParts = 1, relay = true)) =
        IntentRecord(
            idHex = "0102030405060708", op = op, title = "Тривоги: kharkiv", createdMs = 10, deadlineMs = 20,
            counter = 1, prio = 3, replyTo = replyTo, frameB64 = "AA",
            tries = listOf(LaneTry("relay", 11, "L0"), LaneTry("sms", 12)),
            phase = if (answer == null) Phase.SENT else Phase.ANSWERED, answer = answer,
        )

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    @Test fun `поля й імена — як у вебі`() {
        val row = Json.parseToJsonElement(IntentView.page(listOf(record(PeerIntent.OP_ALERTS, null))))
            .jsonObject["intents"]!!.jsonArray.single().jsonObject
        assertEquals(
            setOf("id", "op", "title", "created_ms", "deadline_ms", "phase", "tries", "relay_allowed", "answer"),
            row.keys,
        )
        assertEquals("alerts", row["op"]!!.jsonPrimitive.content)
        assertEquals("SENT", row["phase"]!!.jsonPrimitive.content)
        assertEquals(listOf("relay", "sms"), row["tries"]!!.jsonArray.map { it.jsonObject["lane"]!!.jsonPrimitive.content })
        assertTrue(row["relay_allowed"]!!.jsonPrimitive.boolean)
    }

    @Test fun `тривоги розібрано в області, а мовчазне джерело лишається без «нічого немає»`() {
        val bitmap = (1 shl PeerIntent.OBLAST_ORDER.indexOf("kharkiv")) or (1 shl PeerIntent.OBLAST_ORDER.indexOf("sumy"))
        val body = byteArrayOf((bitmap ushr 24).toByte(), (bitmap ushr 16).toByte(), (bitmap ushr 8).toByte(), bitmap.toByte(), 1, 0, 11, 20)
        val ok = IntentView.row(record(PeerIntent.OP_ALERTS, AnswerRecord(1, 0, b64(body), "sms", 30))).answer!!
        assertEquals(listOf("kharkiv", "sumy"), ok.alerts!!.oblasts)
        assertTrue(ok.alerts!!.mine)
        assertEquals(11, ok.alerts!!.sinceMin)
        val silent = IntentView.row(record(PeerIntent.OP_ALERTS, AnswerRecord(1, PeerIntent.Status.SOURCE_SILENT, "", "sms", 30))).answer!!
        assertEquals(null, silent.alerts)
        assertEquals(PeerIntent.Status.SOURCE_SILENT, silent.status)
    }

    @Test fun `текст ШІ — як є, і веб бачить щабель`() {
        val text = "Притисніть рану."
        val json = IntentView.page(listOf(record(PeerIntent.OP_ASK, AnswerRecord(2, 0, b64(text.toByteArray()), "relay", 40))))
        val answer = Json.parseToJsonElement(json).jsonObject["intents"]!!.jsonArray.single().jsonObject["answer"]!!.jsonObject
        assertEquals(text, answer["text"]!!.jsonPrimitive.content)
        assertEquals(2, answer["rung"]!!.jsonPrimitive.int)
        assertEquals("relay", answer["lane"]!!.jsonPrimitive.content)
    }
}
