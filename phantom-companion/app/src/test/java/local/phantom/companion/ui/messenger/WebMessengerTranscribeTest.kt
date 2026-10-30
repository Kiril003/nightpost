package local.phantom.companion.ui.messenger

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class WebMessengerTranscribeTest {

    private val seg = listOf("messenger", "voice", "transcribe")

    @Test
    fun `a clip comes back as its words`() = runTest {
        var decodedMime = ""
        val route = WebMessengerTranscribe(
            decode = { bytes, mime ->
                decodedMime = mime
                bytes
            },
            hear = { pcm -> if (pcm.contentEquals(byteArrayOf(1, 2, 3, 4))) "привіт" else null },
            hasEngine = { true },
        )
        assertTrue(route.claims(seg))
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, obj["status"]!!.jsonPrimitive.int)
        assertEquals("audio/webm", decodedMime)
        assertEquals("привіт", obj["data"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `no engine answers no_engine and never decodes`() = runTest {
        var decoded = false
        val route = WebMessengerTranscribe(
            decode = { _, _ ->
                decoded = true
                byteArrayOf(1, 2)
            },
            hear = { "words" },
            hasEngine = { false },
        )
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(503, obj["status"]!!.jsonPrimitive.int)
        assertEquals("no_engine", obj["code"]!!.jsonPrimitive.content)
        assertFalse(decoded)
    }

    @Test
    fun `too big refuses before decoding`() = runTest {
        var decoded = false
        val route = WebMessengerTranscribe(
            decode = { _, _ ->
                decoded = true
                byteArrayOf(1)
            },
            hear = { "words" },
            hasEngine = { true },
            maxBytes = 4,
        )
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4, 5))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(413, obj["status"]!!.jsonPrimitive.int)
        assertEquals("too_big", obj["code"]!!.jsonPrimitive.content)
        assertFalse(decoded)
    }

    @Test
    fun `bad base64 is bad_body`() = runTest {
        val route = WebMessengerTranscribe(
            decode = { _, _ -> byteArrayOf(1) },
            hear = { "words" },
            hasEngine = { true },
        )
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"***not_b64***"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(400, obj["status"]!!.jsonPrimitive.int)
        assertEquals("bad_body", obj["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an undecodable clip is 422 and never reaches the engine`() = runTest {
        var heard = false
        val route = WebMessengerTranscribe(
            decode = { _, _ -> null },
            hear = {
                heard = true
                "words"
            },
            hasEngine = { true },
        )
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(422, obj["status"]!!.jsonPrimitive.int)
        assertEquals("undecodable", obj["code"]!!.jsonPrimitive.content)
        assertFalse(heard)
    }

    @Test
    fun `a heard nothing clip answers empty words not invented text`() = runTest {
        val route = WebMessengerTranscribe(
            decode = { bytes, _ -> bytes },
            hear = { null },
            hasEngine = { true },
        )
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertTrue(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals("", obj["data"]!!.jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a job past fifteen seconds answers slow`() = runTest {
        val route = WebMessengerTranscribe(
            decode = { bytes, _ -> bytes },
            hear = {
                delay(20_000)
                "late"
            },
            hasEngine = { true },
        )
        val b64 = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val raw = route.handle("POST", seg, """{"mime":"audio/webm","data_b64":"$b64"}""")
        val obj = Json.parseToJsonElement(raw).jsonObject
        assertFalse(obj["ok"]!!.jsonPrimitive.boolean)
        assertEquals(504, obj["status"]!!.jsonPrimitive.int)
        assertEquals("slow", obj["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `only POST is answered`() = runTest {
        val route = WebMessengerTranscribe(
            decode = { bytes, _ -> bytes },
            hear = { "words" },
            hasEngine = { true },
        )
        val obj = Json.parseToJsonElement(route.handle("GET", seg, null)).jsonObject
        assertEquals(404, obj["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the bridge mounts the route`() {
        val candidates = listOf(
            File("src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"),
            File("app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"),
            File("phantom-companion/app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("WebMessengerBridge.kt not found from ${File(".").absolutePath}")
        val src = file.readText()
        assertTrue(src.contains("transcribeRoute.claims(seg)"))
    }
}
