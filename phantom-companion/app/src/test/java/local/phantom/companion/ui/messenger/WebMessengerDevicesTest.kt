package local.phantom.companion.ui.messenger

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.data.BodyLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Вікно «Мій пристрій» на телефоні: той самий контракт, що й на ПК.
 * Сходинки телефона міняються тут; сходинки ПК лише видно.
 */
class WebMessengerDevicesTest {

    private class Shelf : BodyLadder.Shelf {
        val map = HashMap<String, String>()
        override fun read(key: String) = map[key]
        override fun write(key: String, value: String) {
            map[key] = value
        }
    }

    private class FakePc(var reachable: Boolean = true) : WebMessengerDevices.Link {
        var paired: WebMessengerDevices.Paired? = WebMessengerDevices.Paired("Ноутбук", "node-pc")
        val posts = mutableListOf<Pair<String, JsonObject>>()
        var commandReply = WebMessengerDevices.Reply(200, """{"result":{"ok":true,"detail":"","payload":{}}}""")
        var state = """{"bodies":[{"kind":"desktop","battery_pct":81,"charging":true}],"stopped":false}"""
        var ladder = """{"devices":[{"ladder":[
            {"sphere":"place","level":"see"},{"sphere":"clipboard","level":"ask"},
            {"sphere":"keyboard","level":"see"},{"sphere":"screen","level":"off"},{"sphere":"notices","level":"see"}]}]}"""
        var journal = """{"entries":[
            {"id":7,"at":"2026-09-27T17:40:12Z","what":"Відкрий посилання","direction":"to_pc","status":"done","status_title":"Зроблено","detail":"Відкрито","verb":"pc.open"},
            {"id":6,"at":"2026-09-27T17:00:00Z","what":"Знайти телефон","direction":"to_phone","status_title":"Передано","detail":""}]}"""

        override fun paired() = paired
        override suspend fun get(path: String): WebMessengerDevices.Reply = when {
            !reachable -> WebMessengerDevices.Reply(0, "")
            path.startsWith("/api/v1/symbiote/state") -> WebMessengerDevices.Reply(200, state)
            path.startsWith("/api/v1/symbiote/ladder") -> WebMessengerDevices.Reply(200, ladder)
            path.startsWith("/api/v1/symbiote/journal") -> WebMessengerDevices.Reply(200, journal)
            else -> WebMessengerDevices.Reply(404, "")
        }
        override suspend fun post(path: String, body: JsonObject): WebMessengerDevices.Reply {
            posts += path to body
            if (!reachable) return WebMessengerDevices.Reply(0, "")
            return if (path.endsWith("/command")) commandReply else WebMessengerDevices.Reply(200, "{}")
        }
    }

    private val shelf = Shelf()
    private val ladder = BodyLadder(shelf)
    private val pc = FakePc()
    private val route = WebMessengerDevices(ladder, pc) { 1_790_000_000_000L }

    private fun call(method: String, path: String, body: String? = null): JsonObject {
        val seg = path.trim('/').split('/')
        assertTrue(route.claims(seg))
        return Json.parseToJsonElement(kotlinx.coroutines.runBlocking { route.handle(method, seg, body) }).jsonObject
    }

    private fun data(method: String, path: String, body: String? = null): JsonObject {
        val out = call(method, path, body)
        assertEquals(out.toString(), 200, out["status"]!!.jsonPrimitive.int)
        return out["data"]!!.jsonObject
    }

