package local.phantom.companion.core.data.spatial

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Три руки пишуть один шар міток. Без спільного замка read-merge-write
 * паралельний запис губить чужу мітку — realtime підняв частоту записів
 * на порядок і розкрив вікно. Замок живе у HazardMarks, не в кожній руці.
 */
class HazardMarksConcurrencyTest {

    private class RacySink : SpatialLayerSink {
        val layers = LinkedHashMap<String, SpatialLayer>()
        val ids = AtomicInteger(0)

        override suspend fun push(spec: SpatialLayerSpec): String {
            delay(1)
            val id = "L${ids.incrementAndGet()}"
            layers[id] = SpatialLayer(id = id, spec = spec, createdAt = 0L, visible = true, expiresAt = null)
            return id
        }

        override suspend fun setVisible(layerId: String, visible: Boolean) {}

        override suspend fun clear(layerId: String): Boolean = layers.remove(layerId) != null

        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0

        override suspend fun list(threadId: String?): List<SpatialLayer> {
            delay(1)
            return layers.values.toList()
        }

        override suspend fun requestFocus(target: FocusTarget) {}
    }

    @Test
    fun `parallel pushes lose no marks`() = runBlocking(Dispatchers.Default) {
        val sink = RacySink()
        val n = 24
        (0 until n).map { i ->
            // >20м нарізно, щоб merge-радіус нікого не склеїв.
            async { sink.pushHazardMark(kind = "ДТП", lat = 50.0 + i * 0.01, lon = 30.0) }
        }.awaitAll()
        val marks = sink.hazardLayer()?.spec?.markers.orEmpty()
        assertEquals("паралельні записи загубили мітки", n, marks.size)
    }
}
