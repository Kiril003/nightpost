package local.phantom.companion.ui.messenger

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import local.phantom.companion.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WebMessengerIceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val servers = mutableListOf<ServerSocket>()
    private val hits = AtomicInteger()
    private var clock = 1_700_000_000_000L

    @After
    fun stop() = servers.forEach { runCatching { it.close() } }

    private val srvAnswer = """
        {"iceServers":[
          {"urls":["stun:ice.test:3478"]},
          {"urls":["turn:ice.test:3478?transport=udp","turn:ice.test:3478?transport=tcp"],
           "username":"1700003600","credential":"c2VydmVyLW1pbnRlZA=="}
        ],"turn":true,"ttl":3600}
    """.trimIndent()

    private fun fakeServer(
        status: Int = 200,
        body: String = srvAnswer,
        hang: Boolean = false,
        port: Int = 0,
    ): String {
        val server = ServerSocket(port, 0, InetAddress.getByName("127.0.0.1"))
        servers += server
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread { runCatching { client.use { serve(it, status, body, hang) } } }.apply { isDaemon = true }.start()
            }
        }.apply { isDaemon = true }.start()
        return "http://127.0.0.1:${server.localPort}"
    }

    private fun serve(client: Socket, status: Int, body: String, hang: Boolean) {
        val reader = client.getInputStream().bufferedReader()
        val first = reader.readLine() ?: return
        while (true) if ((reader.readLine() ?: return).isEmpty()) break
        if (!first.startsWith("GET /api/ice ")) return
        hits.incrementAndGet()
        if (hang) {
            Thread.sleep(10_000)
            return
        }
        val bytes = body.toByteArray()
        client.getOutputStream().apply {
            write("HTTP/1.1 $status X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
            write(bytes)
            flush()
        }
    }

    /** Порт, на якому зараз нічого не слухає. */
    private fun freePort(): Int {
        val s = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
        val port = s.localPort
        s.close()
        return port
    }

    private fun closedPort(): String = "http://127.0.0.1:${freePort()}"

    private fun ice(baseUrl: String, files: File = tmp.newFolder(), timeoutMs: Long = 5_000) = WebMessengerIce(
        filesDir = files,
        baseUrl = baseUrl,
        inbound = { true },
        say = { id, arg -> "say:$id:${arg ?: ""}" },
        nowMs = { clock },
        timeoutMs = timeoutMs,
    )

    private fun data(ice: WebMessengerIce): JsonObject {
        val envelope = Json.parseToJsonElement(runBlocking { ice.answer() }).jsonObject
        assertEquals(200L, envelope["status"]!!.jsonPrimitive.long)
        return envelope["data"]!!.jsonObject
    }

    private fun urls(data: JsonObject): List<String> = data["iceServers"]!!.jsonArray.flatMap { s ->
        s.jsonObject["urls"]!!.jsonArray.map { it.jsonPrimitive.content }
    }

    @Test
    fun `без turn json телефон бере ICE з сервера`() {
        val d = data(ice(fakeServer()))
        assertEquals(
            listOf("stun:ice.test:3478", "turn:ice.test:3478?transport=udp", "turn:ice.test:3478?transport=tcp"),
            urls(d),
        )
        assertTrue(d["turn"]!!.jsonPrimitive.boolean)
        assertEquals(3600L, d["ttl"]!!.jsonPrimitive.long)
        assertTrue(d["inbound"]!!.jsonPrimitive.boolean)
        assertEquals("ice.test", d["relay_host"]!!.jsonPrimitive.content)
        val turn = d["iceServers"]!!.jsonArray[1].jsonObject
        assertEquals("1700003600", turn["username"]!!.jsonPrimitive.content)
        assertEquals("c2VydmVyLW1pbnRlZA==", turn["credential"]!!.jsonPrimitive.content)
        assertEquals(1, hits.get())
    }

    @Test
    fun `сервер лежить — порожній перелік і названа причина`() {
        val http = data(ice(fakeServer(status = 404)))
        assertEquals(JsonArray(emptyList()), http["iceServers"])
        assertFalse(http["turn"]!!.jsonPrimitive.boolean)
        assertEquals(0L, http["ttl"]!!.jsonPrimitive.long)
        assertEquals("say:${R.string.msgr_bridge_ice_http}:404", http["detail"]!!.jsonPrimitive.content)

        val connect = data(ice(closedPort()))
        assertEquals(JsonArray(emptyList()), connect["iceServers"])
        assertTrue(
            connect["detail"]!!.jsonPrimitive.content.startsWith("say:${R.string.msgr_bridge_ice_connect}:"),
        )
    }

    @Test
    fun `сервер мовчить — відповідь не чекає довше за межу`() {
        val url = fakeServer(hang = true)
        val started = System.nanoTime()
        val d = data(ice(url, timeoutMs = 400))
        val tookMs = (System.nanoTime() - started) / 1_000_000
        assertTrue("чекав $tookMs мс", tookMs < 2_000)
        assertEquals(JsonArray(emptyList()), d["iceServers"])
        assertEquals("say:${R.string.msgr_bridge_ice_timeout}:$url", d["detail"]!!.jsonPrimitive.content)
        assertEquals(1, hits.get())
    }

    @Test
    fun `слово secret не виходить у веб ні з turn json, ні з сервера`() {
        val files = tmp.newFolder()
        File(files, "turn.json").writeText("""{"host":"relay.test","port":3478,"secret":"s3cr3t-value","ttl":600}""")
        val own = runBlocking { ice(fakeServer(), files).answer() }
        assertFalse(own, own.contains("secret") || own.contains("s3cr3t"))

        val leaky = """{"iceServers":[{"urls":["turn:ice.test:3478"],"username":"u","credential":"c","secret":"x"}],
            "turn":true,"ttl":60,"secret":"x","turn_secret":"x"}"""
        val fromServer = runBlocking { ice(fakeServer(body = leaky)).answer() }
        assertFalse(fromServer, fromServer.contains("secret"))
        assertTrue(fromServer, fromServer.contains("turn:ice.test:3478"))
    }

    @Test
    fun `turn json лишається першим і сервер не питається`() {
        val files = tmp.newFolder()
        File(files, "turn.json").writeText("""{"host":"relay.test","port":3479,"secret":"k","ttl":600}""")
        val d = data(ice(fakeServer(), files))
        assertEquals(0, hits.get())
        assertTrue(d["turn"]!!.jsonPrimitive.boolean)
        assertEquals("relay.test", d["relay_host"]!!.jsonPrimitive.content)
        val turn = d["iceServers"]!!.jsonArray[1].jsonObject
        val username = (clock / 1000 + 600).toString()
        assertEquals(username, turn["username"]!!.jsonPrimitive.content)
        assertEquals(WebMessengerIce.hmacSha1("k", username), turn["credential"]!!.jsonPrimitive.content)
    }

    @Test
    fun `видана пара TURN тримається пів життя, потім перепитується`() {
        val ice = ice(fakeServer())
        data(ice)
        clock += 1_799_000
        data(ice)
        assertEquals(1, hits.get())
        clock += 2_000
        data(ice)
        assertEquals(2, hits.get())
    }

    // 30-хвилинний блок вхідних дзвінків на холодному старті
    // (NO_INBOUND_RETRY_MS у iceRoads.ts перепитує веб кожні 2 с саме через цю
    // гонку; якщо телефон заморожує `inbound` у тому самому кеші, що й TURN,
    // відповідь на ці перепитування лишається брехнею на весь час життя пари).
    @Test
    fun `inbound читається заново навіть коли TURN-пара ще в кеші`() {
        var listening = true
        val ice = WebMessengerIce(
            filesDir = tmp.newFolder(),
            baseUrl = fakeServer(),
            inbound = { listening },
            say = { id, arg -> "say:$id:${arg ?: ""}" },
            nowMs = { clock },
            timeoutMs = 5_000,
        )
        assertTrue(data(ice)["inbound"]!!.jsonPrimitive.boolean)
        listening = false
        assertFalse(data(ice)["inbound"]!!.jsonPrimitive.boolean)
        // TURN-пара й досі з кешу — мережа питалась рівно раз.
        assertEquals(1, hits.get())
    }

    // Fly-ретранслятор auto-stop і перша відповідь після сну довша за кілька
    // секунд: без короткої негативної полиці кожен виклик (сервер і веб
    // перепитує кожні 2 с, поки inbound==false) заново б'є сплячу машину.
    @Test
    fun `відмова сервера кешується коротко, а не на кожен виклик`() {
        val ice = ice(fakeServer(status = 404))
        data(ice)
        assertEquals(1, hits.get())
        clock += 29_000
        data(ice)
        assertEquals(1, hits.get())
        clock += 2_000
        data(ice)
        assertEquals(2, hits.get())
    }

    @Test
    fun `TURN без пари ключів не їде у веб, STUN лишається`() {
        val bare = """{"iceServers":[{"urls":"stun:ice.test:3478"},{"urls":["turn:ice.test:3478"]},
            {"urls":["http://evil.test/x"]}],"turn":true,"ttl":3600}"""
        val d = data(ice(fakeServer(body = bare)))
        assertEquals(listOf("stun:ice.test:3478"), urls(d))
        assertFalse(d["turn"]!!.jsonPrimitive.boolean)
        // relay_host їде лише з TURN-запису: тут його немає, і STUN-хост не
        // підміняє його — бридж завжди слав relay_host разом із TURN.
        assertNull(d["relay_host"])
    }

    @Test
    fun `причина сервера доїжджає, коли TURN немає`() {
        val body = """{"iceServers":[{"urls":["stun:ice.test:3478"]}],"turn":false,"ttl":60,
            "detail":"немає вільних TURN"}"""
        val d = data(ice(fakeServer(body = body)))
        assertEquals("немає вільних TURN", d["detail"]!!.jsonPrimitive.content)
    }

    @Test
    fun `адреси сервера в збірці немає — чесна порожнеча`() {
        val d = data(ice(""))
        assertEquals(JsonArray(emptyList()), d["iceServers"])
        assertFalse(d["turn"]!!.jsonPrimitive.boolean)
        // Причина — «у збірці немає адреси вузла», а не «немає turn.json»:
        // хмарна чи магазинна збірка без LICENSE_BASE_URL інакше слала б людині
        // чужу причину, яку вона не може виправити.
        assertEquals("say:${R.string.msgr_bridge_no_license_host}:", d["detail"]!!.jsonPrimitive.content)
        assertEquals(0, hits.get())
    }

    // Критик: телефон віддавав кешовану відповідь з тим самим `ttl`, з яким її
    // взяв, а веб тримає її ще пів цього ttl від МИТІ ОТРИМАННЯ — вік пари
    // складався двічі, і пара доходила до RTCPeerConnection на межі спливу.
    @Test
    fun `кеш телефона віддає залишок життя пари, а не початковий ttl`() {
        val ice = ice(fakeServer())
        assertEquals(3600L, data(ice)["ttl"]!!.jsonPrimitive.long)
        clock += 1_000_000 // 1000 с: пара ще з кешу (межа — 1800 с)
        val cached = data(ice)
        assertEquals(1, hits.get())
        assertEquals(2600L, cached["ttl"]!!.jsonPrimitive.long)
        // username пари — той самий: кеш не вигадує нову.
        assertEquals(
            "1700003600",
            cached["iceServers"]!!.jsonArray[1].jsonObject["username"]!!.jsonPrimitive.content,
        )
    }

    // SRV видає пару на добу: заявлений `ttl` — його власна довжина життя,
    // а username — справжній момент спливу. Якщо вони розходяться, правда в
    // username: так веб не візьме на половину доби пару, що спливає за годину.
    @Test
    fun `ttl не довший за те, що лишилось до спливу username`() {
        val shortLived = srvAnswer.replace(""""ttl":3600""", """"ttl":86400""")
        val d = data(ice(fakeServer(body = shortLived)))
        assertEquals(3600L, d["ttl"]!!.jsonPrimitive.long)
    }

    // Годинник телефона відстає: «до спливу ще десять днів» — вигадка про
    // пару, видану на годину; заявленого ttl не перевищуємо ніколи.
    @Test
    fun `відстаючий годинник телефона не подовжує пару понад заявлений ttl`() {
        val tenDays = (clock / 1000 + 864_000).toString()
        val skewed = srvAnswer.replace("1700003600", tenDays)
        val d = data(ice(fakeServer(body = skewed)))
        assertEquals(3600L, d["ttl"]!!.jsonPrimitive.long)
    }

    // Критик: DNS чи конект, що впав один раз (метро без мережі), кешувався
    // на 30 с — а веб ще 60 с — тож дзвінок на щойно повернутій мережі
    // лишався без жодного ICE до ~90 с. Недосяжність не кешується взагалі.
    @Test
    fun `недосяжний вузол не кешується — наступний виклик одразу питає знову`() {
        val port = freePort()
        val ice = ice("http://127.0.0.1:$port")
        val down = data(ice)
        assertEquals(JsonArray(emptyList()), down["iceServers"])
        assertTrue(down["detail"]!!.jsonPrimitive.content.startsWith("say:${R.string.msgr_bridge_ice_connect}:"))

        fakeServer(port = port) // мережа повернулась; годинник не рухався
        val up = data(ice)
        assertTrue("після повернення мережі TURN мав приїхати", up["turn"]!!.jsonPrimitive.boolean)
        assertEquals(1, hits.get())
    }

    @Test
    fun `таймаут теж не кешується`() {
        val url = fakeServer(hang = true)
        val ice = ice(url, timeoutMs = 300)
        data(ice)
        data(ice)
        assertEquals(2, hits.get())
    }
}
