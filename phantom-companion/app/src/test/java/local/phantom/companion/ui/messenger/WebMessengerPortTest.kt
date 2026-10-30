package local.phantom.companion.ui.messenger

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Порт моста (MESSENGER_V2 §2.2, 1Б): запит не тримає потік, що його приніс;
 * відповідь одна й повертається на «головний»; байти їдуть без base64.
 */
class WebMessengerPortTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val ui = Executors.newSingleThreadExecutor { Thread(it, "fake-ui") }

    @After fun tearDown() {
        scope.cancel()
        ui.shutdownNow()
    }

    private class Box {
        val posted = CopyOnWriteArrayList<Pair<String, String>>()
        val arrived = CountDownLatch(1)
    }

    private fun port(
        onPost: Box,
        handle: suspend (String, String, String?) -> String,
        takeBytes: suspend (String, ByteArray) -> String = { _, _ -> "{}" },
    ) = WebMessengerPort(scope, { r ->
        // Після tearDown пул уже мертвий, а запізніла відповідь ще летить: справжній
        // UI-потік мертвої Activity теж її просто не приймає. Без цього виняток
        // з чужого потоку падав у наступний runTest як «uncaught before test».
        if (!ui.isShutdown) runCatching { ui.execute(r) }
    }, handle, takeBytes)

    private fun reply(box: Box) = WebMessengerPort.Reply { msg ->
        box.posted += Thread.currentThread().name to msg
        box.arrived.countDown()
    }

    @Test
    fun `робота йде не на потоці, що приніс лист, а відповідь — на UI`() {
        val box = Box()
        var workedOn: Thread? = null
        val p = port(box, { m, path, _ ->
            workedOn = Thread.currentThread()
            """{"ok":true,"status":200,"data":"$m $path"}"""
        })
        p.onText("""{"id":7,"method":"GET","path":"/messenger/conversations"}""", this, reply(box))
        assertTrue(box.arrived.await(5, TimeUnit.SECONDS))
        assertNotSame(Thread.currentThread(), workedOn)
        assertEquals("fake-ui", box.posted.single().first)
        val o = Json.parseToJsonElement(box.posted.single().second).jsonObject
        assertEquals(7, o["id"]!!.jsonPrimitive.int)
        assertEquals("GET /messenger/conversations", o["r"]!!.jsonObject["data"]!!.jsonPrimitive.content)
    }

    @Test
    fun `повільний запит не тримає ні вхід, ні наступний запит`() {
        val box = Box()
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        val p = port(box, { _, path, _ ->
            if (path == "/slow") kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { release.await(5, TimeUnit.SECONDS) }
            """{"ok":true,"status":200,"data":"$path"}"""
        })
        val r = WebMessengerPort.Reply { box.posted += "" to it; done.countDown() }
        val t0 = System.nanoTime()
        p.onText("""{"id":1,"method":"POST","path":"/slow"}""", this, r)
        p.onText("""{"id":2,"method":"GET","path":"/fast"}""", this, r)
        // onText повернувся одразу — JS не чекав.
        assertTrue((System.nanoTime() - t0) < TimeUnit.MILLISECONDS.toNanos(200))
        // Швидкий відповів, поки повільний ще в мережі.
        val deadline = System.currentTimeMillis() + 5000
        while (box.posted.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(5)
        assertTrue(box.posted.first().second.startsWith("{\"id\":2,"))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(2, 1), box.posted.map { Json.parseToJsonElement(it.second).jsonObject["id"]!!.jsonPrimitive.int })
    }

    @Test
    fun `виняток усередині — одна чесна відмова, а не тиша`() {
        val box = Box()
        val p = port(box, { _, _, _ -> error("зламалось") })
        p.onText("""{"id":3,"method":"GET","path":"/x"}""", this, reply(box))
        assertTrue(box.arrived.await(5, TimeUnit.SECONDS))
        val r = Json.parseToJsonElement(box.posted.single().second).jsonObject["r"]!!.jsonObject
        assertEquals(500, r["status"]!!.jsonPrimitive.int)
        Thread.sleep(50)
        assertEquals(1, box.posted.size)
    }

    @Test
    fun `байти за заголовком ідуть як є, без base64`() {
        val box = Box()
        var got: Pair<String, ByteArray>? = null
        val p = port(box, { _, _, _ -> "{}" }, { conv, bytes ->
            got = conv to bytes
            """{"ok":true,"status":200,"data":{"size":${bytes.size}}}"""
        })
        val photo = ByteArray(300_000) { (it * 31).toByte() }
        p.onText("""{"id":9,"bin":{"conversation_id":"peer_a"}}""", this, reply(box))
        p.onBytes(photo, this, reply(box))
        assertTrue(box.arrived.await(5, TimeUnit.SECONDS))
        assertEquals("peer_a", got!!.first)
        assertTrue(photo.contentEquals(got!!.second))
        assertTrue(box.posted.single().second.startsWith("{\"id\":9,"))
    }

    @Test
    fun `байти без заголовка відкидаються`() {
        val box = Box()
        var called = false
        val p = port(box, { _, _, _ -> "{}" }, { _, _ -> called = true; "{}" })
        p.onBytes(ByteArray(4), this, reply(box))
        Thread.sleep(50)
        assertTrue(!called && box.posted.isEmpty())
    }

    @Test
    fun `привітання каже вебу, чи можна слати байти`() {
        val box = Box()
        val p = port(box, { _, _, _ -> "{}" })
        p.binary = true
        p.onText("""{"t":"hello"}""", this, reply(box))
        assertEquals("""{"t":"hello","bin":true}""", box.posted.single().second)
    }

    @Test
    fun `старий синхронний вхід дістає ту саму відповідь`() {
        val out = WebMessengerPort.await(scope) { """{"ok":true,"status":200,"data":1}""" }
        assertEquals("""{"ok":true,"status":200,"data":1}""", out)
    }

    /** Сторож: у ui/messenger не лишилось жодного блокування корутиною. */
    @Test
    fun `жодного runBlocking у ui-messenger`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = File(dir, "app/src/main/java/local/phantom/companion/ui/messenger")
        val files = root.walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(files.size > 20)
        val hits = files.flatMap { f ->
            f.readLines().mapIndexedNotNull { i, l -> if ("runBlocking" in l) "${f.name}:${i + 1}" else null }
        }
        assertEquals(emptyList<String>(), hits)
        // І порт справді стоїть у хості, а не лише існує.
        val host = File(root, "WebMessengerHost.kt").readText()
        assertTrue(host.contains(".install(this, WebMessengerAssets.ORIGIN)"))
        assertTrue(host.contains("meshDeltaJs("))
    }

    @Test
    fun `файл, що ріжеться й шифрується, не тримає слова за собою`() {
        val box = Box()
        val release = CountDownLatch(1)
        val wordDone = CountDownLatch(1)
        // Справжній мост тримає смугу, поки готує шматки, — без жодного withContext.
        val p = port(
            box,
            { _, path, _ ->
                if (path.startsWith("/messenger/files")) release.await(5, TimeUnit.SECONDS)
                """{"ok":true,"status":200,"data":"$path"}"""
            },
            { _, _ -> release.await(5, TimeUnit.SECONDS); "{}" },
        )
        val r = WebMessengerPort.Reply { box.posted += "" to it; if (it.startsWith("{\"id\":3,")) wordDone.countDown() }
        p.onText("""{"id":1,"bin":{"conversation_id":"peer_a"}}""", this, r)
        p.onBytes(ByteArray(1_000), this, r)
        p.onText("""{"id":2,"method":"POST","path":"/messenger/files"}""", this, r)
        p.onText("""{"id":3,"method":"POST","path":"/messenger/conversations/peer_a/messages"}""", this, r)
        assertTrue("слово мусить відповісти, поки файл ще в роботі", wordDone.await(2, TimeUnit.SECONDS))
        release.countDown()
    }

    @Test
    fun `файлові шляхи йдуть окремою смугою, решта — словесною`() {
        assertTrue(WebMessengerPort.isBulk("/messenger/files"))
        assertTrue(WebMessengerPort.isBulk("/messenger/files/abc/status"))
        assertTrue(WebMessengerPort.isBulk("/api/v1/attachments/claim"))
        assertTrue(!WebMessengerPort.isBulk("/messenger/conversations/files"))
        assertTrue(!WebMessengerPort.isBulk("/messenger/filesystem"))
    }

    @Test
    fun `рівень мікрофона йде у веб не частіше ~15 разів на секунду`() = kotlinx.coroutines.test.runTest {
        val raw = kotlinx.coroutines.flow.flow {
            // Мікрофон віддає буфер кожні 10 мс — 300 значень за 3 с.
            repeat(300) {
                emit(it / 300f)
                kotlinx.coroutines.delay(10)
            }
        }
        val sent = mutableListOf<Float>()
        WebMessengerPort.audioFrames(raw).collect { sent += it }
        assertTrue("кадрів ${sent.size}", sent.size in 30..46)
    }
}
