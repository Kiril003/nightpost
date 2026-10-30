package local.phantom.companion.license

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.license.AvailableBuild
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Завантаження збірки — єдине місце застосунку, де зловмиснику вигідно, щоб ми
 * ЗДАЛИСЯ на пів дорозі й поставили те, що є. Тому доводиться троє: обрив не
 * коштує вже завантаженого, чужі байти не встановлюються ніколи, і нескінченна
 * відповідь не зʼїдає диск.
 *
 * Сервер справжній (петля), бо ламався саме заголовок `Range:` і код 206 —
 * підроблене джерело довело б лише те, що ми вміємо писати у файл.
 */
class UpdateFetcherTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: ServerSocket
    private val payload = ByteArray(64_000) { (it % 251).toByte() }
    private var perResponse = Int.MAX_VALUE
    private var honourRange = true
    private val ranges = mutableListOf<String?>()

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun build(
        size: Long = payload.size.toLong(),
        sha: String = sha256(payload),
    ) = AvailableBuild(
        platform = "android",
        build = 42,
        version = "1.4.0",
        filename = "phantom-1.4.0.apk",
        url = "https://phantom-os.dev/account",
        sizeBytes = size,
        sha256 = sha,
        requiresLicence = false,
    )

    @Before
    fun start() {
        server = ServerSocket(0, 0, java.net.InetAddress.getByName("127.0.0.1"))
        Thread {
            while (!server.isClosed) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                runCatching { client.use(::serve) }
            }
        }.apply { isDaemon = true }.start()
    }

    @After
    fun stop() = server.close()

    /** Рівно стільки HTTP, скільки треба, щоб `Range:` був справжнім. */
    private fun serve(client: Socket) {
        val reader = client.getInputStream().bufferedReader()
        var asked: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                asked = line.substringAfter(':').trim()
            }
        }
        ranges += asked
        val from = if (honourRange && asked != null) {
            asked.removePrefix("bytes=").substringBefore('-').toInt()
        } else {
            0
        }
        val body = payload.copyOfRange(from, payload.size)
        val status = if (from > 0) "206 Partial Content" else "200 OK"
        val out = client.getOutputStream()
        out.write(
            (
                "HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\n" +
                    "Content-Range: bytes $from-${payload.size - 1}/${payload.size}\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        // Обрив = відповідь коротша за оголошену довжину, тобто рівно те, що
        // бачить телефон, коли звʼязок зникає посеред 156 МБ.
        out.write(body, 0, minOf(perResponse, body.size))
        out.flush()
    }

    private fun url() = "http://127.0.0.1:${server.localPort}/build.apk"

    private fun updates(dir: File) = File(dir, UpdateFetcher.DIR)

    private fun assertIsTheAnnouncedBuild(file: File) {
        assertEquals(payload.size.toLong(), file.length())
        assertEquals(sha256(payload), sha256(file.readBytes()))
    }

    @Test
    fun `a connection that keeps breaking costs the lost bytes, not the whole file`() = runBlocking {
        val dir = folder.newFolder()
        perResponse = 20_000

        val result = UpdateFetcher(dir).fetch(build(), url())

        assertTrue(result is UpdateFetcher.Result.Ready)
        assertIsTheAnnouncedBuild((result as UpdateFetcher.Result.Ready).file)
        // Кожна наступна спроба просила ПРОДОВЖИТИ з місця обриву. Без цього
        // 156 МБ на поганій мережі не докачуються ніколи — качаються вічно.
        assertEquals(listOf(null, "bytes=20000-", "bytes=40000-", "bytes=60000-"), ranges)
    }

    @Test
    fun `a source that gives nothing back is not retried forever`() = runBlocking {
        perResponse = 0

        val result = UpdateFetcher(folder.newFolder()).fetch(build(), url())

        assertEquals(
            UpdateFetcher.Reason.Network,
            (result as UpdateFetcher.Result.Failed).reason,
        )
        assertEquals(1, ranges.size)
    }

    @Test
    fun `a server that ignores Range starts over instead of stitching a chimera`() = runBlocking {
        val dir = folder.newFolder()
        val announced = build()
        val part = File(updates(dir).apply { mkdirs() }, "42-phantom-1.4.0.apk.part")
        part.writeBytes(payload.copyOfRange(0, 20_000))
        honourRange = false

        val result = UpdateFetcher(dir).fetch(announced, url())

        // Продовжити просили — сервер зігнорував. Дописане в хвіст дало б файл
        // із подвоєною серединою, тобто саме той випадок, коли помилку ловить
        // лише сума. Тому переписуємо з нуля, а не зшиваємо.
        assertEquals(listOf("bytes=20000-"), ranges)
        assertTrue(result is UpdateFetcher.Result.Ready)
        assertIsTheAnnouncedBuild((result as UpdateFetcher.Result.Ready).file)
    }

    @Test
    fun `bytes that do not match the signed sum are refused and thrown away`() = runBlocking {
        val dir = folder.newFolder()

        val result = UpdateFetcher(dir).fetch(build(sha = "b".repeat(64)), url())

        assertEquals(
            UpdateFetcher.Reason.Mismatch,
            (result as UpdateFetcher.Result.Failed).reason,
        )
        // Нічого придатного для встановлення не лишається: інакше наступний
        // екран запропонував би поставити саме ці байти.
        assertEquals(emptyList<String>(), updates(dir).list()!!.toList())
    }

    @Test
    fun `an answer describing another size is refused before a byte is kept`() = runBlocking {
        val dir = folder.newFolder()

        val result = UpdateFetcher(dir).fetch(build(size = payload.size + 4_096L), url())

        assertEquals(
            UpdateFetcher.Reason.Mismatch,
            (result as UpdateFetcher.Result.Failed).reason,
        )
        assertEquals(emptyList<String>(), updates(dir).list()!!.toList())
    }

    @Test
    fun `an endless answer cannot outgrow the announced build`() = runBlocking {
        val dir = folder.newFolder()
        val announced = build(size = 8_000, sha = sha256(payload.copyOfRange(0, 8_000)))
        // Джерело бреше про довжину й ллє без кінця. Стеля — оголошений розмір.
        val fetcher = UpdateFetcher(dir) { _, _ ->
            UpdateFetcher.Source(
                stream = object : java.io.InputStream() {
                    override fun read(): Int = 0
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        payload.copyInto(b, off, 0, len)
                        return len
                    }
                },
                resumed = false,
                remaining = -1,
            )
        }

        val result = fetcher.fetch(announced, url())

        assertTrue(result is UpdateFetcher.Result.Ready)
        assertEquals(8_000L, (result as UpdateFetcher.Result.Ready).file.length())
    }

    @Test
    fun `a build already fetched and verified is not fetched twice`() = runBlocking {
        val fetcher = UpdateFetcher(folder.newFolder())
        assertTrue(fetcher.fetch(build(), url()) is UpdateFetcher.Result.Ready)

        assertTrue(fetcher.fetch(build(), url()) is UpdateFetcher.Result.Ready)

        assertEquals(1, ranges.size)
    }

    @Test
    fun `yesterday's half-downloaded build does not live in the cache forever`() = runBlocking {
        val dir = folder.newFolder()
        val fetcher = UpdateFetcher(dir)
        perResponse = 0
        fetcher.fetch(build(), url())
        File(updates(dir), "41-phantom-1.3.0.apk.part").writeBytes(ByteArray(16))

        fetcher.forget(keep = null)

        assertFalse(updates(dir).list()!!.any { it.endsWith(".part") })
    }
}
