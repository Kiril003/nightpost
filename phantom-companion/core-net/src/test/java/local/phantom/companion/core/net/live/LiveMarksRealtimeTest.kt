package local.phantom.companion.core.net.live

import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.net.geosrc.GeoClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-4 — живий сокет.
 *
 * Перевіряється не «швидко», а межа: що виїжджає в рукостисканні, що
 * потрапляє в журнал, і як канал поводиться, коли інший бік мовчить або
 * відмовляє. Сокета тут немає — є дріт, який тест тримає в руках.
 */
class LiveMarksRealtimeTest {

    // Форма справжнього ключа: JWT, суцільний ASCII. Кирилиця тут дала б
    // відсоткове кодування і сховала б витік, замість того щоб його показати.
    private val anonKey =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJyb2xlIjoiYW5vbiJ9.s3cr3t-signature-vAlue"
    private val config = LiveMarksConfig(baseUrl = "https://проєкт.supabase.co", anonKey = anonKey)

    /**
     * `created_at` зі зразка. Сам зразок — кадр, знятий із живого проєкту, а
     * не написаний рукою: рукописний мав зайві `eventType` і `old_record`,
     * яких Realtime на INSERT не шле.
     */
    private val createdAtMs = Instant.parse("2026-08-23T13:00:23.282809Z").toEpochMilli()

    private class FakeClock(var now: Long) : GeoClock {
        override fun nowMs(): Long = now
    }

    private class FakeWire : LiveWire {
        val sent = mutableListOf<String>()
        val inbox = Channel<String>(Channel.UNLIMITED)

        @Volatile
        var closed = false

        override suspend fun send(text: String) {
            sent += text
        }

        override suspend fun receive(): String? = inbox.receiveCatching().getOrNull()

        override suspend fun close() {
            closed = true
            inbox.close()
        }

        fun feed(text: String) {
            inbox.trySend(text)
        }
    }

    private class FakeOpener(var failures: Int = 0) : LiveWireOpener {
        val urls = mutableListOf<String>()
        val wires = mutableListOf<FakeWire>()

        /** Чим саме падати. Стандартно — звичайний обрив. */
        var boom: () -> Throwable = { java.io.IOException("мережі немає") }

        override suspend fun open(url: String): LiveWire {
            urls += url
            if (failures > 0) {
                failures--
                throw boom()
            }
            return FakeWire().also { wires += it }
        }
    }

    private fun fixture(): String =
        checkNotNull(javaClass.getResourceAsStream("/live/postgres_changes_insert.json")) {
            "зразок розсилки не знайдено"
        }.bufferedReader().use { it.readText() }

    private fun joinOk(): String =
        """{"topic":"${PhxCodec.TOPIC}","event":"phx_reply","ref":"1",
           "payload":{"status":"ok","response":{"postgres_changes":[{"id":40905980,
           "event":"INSERT","schema":"public","table":"live_marks"}]}}}"""

    /** «ok» без жодної привʼязки — таблиці немає в публікації. */
    private fun joinNoBindings(): String =
        """{"topic":"${PhxCodec.TOPIC}","event":"phx_reply","ref":"1",
           "payload":{"status":"ok","response":{"postgres_changes":[]}}}"""

    /** Друге підтвердження — те, що знято з живого проєкту. */
    private fun systemOk(): String =
        """{"topic":"${PhxCodec.TOPIC}","event":"system","ref":null,
           "payload":{"channel":"public:live_marks","extension":"postgres_changes",
           "status":"ok","message":"Subscribed to PostgreSQL"}}"""

    private fun systemFail(): String =
        """{"topic":"${PhxCodec.TOPIC}","event":"system","ref":null,
           "payload":{"channel":"public:live_marks","extension":"postgres_changes",
           "status":"error","message":"Unable to subscribe to changes"}}"""

    private fun beatOk(ref: String): String =
        """{"topic":"phoenix","event":"phx_reply","ref":"$ref","payload":{"status":"ok","response":{}}}"""