    @Test
    fun `живий ПК — на звʼязку, із зарядом і імʼям контакту`() {
        val row = data("GET", "/messenger/devices")["devices"]!!.jsonArray.single().jsonObject
        assertEquals("pc", row["id"]!!.jsonPrimitive.content)
        assertEquals("Ноутбук", row["name"]!!.jsonPrimitive.content)
        assertEquals("node-pc", row["peer_id"]!!.jsonPrimitive.content)
        assertTrue(row["online"]!!.jsonPrimitive.boolean)
        assertEquals(81, row["battery_pct"]!!.jsonPrimitive.int)
        assertEquals("2026-09-21T14:13:20Z", row["seen_at"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ПК мовчить — ні заряду, ні «був», нічого вигаданого`() {
        pc.reachable = false
        val row = data("GET", "/messenger/devices")["devices"]!!.jsonArray.single().jsonObject
        assertFalse(row["online"]!!.jsonPrimitive.boolean)
        assertNull(row["battery_pct"])
        assertNull(row["seen_at"])
        assertNull(row["charging"])
    }

    @Test
    fun `не спарений — список порожній, а вікно пристрою не існує`() {
        pc.paired = null
        assertTrue(data("GET", "/messenger/devices")["devices"]!!.jsonArray.isEmpty())
        assertEquals(404, call("GET", "/messenger/devices/pc/ladder")["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `посилання їде на ПК як url, «так» на ПК — людськими словами`() {
        pc.commandReply = WebMessengerDevices.Reply(
            200, """{"result":{"ok":false,"payload":{"status":"asked","journal_id":"5"}}}""",
        )
        val out = data("POST", "/messenger/devices/pc/act", """{"verb":"pc.open","text":"https://example.org"}""")
        val (path, body) = pc.posts.single()
        assertEquals("/api/v1/symbiote/command", path)
        assertEquals("pc.open", body["verb"]!!.jsonPrimitive.content)
        assertEquals("https://example.org", body["args"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("asked", out["state"]!!.jsonPrimitive.content)
        assertEquals("", out["said"]!!.jsonPrimitive.content)
    }

    @Test
    fun `відмова драбини ПК приходить його ж словами, 403 на екран не йде`() {
        pc.commandReply = WebMessengerDevices.Reply(
            403, """{"detail":{"code":"ladder_denied","message":"Для «Екран» доступ вимкнено"}}""",
        )
        val out = data("POST", "/messenger/devices/pc/act", """{"verb":"pc.lock"}""")
        assertEquals("refused", out["state"]!!.jsonPrimitive.content)
        assertEquals("Для «Екран» доступ вимкнено", out["said"]!!.jsonPrimitive.content)
    }

    @Test
    fun `без тексту буфер не питає ПК, а чуже дієслово — відмова`() {
        val out = data("POST", "/messenger/devices/pc/act", """{"verb":"pc.clipboard","text":"  "}""")
        assertEquals("empty", out["state"]!!.jsonPrimitive.content)
        assertEquals(400, call("POST", "/messenger/devices/pc/act", """{"verb":"pc.type","text":"x"}""")["status"]!!.jsonPrimitive.int)
        assertTrue(pc.posts.isEmpty())
    }

    @Test
    fun `ПК не на звʼязку — так і сказано`() {
        pc.reachable = false
        val out = data("POST", "/messenger/devices/pc/act", """{"verb":"pc.lock"}""")
        assertEquals("offline", out["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `сходинки телефона міняються тут, сходинки ПК лише видно`() {
        val got = data("GET", "/messenger/devices/pc/ladder")
        val here = got["here"]!!.jsonObject
        assertTrue(here["editable"]!!.jsonPrimitive.boolean)
        assertEquals(
            listOf("place", "sound", "notices", "screen", "keyboard", "clipboard"),
            here["spheres"]!!.jsonArray.map { it.jsonObject["sphere"]!!.jsonPrimitive.content },
        )
        val there = got["there"]!!.jsonObject
        assertFalse(there["editable"]!!.jsonPrimitive.boolean)
        val levels = there["spheres"]!!.jsonArray.associate {
            it.jsonObject["sphere"]!!.jsonPrimitive.content to it.jsonObject["level"]!!.jsonPrimitive.content
        }
        assertEquals(mapOf("notices" to "see", "clipboard" to "ask", "screen" to "off", "keyboard" to "see"), levels)
        assertTrue(
            there["spheres"]!!.jsonArray.single { it.jsonObject["sphere"]!!.jsonPrimitive.content == "keyboard" }
                .jsonObject["irreversible"]!!.jsonPrimitive.boolean,
        )

        val after = data("PATCH", "/messenger/devices/pc/ladder", """{"sphere":"sound","level":"ask"}""")
        assertEquals(BodyLadder.Rung.ASK, ladder.level(BodyLadder.Sphere.SOUND))
        assertEquals(
            "ask",
            after["here"]!!.jsonObject["spheres"]!!.jsonArray
                .single { it.jsonObject["sphere"]!!.jsonPrimitive.content == "sound" }
                .jsonObject["level"]!!.jsonPrimitive.content,
        )
        // Клавіатура тепер і телефонна сфера, але незворотна: вище за «так» не стає.
        data("PATCH", "/messenger/devices/pc/ladder", """{"sphere":"keyboard","level":"silent"}""")
        assertEquals(BodyLadder.Rung.ASK, ladder.level(BodyLadder.Sphere.KEYBOARD))
        assertEquals(400, call("PATCH", "/messenger/devices/pc/ladder", """{"sphere":"nope","level":"see"}""")["status"]!!.jsonPrimitive.int)
    }

    @Test
    fun `ПК мовчить — сходинок ПК немає, а свої є`() {
        pc.reachable = false
        val got = data("GET", "/messenger/devices/pc/ladder")
        assertEquals(JsonNull, got["there"])
        assertEquals(6, got["here"]!!.jsonObject["spheres"]!!.jsonArray.size)
    }

    @Test
    fun `журнал — людськими рядками, «хто» з боку телефона, без службових слів`() {
        val entries = data("GET", "/messenger/devices/pc/journal")["entries"]!!.jsonArray.map { it.jsonObject }
        assertEquals("this_phone", entries[0]["who"]!!.jsonPrimitive.content)
        assertEquals("pc", entries[1]["who"]!!.jsonPrimitive.content)
        assertEquals("pc.open", entries[0]["verb"]!!.jsonPrimitive.content)
        assertEquals("done", entries[0]["status"]!!.jsonPrimitive.content)
        assertNull(entries[0]["direction"])
    }

    @Test
    fun `«Зупинити все» діє тут і просить ПК теж, відновлення — лише тут`() {
        assertTrue(data("POST", "/messenger/devices/pc/stop", """{"on":true}""")["stopped"]!!.jsonPrimitive.boolean)
        assertTrue(ladder.stopped.value)
        assertEquals("/api/v1/symbiote/stop", pc.posts.single().first)
        assertTrue(data("GET", "/messenger/devices")["stopped"]!!.jsonPrimitive.boolean)

        pc.reachable = false
        assertFalse(data("POST", "/messenger/devices/pc/stop", """{"on":false}""")["stopped"]!!.jsonPrimitive.boolean)
        assertFalse(ladder.stopped.value)
        assertEquals(1, pc.posts.size)
    }

    @Test
    fun `кожне дієслово й стан, що міст пропускає в журнал, мають слова в спільній таблиці`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        val table = File(root.parentFile, "phantom-os/src/shared/deviceWords.json")
        assertTrue("немає ${table.path}", table.isFile)
        val words = Json.parseToJsonElement(table.readText()).jsonObject
        val verbs = words["verbs"]!!.jsonObject
        val verbsHere = BodyLadder.RULES.keys + WebMessengerDevices.ACTS + setOf("pc.type", "symbiote.ladder", "symbiote.stop")
        verbsHere.forEach { verb ->
            val w = verbs[verb]?.jsonObject ?: fail("немає слів для $verb") as Nothing
            assertTrue(w["done"]!!.jsonPrimitive.content.isNotBlank())
            assertTrue(w["tried"]!!.jsonPrimitive.content.isNotBlank())
        }
        val statuses = words["statuses"]!!.jsonObject.keys
        listOf("refused", "asked", "suggested", "declined", "stopped", "done", "failed", "sent").forEach {
            assertTrue("немає стану $it", it in statuses)
        }
        // Стан відповіді на дію й «хто» в журналі міст віддає кодами — слово для кожного є в таблиці.
        val acts = words["acts"]!!.jsonObject
        listOf("asked", "suggested", "done", "failed", "refused", "stopped", "offline", "empty").forEach {
            assertTrue("немає слова для стану дії $it", acts[it]?.jsonPrimitive?.content?.isNotBlank() == true)
        }
        val who = words["who"]!!.jsonObject
        listOf("this_phone", "pc").forEach { assertTrue("немає слова для «хто» $it", it in who) }
    }

    @Test
    fun `міст справді веде «devices» сюди`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = dir ?: fail("корінь дерева не знайдено") as Nothing
        val bridge = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue(bridge.contains("WebMessengerDevices.of(container)"))
        assertTrue(bridge.contains("if (ownDevices.claims(seg)) return ownDevices.handle(method, seg, body)"))
    }
}
