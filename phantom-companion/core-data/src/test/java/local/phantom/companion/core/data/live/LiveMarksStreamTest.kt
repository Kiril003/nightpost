package local.phantom.companion.core.data.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.HAZARD_SOURCE_COMMUNITY
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.pushHazardMark
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.live.LiveMark
import local.phantom.companion.core.net.live.LiveMarkDraft
import local.phantom.companion.core.net.live.LiveMarksPort
import local.phantom.companion.core.net.live.LiveMarksStreamPort
import local.phantom.companion.core.net.live.LiveStreamEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-4 — сокет як прискорювач поверх звірки.
 *
 * Найважливіше тут не «швидко приходить», а: звірка НЕ вимикається, чужий
 * район не зливається, і курсор звірки не рухається від сокета — інакше
 * прогалина, заради якої звірка й існує, лишилась би непоміченою.
 */
class LiveMarksStreamTest {

    private val kyiv = LiveRegion(50.4501, 30.5234)

    private open class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        var now = 1_700_000_000_000L

        /** Точка перемикання в читанні — так двоє писарів реально стикаються. */
        var yieldOnRead = false
        private var seq = 0
        override suspend fun push(spec: SpatialLayerSpec): String {
            require(spec.elementCount > 0)
            val id = "layer-${seq++}"
            layers += SpatialLayer(id, spec, now, true, spec.ttlMs?.let { now + it })
            return id
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> {
            if (yieldOnRead) yield()
            return layers.toList()
        }
        override suspend fun requestFocus(target: FocusTarget) = Unit
    }

    private class FakePort(var rows: List<LiveMark> = emptyList()) : LiveMarksPort {
        override val isConfigured = true
        var pulls = 0
        var lastSince: Long? = null
        override suspend fun push(draft: LiveMarkDraft): GeoResult<Unit> = GeoResult.Ok(Unit)
        override suspend fun pull(cells: List<String>, sinceMs: Long?): GeoResult<List<LiveMark>> {
            pulls++
            lastSince = sinceMs
            return GeoResult.Ok(rows)
        }
    }

    private class FakeStream(override val isConfigured: Boolean = true) : LiveMarksStreamPort {
        var subscriptions = 0

        @Volatile
        var live = false
        val outbox = Channel<LiveStreamEvent>(Channel.UNLIMITED)
        override fun stream(): Flow<LiveStreamEvent> = flow {
            subscriptions++
            live = true
            try {
                for (event in outbox) emit(event)
            } finally {
                live = false
            }
        }
    }

    private fun repo(
        port: LiveMarksPort,
        sink: FakeSink,
        stream: LiveMarksStreamPort? = FakeStream(),
        region: () -> LiveRegion? = { kyiv },
    ) = LiveMarksRepository(
        port = port,
        sink = sink,
        layers = MutableStateFlow(emptyList()),
        region = region,
        clockMs = { sink.now },
        stream = stream,
    )

    private fun mark(
        kind: String,
        lat: Double,
        lon: Double,
        now: Long,
        ttlMs: Long = 30 * 60_000L,
        createdAtMs: Long = now,
    ) = LiveMark("id-$lat-$lon", kind, lat, lon, encode(lat, lon), createdAtMs, now + ttlMs)

    private fun encode(lat: Double, lon: Double) =
        local.phantom.companion.core.net.live.LiveGeohash.encode(lat, lon)

    // ── доставка ─────────────────────────────────────────────────────────

