package local.phantom.companion.ui.messenger

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.data.BodyLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class WebMessengerDevicesKeysTest {

    @Test
    fun `mapJournalRow maps stop_on status to what_key and keeps server text only as fallback`() {
        val raw = buildJsonObject {
            put("id", 42)
            put("at", "2026-10-17T12:00:00Z")
            put("verb", "symbiote.stop")
            put("status", "stop_on")
            put("what", "Усе зупинено")
            put("status_title", "Усе зупинено")
            put("detail", "Усе зупинено")
            put("direction", "to_pc")
            put("who", "Телефон")
        }

        val mapped = WebMessengerDevices.mapJournalRow(raw)

        assertEquals("status.stop_on", mapped["what_key"]?.jsonPrimitive?.contentOrNull)
        assertEquals("this_phone", mapped["who"]?.jsonPrimitive?.contentOrNull)
        assertEquals("Усе зупинено", mapped["what"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `pc device row reads stopped from symbiote feed even when phone resumed`() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val ladder = BodyLadder.of(app)
        ladder.resume()

        val link = object : WebMessengerDevices.Link {
            override fun paired() = WebMessengerDevices.Paired("Laptop", "peer-pc")
            override suspend fun get(path: String): WebMessengerDevices.Reply = when (path) {
                "/api/v1/symbiote/state" -> WebMessengerDevices.Reply(
                    200,
                    """{"bodies":[{"kind":"desktop","battery_pct":90,"charging":false}]}""",
                )
                "/api/v1/symbiote/feed" -> WebMessengerDevices.Reply(
                    200,
                    """{"stopped":true,"entries":[]}""",
                )
                else -> WebMessengerDevices.Reply(404, "{}")
            }

            override suspend fun post(path: String, body: JsonObject) = WebMessengerDevices.Reply(200, "{}")
        }

        val bridge = WebMessengerDevices(ladder = ladder, link = link)
        val raw = bridge.handle("GET", listOf("messenger", "devices"), null)
        val data = Json.parseToJsonElement(raw).jsonObject["data"]!!.jsonObject
        val pc = data["devices"]!!.jsonArray.first().jsonObject

        assertEquals(false, data["stopped"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(true, pc["stopped"]?.jsonPrimitive?.booleanOrNull)
        assertTrue(pc["online"]?.jsonPrimitive?.booleanOrNull == true)
    }
}
