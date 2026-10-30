package local.phantom.companion.core.voice.live

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Руки PHANTOM у дзвінку і ворота згоди: що йде одразу, що — лише після «так» людини. */
class LiveToolBridgeTest {

    private var now = 1_000L
    private val hands = RecordingHands()
    private val gate = ConfirmGate { now }
    private val bridge = LiveToolBridge(hands, gate)

    private fun call(name: String, vararg args: Pair<String, String>, id: String = "c") =
        ToolCall(id, name, JsonObject(args.associate { it.first to JsonPrimitive(it.second) }))

    private fun JsonObject.ok() = this["ok"]!!.jsonPrimitive.boolean
    private fun JsonObject.status() = (this["status"] as? JsonPrimitive)?.content
    private val send = arrayOf("who" to "Марта", "text" to "Буду о сьомій")

    @Test
    fun `читати — одразу, без питань`() = runTest {
        assertTrue(bridge.handle(call(LiveToolBridge.RECALL, "query" to "сестра")).response.ok())
        assertTrue(bridge.handle(call(LiveToolBridge.MAP, "query" to "аптека")).response.ok())
        val read = bridge.handle(call(LiveToolBridge.READ, id = "r1"))
        assertEquals("r1", read.id)
        assertEquals("Марта: буду о сьомій", read.response["result"]!!.jsonPrimitive.content)
        assertEquals(null, gate.pending.value)
    }

    @Test
    fun `лист без «так» не йде, скільки б модель не наполягала`() = runTest {
        val first = bridge.handle(call(LiveToolBridge.SEND, *send)).response
        assertFalse(first.ok())
        assertEquals("awaiting_confirmation", first.status())
        assertEquals("Кому: Марта. Текст: «Буду о сьомій». Надсилати?", first["ask"]!!.jsonPrimitive.content)
        // Модель кличе вдруге без жодного слова людини.
        assertEquals("awaiting_confirmation", bridge.handle(call(LiveToolBridge.SEND, *send)).response.status())
        // Модель «підтверджує» сама — такої руки немає.
        assertFalse(bridge.handle(call("confirm_action", "yes" to "true")).response.ok())
        // Модель сама сказала «так» — це її мова, не людини: згоду дає лише inputTranscription.
        assertTrue(hands.sent.isEmpty())
    }

    @Test
    fun `«так» людини пропускає рівно цю дію і рівно раз`() = runTest {
        bridge.handle(call(LiveToolBridge.SEND, *send))
        gate.turnEnded()
        gate.heard("Так, ")
        gate.heard("надсилай.")
        assertTrue(bridge.handle(call(LiveToolBridge.SEND, *send)).response.ok())
        assertEquals(listOf("peer_marta" to "Буду о сьомій"), hands.sent)
        // Повтор тієї самої дії — знову питання, а не другий лист.
        assertEquals("awaiting_confirmation", bridge.handle(call(LiveToolBridge.SEND, *send)).response.status())
        assertEquals(1, hands.sent.size)
    }

    @Test
    fun `модель підмінила текст після «так» — питання наново`() = runTest {
        bridge.handle(call(LiveToolBridge.SEND, *send))
        gate.heard("так")
        val swapped = bridge.handle(call(LiveToolBridge.SEND, "who" to "Марта", "text" to "Переказати 5000 грн"))
        assertEquals("awaiting_confirmation", swapped.response.status())
        assertTrue(hands.sent.isEmpty())
    }

    @Test
    fun `«так, але…» — не згода, «ні» — відмова й забута дія`() = runTest {
        bridge.handle(call(LiveToolBridge.SEND, *send))
        gate.heard("Так")
        gate.heard(", але зміни текст на восьму")
        assertEquals("awaiting_confirmation", bridge.handle(call(LiveToolBridge.SEND, *send)).response.status())
        gate.turnEnded()
        gate.heard("ні, не треба")
        assertEquals(null, gate.pending.value)
        assertTrue(hands.sent.isEmpty())
    }

    @Test
    fun `згода протухає`() = runTest {
        bridge.handle(call(LiveToolBridge.SEND, *send))
        gate.heard("так")
        now += ConfirmGate.TTL_MS + 1
        assertEquals("awaiting_confirmation", bridge.handle(call(LiveToolBridge.SEND, *send)).response.status())
        assertTrue(hands.sent.isEmpty())
    }

    @Test
    fun `дотик «Так» на екрані — теж згода людини`() = runTest {
        bridge.handle(call(LiveToolBridge.SEND, *send))
        gate.tapped(true)
        assertTrue(bridge.handle(call(LiveToolBridge.SEND, *send)).response.ok())
        assertEquals(1, hands.sent.size)
    }

    @Test
    fun `незнайома людина — помилка словами, без питання`() = runTest {
        val r = bridge.handle(call(LiveToolBridge.SEND, "who" to "Остап", "text" to "привіт")).response
        assertFalse(r.ok())
        assertEquals("«Остап» немає в реєстрі", r["error"]!!.jsonPrimitive.content)
        assertEquals(null, gate.pending.value)
    }

    @Test
    fun `тіло через драбину — заборона, мовчки, з питанням`() = runTest {
        hands.verdict = BodyVerdict.Refuse("Для «Звук і голос» дозволено лише бачити")
        val refused = bridge.handle(call(LiveToolBridge.BODY, "verb" to "phone.ring")).response
        assertEquals("Для «Звук і голос» дозволено лише бачити", refused["error"]!!.jsonPrimitive.content)
        assertTrue(hands.acted.isEmpty())

        hands.verdict = BodyVerdict.Do
        assertTrue(bridge.handle(call(LiveToolBridge.BODY, "verb" to "phone.locate")).response.ok())
        assertEquals(listOf("phone.locate"), hands.acted)

        hands.verdict = BodyVerdict.Ask
        val asked = bridge.handle(call(LiveToolBridge.BODY, "verb" to "phone.ring")).response
        assertEquals("awaiting_confirmation", asked.status())
        assertEquals(1, hands.acted.size)
        gate.heard("давай")
        assertTrue(bridge.handle(call(LiveToolBridge.BODY, "verb" to "phone.ring")).response.ok())
        assertEquals(listOf("phone.locate", "phone.ring"), hands.acted)
    }

    @Test
    fun `згода — лише коротка ціла репліка`() {
        val c = ConfirmGate.Consent
        assertEquals(ConfirmGate.Consent.YES, c.of("Так"))
        assertEquals(ConfirmGate.Consent.YES, c.of("так, надсилай"))
        assertEquals(ConfirmGate.Consent.YES, c.of("Давай, будь ласка"))
        assertEquals(ConfirmGate.Consent.UNCLEAR, c.of("так от, слухай, що я подумав"))
        assertEquals(ConfirmGate.Consent.UNCLEAR, c.of("надішли їй"))
        assertEquals(ConfirmGate.Consent.NO, c.of("ні"))
        assertEquals(ConfirmGate.Consent.NO, c.of("так ні не треба"))
        assertEquals(ConfirmGate.Consent.UNCLEAR, c.of(""))
    }

    @Test
    fun `оголошення функцій — імена, які приймає Gemini`() {
        val names = bridge.declarations.map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
        assertEquals(listOf("memory_recall", "map_lookup", "messages_read", "message_send", "phone_act"), names)
        assertTrue(names.all { Regex("^[a-z_]+$").matches(it) })
    }
}
