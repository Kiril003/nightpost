package local.phantom.companion.core.data.live

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.HAZARD_SOURCE_COMMUNITY
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.data.spatial.pushHazardMark
import local.phantom.companion.core.net.geosrc.GeoResult
import local.phantom.companion.core.net.geosrc.SourceUnavailable
import local.phantom.companion.core.net.live.LiveMark
import local.phantom.companion.core.net.live.LiveMarkDraft
import local.phantom.companion.core.net.live.LiveMarksPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATLAS-4 — черга власних міток і злиття чужих.
 *
 * Найважливіше тут не «синхронізується», а: власна мітка не перепідписується
 * чужою, чужа гасне за власним TTL, і з вимкненою площиною не змінюється
 * НІЧОГО (local-first).
 */
class LiveMarksRepositoryTest {

    private class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        var now = 1_700_000_000_000L
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
        override suspend fun list(threadId: String?): List<SpatialLayer> = layers.toList()
        override suspend fun requestFocus(target: FocusTarget) = Unit
    }

    private class FakePort(
        override val isConfigured: Boolean = true,
        var rows: List<LiveMark> = emptyList(),
    ) : LiveMarksPort {
        val pushed = mutableListOf<LiveMarkDraft>()
        var pulls = 0
        var pushFailures = 0
        var permanentFailure = false
        override suspend fun push(draft: LiveMarkDraft): GeoResult<Unit> {
            if (permanentFailure) {
                return GeoResult.Unavailable(SourceUnavailable.RequestRejected("вид поза схемою"))
            }
            if (pushFailures > 0) {
                pushFailures--
                return GeoResult.Unavailable(SourceUnavailable.Transport("обрив"))
            }
            pushed += draft
            return GeoResult.Ok(Unit)
        }
        override suspend fun pull(cells: List<String>, sinceMs: Long?): GeoResult<List<LiveMark>> {
            pulls++
            return GeoResult.Ok(rows)
        }
    }

    private fun repo(
        port: LiveMarksPort,
        sink: FakeSink,
        clock: () -> Long = { sink.now },
        region: () -> LiveRegion? = { LiveRegion(50.4501, 30.5234) },
    ) = LiveMarksRepository(
        port = port,
        sink = sink,
        layers = MutableStateFlow<List<SpatialLayer>>(emptyList()),
        region = region,
        clockMs = clock,
    )

    private fun mark(kind: String, lat: Double, lon: Double, now: Long, ttlMs: Long = 30 * 60_000L) =
        LiveMark("id-$lat-$lon", kind, lat, lon, "u8vxn8", now - 60_000L, now + ttlMs)

    // ── черга ────────────────────────────────────────────────────────────

    @Test
    fun `власна мітка з шару стає в чергу і їде`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)
        sink.pushHazardMark("ДТП", 50.45, 30.52)

        plane.capture(sink.list(null))
        assertEquals(1, plane.pendingCount())
        assertEquals(1, plane.drainOutbox())

        assertEquals("ДТП", port.pushed.single().kind)
        assertEquals(0, plane.pendingCount())
    }

    @Test
    fun `чужа мітка назад у мережу не повертається`() = runTest {
        val sink = FakeSink()
        val port = FakePort(rows = listOf(mark("ДТП", 50.4600, 30.5400, sink.now)))
        val plane = repo(port, sink)

        plane.syncRegion()
        plane.capture(sink.list(null))

        assertEquals(0, plane.pendingCount())
    }

    @Test
    fun `блокпост і патруль із мережі на мапу не лягають`() = runTest {
        val sink = FakeSink()
        val port = FakePort(
            rows = listOf(
                mark("блокпост", 50.4600, 30.5400, sink.now),
                mark("патруль", 50.4650, 30.5450, sink.now),
                mark("ДТП", 50.4700, 30.5500, sink.now),
            ),
        )
        repo(port, sink).syncRegion()

        val kinds = sink.layers.flatMap { it.spec.markers }.map { it.label }
        assertEquals(listOf("ДТП"), kinds)
    }

    @Test
    fun `вид поза схемою лишається локальним`() = runTest {
        val sink = FakeSink()
        val plane = repo(FakePort(), sink)
        sink.pushHazardMark("камера", 50.45, 30.52)

        plane.capture(sink.list(null))

        assertEquals(0, plane.pendingCount())
    }

    @Test
    fun `обрив мережі не втрачає мітку — вона їде наступним колом`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        port.pushFailures = 2
        val plane = repo(port, sink)
        sink.pushHazardMark("ДТП", 50.45, 30.52)
        plane.capture(sink.list(null))

        assertEquals(0, plane.drainOutbox())
        assertEquals(1, plane.pendingCount())

        // Наступна спроба — не раніше, ніж дозволяє відкат.
        assertEquals(0, plane.drainOutbox())
        sink.now += 10_000L
        assertEquals(0, plane.drainOutbox())
        sink.now += 30_000L
        assertEquals(1, plane.drainOutbox())
        assertEquals(0, plane.pendingCount())
    }

    @Test
    fun `постійна відмова не крутить чергу вічно`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        port.permanentFailure = true
        val plane = repo(port, sink)
        sink.pushHazardMark("ДТП", 50.45, 30.52)
        plane.capture(sink.list(null))

        assertEquals(0, plane.drainOutbox())

        assertEquals(0, plane.pendingCount())
    }

    @Test
    fun `відправлена мітка не ставиться в чергу знову`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)
        sink.pushHazardMark("ДТП", 50.45, 30.52)

        plane.capture(sink.list(null))
        plane.drainOutbox()
        plane.capture(sink.list(null))
        plane.capture(sink.list(null))

        assertEquals(0, plane.pendingCount())
        assertEquals(1, port.pushed.size)
    }

    // ── злиття ───────────────────────────────────────────────────────────

    @Test
    fun `чужа мітка лягає в той самий шар із провенансом`() = runTest {
        val sink = FakeSink()
        val port = FakePort(rows = listOf(mark("ДТП", 50.4600, 30.5400, sink.now)))
        val plane = repo(port, sink)

        assertEquals(1, plane.syncRegion())

        val marker = sink.layers.single().spec.markers.single()
        assertEquals(HAZARD_SOURCE_COMMUNITY, marker.source)
        assertEquals("ДТП", marker.label)
        assertEquals(HAZARD_SOURCE_COMMUNITY, marker.detail)
    }

    @Test
    fun `чужа мітка за двадцять метрів не підмінює власну`() = runTest {
        val sink = FakeSink()
        sink.pushHazardMark("ДТП", 50.4500, 30.5200)
        // ≈8 м на північ від власної.
        val port = FakePort(rows = listOf(mark("ДТП", 50.45007, 30.5200, sink.now)))
        val plane = repo(port, sink)

        assertEquals(0, plane.syncRegion())

        val markers = sink.layers.single().spec.markers
        assertEquals(1, markers.size)
        assertNull("власна мітка перепідписалась чужою", markers.single().source)
    }

    @Test
    fun `чужа мітка гасне за власним TTL, не за життям шару`() = runTest {
        val sink = FakeSink()
        val port = FakePort(rows = listOf(mark("ДТП", 50.4600, 30.5400, sink.now, ttlMs = 10 * 60_000L)))
        val plane = repo(port, sink)
        plane.syncRegion()
        assertEquals(1, sink.layers.single().spec.markers.size)

        sink.now += 11 * 60_000L
        port.rows = emptyList()
        plane.syncRegion()

        assertTrue("прострочена чужа мітка лишилась на мапі", sink.layers.isEmpty())
    }

    @Test
    fun `регіон не питається частіше за інтервал`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink)

        plane.syncRegion()
        plane.syncRegion()
        assertEquals(1, port.pulls)

        sink.now += LiveMarksRepository.DEFAULT_PULL_INTERVAL_MS
        plane.syncRegion()
        assertEquals(2, port.pulls)
    }

    @Test
    fun `закрита мапа регіон не питає`() = runTest {
        val sink = FakeSink()
        val port = FakePort()
        val plane = repo(port, sink, region = { null })

        plane.syncRegion()

        assertEquals(0, port.pulls)
    }

    // ── local-first ──────────────────────────────────────────────────────

    @Test
    fun `без ключів площина не піднімається і не чіпає шар`() = runTest {
        val sink = FakeSink()
        val port = FakePort(isConfigured = false)
        val plane = repo(port, sink)
        sink.pushHazardMark("ДТП", 50.45, 30.52)
        val before = sink.layers.single().spec.markers

        plane.capture(sink.list(null))
        assertEquals(0, plane.drainOutbox())
        assertEquals(0, plane.syncRegion())

        assertEquals(0, port.pulls)
        assertTrue(port.pushed.isEmpty())
        assertEquals(before, sink.layers.single().spec.markers)
    }
}