    @Test
    fun `мітка з сокета лягає в шар не чекаючи тіку і без жодного запиту`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)

        plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now + 900L))

        val marker = sink.layers.single().spec.markers.single()
        assertEquals(HAZARD_SOURCE_COMMUNITY, marker.source)
        assertEquals("ДТП", marker.label)
        assertEquals(0, port.pulls)
        assertEquals(1, plane.streamStats.merged)
    }

    @Test
    fun `затримка від створення до прийому лягає у свідчення воріт`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink)

        plane.absorb(
            LiveStreamEvent.Inserted(
                mark("ДТП", 50.4600, 30.5400, sink.now, createdAtMs = sink.now - 1_400L),
                sink.now,
            ),
        )

        assertEquals(1_400L, plane.streamStats.lastLagMs)
        assertEquals(1, plane.streamStats.received)
    }

    // ── відсів району ────────────────────────────────────────────────────

    @Test
    fun `мітка поза видимим районом не зливається`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink)

        // Львів — інший бік країни, той самий шар.
        plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 49.8397, 24.0297, sink.now), sink.now))

        assertTrue("чужий район ліг на мапу", sink.layers.isEmpty())
        assertEquals(1, plane.streamStats.outOfRegion)
        assertEquals(0, plane.streamStats.merged)
    }

    @Test
    fun `закрита мапа не приймає нічого з сокета`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink, region = { null })

        plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now))

        assertTrue(sink.layers.isEmpty())
        assertEquals(1, plane.streamStats.outOfRegion)
    }

    // ── злиття тими самими правилами ─────────────────────────────────────

    @Test
    fun `мітка з сокета не підмінює власну за двадцять метрів`() = runTest {
        val sink = FakeSink()
        sink.pushHazardMark("ДТП", 50.4500, 30.5200)
        val plane = repo(FakePort(), sink)

        plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 50.45007, 30.5200, sink.now), sink.now))

        val markers = sink.layers.single().spec.markers
        assertEquals(1, markers.size)
        assertNull("власна мітка перепідписалась чужою", markers.single().source)
        assertEquals(0, plane.streamStats.merged)
    }

    @Test
    fun `мертва мітка з сокета не лягає на мапу`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink)

        plane.absorb(
            LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now, ttlMs = -1L), sink.now),
        )

        assertTrue(sink.layers.isEmpty())
        assertEquals(0, plane.streamStats.merged)
    }

    @Test
    fun `та сама мітка двічі не роздвоюється`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink)
        val hit = LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now)

        plane.absorb(hit)
        plane.absorb(hit)

        assertEquals(1, sink.layers.single().spec.markers.size)
        assertEquals(1, plane.streamStats.merged)
    }

    // ── звірка лишається істиною ─────────────────────────────────────────

    @Test
    fun `сокет не рухає курсор звірки`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)

        plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now))
        plane.syncRegion()

        assertEquals(1, port.pulls)
        assertNull("курсор зсунувся — звірка пропустить прогалину", port.lastSince)
    }

    @Test
    fun `жива підписка сповільнює звірку, але не спиняє`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)

        plane.absorb(LiveStreamEvent.Connected)
        plane.syncRegion()
        assertEquals(1, port.pulls)

        sink.now += LiveMarksRepository.TIGHT_PULL_INTERVAL_MS
        plane.syncRegion()
        assertEquals("зі здоровим сокетом крок мусить бути хвилина", 1, port.pulls)

        sink.now += LiveMarksRepository.RELAXED_PULL_INTERVAL_MS
        plane.syncRegion()
        assertEquals(2, port.pulls)
    }

    @Test
    fun `втрата сокета пришвидшує звірку`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)
        plane.absorb(LiveStreamEvent.Connected)
        plane.syncRegion()

        plane.absorb(LiveStreamEvent.Dropped("обрив"))
        sink.now += LiveMarksRepository.TIGHT_PULL_INTERVAL_MS
        plane.syncRegion()

        assertEquals(2, port.pulls)
        assertFalse(plane.streamStats.joined)
    }

    @Test
    fun `без сокета крок звірки лишається таким, як був`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink, stream = null)

        plane.syncRegion()
        sink.now += LiveMarksRepository.TIGHT_PULL_INTERVAL_MS
        plane.syncRegion()
        assertEquals(1, port.pulls)

        sink.now += LiveMarksRepository.DEFAULT_PULL_INTERVAL_MS
        plane.syncRegion()
        assertEquals(2, port.pulls)
    }

    // ── життєвий цикл ────────────────────────────────────────────────────

    @Test
    fun `сокет живе рівно доки відкрита мапа`() = runTest {
        val sink = FakeSink()
        val stream = FakeStream()
        var open = false
        val plane = repo(FakePort(), sink, stream = stream, region = { if (open) kyiv else null })

        plane.start(backgroundScope)
        runCurrent()
        assertEquals("закрита мапа не має тримати сокет", 0, stream.subscriptions)

        open = true
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()
        assertEquals(1, stream.subscriptions)
        assertTrue(stream.live)

        // Підписка справді ожила — інакше перевірка `joined` нижче нічого не
        // означала б: прапорець і так стояв би на false весь тест.
        stream.outbox.trySend(LiveStreamEvent.Connected)
        runCurrent()
        assertTrue(plane.streamStats.joined)

        open = false
        repeat(LiveMarksRepository.IDLE_TICKS_BEFORE_CLOSE) {
            advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
            runCurrent()
        }
        assertFalse("закрита мапа лишила сокет висіти", stream.live)
        assertFalse("підписка лишилась «живою» на закритій мапі", plane.streamStats.joined)

        open = true
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()
        assertEquals(2, stream.subscriptions)
    }

    @Test
    fun `пінч навколо межі зуму не рве сокет`() = runTest {
        val sink = FakeSink()
        val stream = FakeStream()
        var open = true
        val plane = repo(FakePort(), sink, stream = stream, region = { if (open) kyiv else null })

        plane.start(backgroundScope)
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()
        assertEquals(1, stream.subscriptions)

        // Регіон блимнув на один тік — саме так виглядає зум, що переходить
        // z12 туди й назад. Сокет мусить це пережити.
        repeat(4) {
            open = !open
            advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
            runCurrent()
        }

        assertTrue("сокет перезбирався на пінчі", stream.live)
        assertEquals("зайве перепідключення", 1, stream.subscriptions)
    }

    @Test
    fun `палець і сокет одночасно — жодна мітка не гине`() = runTest {
        val sink = FakeSink()
        // Перемикання рівно між читанням шару і записом — те саме вікно, яке
        // realtime розширив із одного запису на пів хвилини до запису на мітку.
        sink.yieldOnRead = true
        val plane = repo(FakePort(), sink)

        val finger = launch { sink.pushHazardMark("ДТП", 50.4550, 30.5300) }
        val socket = launch {
            plane.absorb(LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now))
        }
        finger.join()
        socket.join()

        // Спільний замок шару тримається через ОБИДВІ фази, тож ці двоє
        // шикуються в чергу. Без нього другий писав би поверх першого.
        val markers = sink.layers.single().spec.markers
        assertEquals("одна з двох міток загубилась: $markers", 2, markers.size)
        assertTrue("мітка оператора зникла", markers.any { it.source == null && it.label == "ДТП" })
        assertTrue(markers.any { it.source == HAZARD_SOURCE_COMMUNITY && it.label == "ДТП" })
    }

    @Test
    fun `падіння сховища на події сокета не вбиває звірку`() = runTest {
        val port = FakePort()
        val stream = FakeStream()
        // Шар, що падає на кожному записі: повна памʼять, замкнена база.
        val sink = object : FakeSink() {
            override suspend fun push(spec: SpatialLayerSpec): String = error("диск повний")
        }
        val plane = repo(port, sink, stream = stream)
        plane.start(backgroundScope)
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()
        val pullsBefore = port.pulls

        stream.outbox.trySend(
            LiveStreamEvent.Inserted(mark("ДТП", 50.4600, 30.5400, sink.now), sink.now),
        )
        runCurrent()

        // Опитування — звіряльник. Смерть сокета не має права забрати його з
        // собою: площина зводиться раз і перезапуску не має.
        sink.now += LiveMarksRepository.RELAXED_PULL_INTERVAL_MS
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()
        assertTrue("звірка спинилась разом із сокетом", port.pulls > pullsBefore)
    }

    @Test
    fun `без ключів сокета підписки немає`() = runTest {
        val sink = FakeSink()
        val stream = FakeStream(isConfigured = false)
        val plane = repo(FakePort(), sink, stream = stream)

        plane.start(backgroundScope)
        advanceTimeBy(LiveMarksRepository.DEFAULT_TICK_MS + 1)
        runCurrent()

        assertEquals(0, stream.subscriptions)
    }
}
