package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** R4.1 — contract tests for the `map.*` spine tools over a fake sink. */
class MapLayerToolsTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    /** In-memory [SpatialLayerSink] — the whole point of the pure port. */
    private class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        val focusRequests = mutableListOf<FocusTarget>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            require(spec.elementCount > 0)
            val id = UUID.randomUUID().toString()
            layers.add(0, SpatialLayer(id, spec, 0L, true, spec.ttlMs))
            return id
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) {
            val i = layers.indexOfFirst { it.id == layerId }
            if (i >= 0) layers[i] = layers[i].copy(visible = visible)
        }
        override suspend fun clear(layerId: String): Boolean =
            layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int {
            val before = layers.size
            layers.removeAll {
                (threadId == null || it.spec.ownerThreadId == threadId) &&
                    (origin == null || it.spec.origin == origin)
            }
            return before - layers.size
        }
        override suspend fun list(threadId: String?): List<SpatialLayer> =
            if (threadId == null) layers else layers.filter { it.spec.ownerThreadId == threadId }
        override suspend fun requestFocus(target: FocusTarget) { focusRequests.add(target) }
    }

    private fun mapArg() = buildJsonObject {
        put("latitude", 50.45)
        put("longitude", 30.52)
        put("zoom", 14.0)
        put("markers", buildJsonArray {
            add(buildJsonObject { put("lat", 50.451); put("lon", 30.523); put("label", "Аптека") })
            add(buildJsonObject { put("lat", 50.452); put("lon", 30.524); put("label", "Кафе") })
        })
        put("polylines", buildJsonArray {
            add(buildJsonObject {
                put("points", buildJsonArray {
                    add(buildJsonObject { put("lat", 50.451); put("lon", 30.523) })
                    add(buildJsonObject { put("lat", 50.452); put("lon", 30.524) })
                })
            })
        })
    }

    @Test
    fun `map show - persists a named thread-scoped layer and echoes the map`() = runTest {
        val sink = FakeSink()
        val tool = MapShowTool(sink) { "thread-42" }
        val res = tool.execute(buildJsonObject {
            put("title", "3 кафе поруч")
            put("map", mapArg())
            put("kind", "poi")
        }, ctx) {}
        assertTrue("expected Ok, got $res", res is ToolResult.Ok)
        assertEquals(1, sink.layers.size)
        val layer = sink.layers.first()
        assertEquals("3 кафе поруч", layer.spec.title)
        assertEquals("thread-42", layer.spec.ownerThreadId)
        assertEquals(LayerOrigin.PHANTOM, layer.spec.origin)
        assertEquals(2, layer.spec.markers.size)
        assertEquals(1, layer.spec.lines.size)
        // echoes the widget-ready map for the inline MapWidget
        assertNotNull((res as ToolResult.Ok).structured!!["map"])
        // auto-focuses the new layer
        assertEquals(1, sink.focusRequests.size)
    }

    @Test
    fun `map show - ttl_min sets an expiry window`() = runTest {
        val sink = FakeSink()
        val tool = MapShowTool(sink) { null }
        tool.execute(buildJsonObject {
            put("title", "живий шар")
            put("origin", "world")
            put("ttl_min", 5)
            put("map", mapArg())
        }, ctx) {}
        val layer = sink.layers.first()
        assertEquals(LayerOrigin.WORLD, layer.spec.origin)
        assertEquals(5 * 60_000L, layer.spec.ttlMs)
    }

    @Test
    fun `map show - empty geometry is refused (Law 1)`() = runTest {
        val sink = FakeSink()
        val tool = MapShowTool(sink) { null }
        val res = tool.execute(buildJsonObject {
            put("title", "порожньо")
            put("map", buildJsonObject { put("latitude", 50.0); put("longitude", 30.0) })
        }, ctx) {}
        assertTrue(res is ToolResult.Err)
        assertEquals("empty_layer", (res as ToolResult.Err).code)
        assertTrue(sink.layers.isEmpty())
    }

    @Test
    fun `map focus - by explicit coords emits a focus request`() = runTest {
        val sink = FakeSink()
        val tool = MapFocusTool(sink) { null }
        val res = tool.execute(buildJsonObject { put("lat", 48.9); put("lon", 24.7) }, ctx) {}
        assertTrue(res is ToolResult.Ok)
        assertEquals(48.9, sink.focusRequests.single().lat, 1e-9)
    }

    @Test
    fun `map focus - by layer id centres on the layer`() = runTest {
        val sink = FakeSink()
        val id = MapShowTool(sink) { "t" }.let {
            it.execute(buildJsonObject { put("title", "x"); put("map", mapArg()) }, ctx) {}
            sink.layers.first().id
        }
        sink.focusRequests.clear()
        val res = MapFocusTool(sink) { "t" }
            .execute(buildJsonObject { put("layer_id", id) }, ctx) {}
        assertTrue(res is ToolResult.Ok)
        val f = sink.focusRequests.single()
        assertEquals(id, f.layerId)
        assertTrue(f.lat in 50.45..50.46)
    }

    @Test
    fun `map clear - by id removes one layer`() = runTest {
        val sink = FakeSink()
        MapShowTool(sink) { "t" }.execute(buildJsonObject { put("title", "a"); put("map", mapArg()) }, ctx) {}
        val id = sink.layers.first().id
        val res = MapClearTool(sink) { "t" }.execute(buildJsonObject { put("layer_id", id) }, ctx) {}
        assertTrue(res is ToolResult.Ok)
        assertTrue(sink.layers.isEmpty())
    }

    @Test
    fun `map clear - no id clears this thread's phantom and world layers`() = runTest {
        val sink = FakeSink()
        MapShowTool(sink) { "t" }.execute(buildJsonObject { put("title", "a"); put("map", mapArg()) }, ctx) {}
        MapShowTool(sink) { "t" }.execute(buildJsonObject { put("title", "b"); put("origin", "world"); put("map", mapArg()) }, ctx) {}
        // a layer in another thread must survive
        MapShowTool(sink) { "other" }.execute(buildJsonObject { put("title", "c"); put("map", mapArg()) }, ctx) {}
        val res = MapClearTool(sink) { "t" }.execute(buildJsonObject {}, ctx) {}
        assertTrue(res is ToolResult.Ok)
        assertEquals(1, sink.layers.size)
        assertEquals("other", sink.layers.first().spec.ownerThreadId)
    }
}