    private class Harness(
        val opener: FakeOpener = FakeOpener(),
        val clock: FakeClock,
        config: LiveMarksConfig,
    ) {
        val journal = mutableListOf<String>()
        val events = mutableListOf<LiveStreamEvent>()
        val realtime = LiveMarksRealtime(
            config = config,
            opener = opener,
            clock = clock,
            roll = { 0.5 },
            journal = { journal += it },
        )
    }

    private fun harness() = Harness(clock = FakeClock(createdAtMs + 800L), config = config)

    // ── рукостискання і ключ ─────────────────────────────────────────────

    @Test
    fun `ключ у рукостисканні є, у журналі його немає`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        val url = h.opener.urls.single()
        assertTrue("рукостискання без ключа Realtime не приймає", url.contains("apikey="))
        assertTrue("ключ мусить бути в query саме тут", url.contains(anonKey))
        assertTrue("wss, не ws", url.startsWith("wss://проєкт.supabase.co/realtime/v1/websocket"))
        assertTrue("версія серіалізатора", url.contains("vsn=1.0.0"))

        assertTrue("журнал порожній — нічого доводити", h.journal.isNotEmpty())
        for (line in h.journal) {
            assertFalse("ключ протік у журнал: $line", line.contains("s3cr3t-signature"))
            assertFalse("ключ протік у журнал: $line", line.contains(anonKey))
        }
        assertTrue(h.journal.any { it.contains("apikey=***") })
    }

    @Test
    fun `скраб знімає ключ і лишає адресу`() {
        val url = RealtimeHandshake.url(config)
        val scrubbed = RealtimeHandshake.scrub(url)

        assertFalse(scrubbed.contains("s3cr3t-signature"))
        assertTrue(scrubbed.contains("проєкт.supabase.co"))
        assertTrue(scrubbed.contains("vsn=1.0.0"))
    }

    @Test
    fun `приєднання просить саме INSERT саме цієї таблиці`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        val join = h.opener.wires.single().sent.single()
        assertTrue(join.contains("\"event\":\"phx_join\""))
        assertTrue(join.contains("\"topic\":\"realtime:public:live_marks\""))
        assertTrue(join.contains("\"table\":\"live_marks\""))
        assertTrue(join.contains("\"event\":\"INSERT\""))
        // Не обовʼязковий для anon, але це майбутнє місце токена користувача.
        assertTrue(join.contains("\"access_token\":\"$anonKey\""))
    }

    @Test
    fun `без ключів сокет не відкривається взагалі`() = runTest {
        val h = Harness(clock = FakeClock(0L), config = LiveMarksConfig())
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        assertTrue(h.opener.urls.isEmpty())
        assertTrue(h.events.isEmpty())
    }

    // ── розбір розсилки ──────────────────────────────────────────────────

    @Test
    fun `зразок розсилки стає міткою із затримкою`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(joinOk())
        wire.feed(systemOk())
        wire.feed(fixture())
        runCurrent()

        assertTrue(h.events.first() is LiveStreamEvent.Connected)
        val inserted = h.events.filterIsInstance<LiveStreamEvent.Inserted>().single()
        assertEquals("патруль", inserted.mark.kind)
        assertEquals(50.4501, inserted.mark.lat, 1e-6)
        assertEquals("u8vxn8", inserted.mark.geohash6)
        assertEquals("4377ce27-3c84-406d-ba3c-2fcf69b2e10b", inserted.mark.id)
        assertEquals(createdAtMs, inserted.mark.createdAtMs)
        assertEquals(800L, inserted.receivedAtMs - inserted.mark.createdAtMs)
    }

    @Test
    fun `чужа таблиця і не-INSERT крізь декодер не проходять`() {
        val update = fixture().replace("\"type\": \"INSERT\"", "\"type\": \"UPDATE\"")
        val other = fixture().replace("\"table\": \"live_marks\"", "\"table\": \"profiles\"")

        assertNotNull(PhxCodec.insertedMark(PhxCodec.decode(fixture())!!))
        assertNull(PhxCodec.insertedMark(PhxCodec.decode(update)!!))
        assertNull(PhxCodec.insertedMark(PhxCodec.decode(other)!!))
    }

    @Test
    fun `форма supabase-js з полем new теж читається`() {
        // Дріт шле `record`; бібліотечна форма — `new`. Межа не наша.
        val asNew = fixture().replace("\"record\":", "\"new\":")

        val mark = PhxCodec.insertedMark(PhxCodec.decode(asNew)!!)

        assertEquals("патруль", mark?.kind)
    }

    @Test
    fun `битий кадр не валить канал`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(joinOk())
        wire.feed(systemOk())
        wire.feed("{це не json")
        wire.feed("""{"topic":"x","event":"невідома подія","payload":{}}""")
        wire.feed(fixture())
        runCurrent()

        assertEquals(1, h.events.filterIsInstance<LiveStreamEvent.Inserted>().size)
        assertTrue(h.events.none { it is LiveStreamEvent.Dropped })
    }

    // ── серцебиття й перезбирання ────────────────────────────────────────

    @Test
    fun `серцебиття йде раз на тридцять секунд`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()
        wire.feed(joinOk())
        runCurrent()

        advanceTimeBy(LiveMarksRealtime.HEARTBEAT_MS + 1)
        runCurrent()

        val beats = wire.sent.filter { it.contains("\"event\":\"heartbeat\"") }
        assertEquals(1, beats.size)
        assertTrue(beats.single().contains("\"topic\":\"phoenix\""))
    }

    @Test
    fun `мовчазний канал помирає і перезбирається`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val first = h.opener.wires.single()
        first.feed(joinOk())
        runCurrent()

        // Два удари без відповіді, третій тік — смерть.
        repeat(LiveMarksRealtime.MAX_SILENT_BEATS + 1) {
            advanceTimeBy(LiveMarksRealtime.HEARTBEAT_MS + 1)
            runCurrent()
        }

        assertTrue("канал мусив бути розірваний", first.closed)
        assertTrue(h.events.any { it is LiveStreamEvent.Dropped })

        // Відкат із розкидом — і нове приєднання на новому дроті.
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, h.opener.wires.size)
        assertTrue(h.opener.wires.last().sent.single().contains("phx_join"))
    }

    @Test
    fun `підтверджений удар обнуляє лічильник мовчання`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()
        wire.feed(joinOk())
        runCurrent()

        repeat(6) {
            advanceTimeBy(LiveMarksRealtime.HEARTBEAT_MS + 1)
            runCurrent()
            wire.feed(beatOk("hb-${it + 1}"))
            runCurrent()
        }

        assertFalse("канал з відповідями не має вмирати", wire.closed)
        assertTrue(h.events.none { it is LiveStreamEvent.Dropped })
    }

    @Test
    fun `відмова в підписці не лишає німий канал відкритим`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(
            """{"topic":"${PhxCodec.TOPIC}","event":"phx_reply","ref":"1",
               "payload":{"status":"error","response":{"reason":"unauthorized"}}}""",
        )
        runCurrent()

        assertTrue(wire.closed)
        val dropped = h.events.filterIsInstance<LiveStreamEvent.Dropped>().single()
        assertTrue(dropped.why, dropped.why.contains("unauthorized"))
        assertTrue(h.events.none { it is LiveStreamEvent.Connected })
    }

    @Test
    fun `прийнятий топік ще не означає живу підписку`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(joinOk())
        runCurrent()

        // Сокет стоїть, топік прийнято — але змін не буде, поки Postgres
        // не підтвердив. Оголосити тут «підписка є» означало б сповільнити
        // звірку до хвилини на каналі, який нічого не принесе.
        assertTrue(h.events.none { it is LiveStreamEvent.Connected })

        wire.feed(systemOk())
        runCurrent()
        assertEquals(1, h.events.filterIsInstance<LiveStreamEvent.Connected>().size)
    }

    @Test
    fun `відмова Postgres не лишає канал висіти мовчки`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(joinOk())
        wire.feed(systemFail())
        runCurrent()

        assertTrue(wire.closed)
        assertTrue(h.events.none { it is LiveStreamEvent.Connected })
        val dropped = h.events.filterIsInstance<LiveStreamEvent.Dropped>().single()
        assertTrue(dropped.why, dropped.why.contains("Unable to subscribe"))
    }

    @Test
    fun `приєднання без жодної привʼязки не вважається успіхом`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()
        val wire = h.opener.wires.single()

        wire.feed(joinNoBindings())
        runCurrent()

        // Канал цілком здоровий і не принесе жодної зміни ніколи. Оголосити
        // тут успіх означало б сповільнити звірку саме там, де вона єдина.
        assertTrue(h.events.none { it is LiveStreamEvent.Connected })
        assertTrue(wire.closed)
        assertTrue(h.events.filterIsInstance<LiveStreamEvent.Dropped>().single().why.contains("привʼязки"))
    }

    @Test
    fun `виняток транспорту не тягне ключ у журнал`() = runTest {
        // Саме так Ktor повідомляє про тайм-аут: із повним URL у тексті.
        val leaky = FakeOpener(failures = 1)
        leaky.boom = {
            java.net.SocketTimeoutException(
                "Socket timeout has expired [url=${RealtimeHandshake.url(config)}, socket_timeout=15000] ms",
            )
        }
        val h = Harness(opener = leaky, clock = FakeClock(createdAtMs), config = config)
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        val dropped = h.events.filterIsInstance<LiveStreamEvent.Dropped>().single()
        assertFalse("ключ у причині розриву: ${dropped.why}", dropped.why.contains("s3cr3t-signature"))
        for (line in h.journal) {
            assertFalse("ключ протік у журнал: $line", line.contains("s3cr3t-signature"))
        }
    }

    @Test
    fun `сервер, що рве одразу після приєднання, не крутить рукостискання щосекунди`() = runTest {
        val h = harness()
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        // Пʼять кіл «приєднався — і одразу обрив», з коротким чеканням між ними.
        repeat(5) {
            h.opener.wires.last().let { wire ->
                wire.feed(joinOk())
                wire.feed(systemOk())
                runCurrent()
                wire.close()
                runCurrent()
            }
            advanceTimeBy(1_500L)
            runCurrent()
        }

        // Якби відкат скидався на самому приєднанні, кожне коло вкладалося б у
        // півтори секунди й сесій було б шість. Він росте від того, що жодна
        // сесія не прожила, — тож далі третьої не пішло.
        assertTrue("рукостискання по колу: ${h.opener.wires.size} сесій", h.opener.wires.size < 5)
    }

    @Test
    fun `мережі немає — тихо і без винятків`() = runTest {
        val h = Harness(opener = FakeOpener(failures = 2), clock = FakeClock(createdAtMs), config = config)
        backgroundScope.launch { h.realtime.stream().collect { h.events += it } }
        runCurrent()

        assertEquals(1, h.events.filterIsInstance<LiveStreamEvent.Dropped>().size)
        advanceTimeBy(2_000L)
        runCurrent()
        assertEquals(2, h.events.filterIsInstance<LiveStreamEvent.Dropped>().size)

        advanceTimeBy(4_000L)
        runCurrent()
        assertEquals(1, h.opener.wires.size)
    }

    @Test
    fun `скасування збирача не перетворюється на розрив`() = runTest {
        val h = harness()
        val job = backgroundScope.launch {
            try {
                h.realtime.stream().collect { h.events += it }
            } catch (e: CancellationException) {
                h.journal += "скасовано"
                throw e
            }
        }
        runCurrent()
        h.opener.wires.single().feed(joinOk())
        runCurrent()

        job.cancel()
        runCurrent()

        assertTrue(h.events.none { it is LiveStreamEvent.Dropped })
        assertTrue(h.opener.wires.single().closed)
    }

    // ── відкат ───────────────────────────────────────────────────────────

    @Test
    fun `відкат росте, впирається в стелю і завжди розкиданий`() {
        val low = LiveMarksRealtime.backoffMs(1, 0.0)
        val high = LiveMarksRealtime.backoffMs(1, 1.0)

        assertTrue("розкид відсутній", low < high)
        assertEquals(750L, low)
        assertEquals(1_250L, high)
        assertTrue(LiveMarksRealtime.backoffMs(4, 0.5) > LiveMarksRealtime.backoffMs(2, 0.5))
        assertTrue(LiveMarksRealtime.backoffMs(99, 1.0) <= 30_000L * 1.25)
    }
}
