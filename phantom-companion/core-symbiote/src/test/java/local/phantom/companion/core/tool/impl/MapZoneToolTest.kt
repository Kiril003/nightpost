package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** ATLAS-5 — `map.zone` contract tests over the same fake sink as its siblings. */
class MapZoneToolTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    private class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        val focusRequests = mutableListOf<FocusTarget>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            require(spec.elementCount > 0)
            val id = UUID.randomUUID().toString()
            layers.add(0, SpatialLayer(id, spec, 0L, true, spec.ttlMs))
            return id
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> = layers
        override suspend fun requestFocus(target: FocusTarget) { focusRequests.add(target) }
    }

    @Test
    fun `an exclusion circle lands as a red TTL'd region layer`() = runTest {
        val sink = FakeSink()
        val tool = MapZoneTool(sink) { "thread-1" }
        val res = tool.execute(
            buildJsonObject {
                put("zone", "exclusion")
                put("label", "обстріл перехрестя")
                put("lat", 50.45)
                put("lon", 30.52)
                put("radius_m", 400.0)
            },
            ctx,
        ) { }
        assertTrue(res is ToolResult.Ok)
        val layer = sink.layers.single()
        assertEquals(1, layer.spec.regions.size)
        assertEquals("#EF4444", layer.spec.regions[0].color)
        assertEquals("zone-exclusion", layer.spec.kind)
        assertEquals("thread-1", layer.spec.ownerThreadId)
        // Default TTL: two hours, not forever.
        assertEquals(2 * 60 * 60_000L, layer.spec.ttlMs)
        // The camera was asked to look at the verdict.
        assertEquals(1, sink.focusRequests.size)
    }

    @Test
    fun `a safe corridor lands as a wide green line`() = runTest {
        val sink = FakeSink()
        val tool = MapZoneTool(sink) { null }
        val res = tool.execute(
            buildJsonObject {
                put("zone", "safe")
                put("label", "тихий хід до укриття")
                put("points", buildJsonArray {
                    add("50.4500,30.5200")
                    add("50.4520,30.5210")
                    add("50.4540,30.5230")
                })
            },
            ctx,
        ) { }
        assertTrue(res is ToolResult.Ok)
        val layer = sink.layers.single()
        assertEquals(1, layer.spec.lines.size)
        assertEquals(3, layer.spec.lines[0].points.size)
        assertEquals("#22C55E", layer.spec.lines[0].color)
    }

    @Test
    fun `ttl_min zero pins the zone permanently`() = runTest {
        val sink = FakeSink()
        val tool = MapZoneTool(sink) { null }
        tool.execute(
            buildJsonObject {
                put("zone", "watch")
                put("label", "міст")
                put("lat", 50.45)
                put("lon", 30.52)
                put("radius_m", 250.0)
                put("ttl_min", 0.0)
            },
            ctx,
        ) { }
        assertNull(sink.layers.single().spec.ttlMs)
    }

    @Test
    fun `an unknown verdict is refused`() = runTest {
        val sink = FakeSink()
        val tool = MapZoneTool(sink) { null }
        val res = tool.execute(
            buildJsonObject {
                put("zone", "party")
                put("label", "свято")
                put("lat", 50.45)
                put("lon", 30.52)
                put("radius_m", 100.0)
            },
            ctx,
        ) { }
        assertTrue(res is ToolResult.Err)
        assertTrue(sink.layers.isEmpty())
    }

    @Test
    fun `no geometry is an honest refusal, not an empty layer`() = runTest {
        val sink = FakeSink()
        val tool = MapZoneTool(sink) { null }
        val res = tool.execute(
            buildJsonObject {
                put("zone", "exclusion")
                put("label", "щось")
                // one corridor point is not a corridor; no circle either
                put("points", buildJsonArray { add("50.45,30.52") })
            },
            ctx,
        ) { }
        assertTrue(res is ToolResult.Err)
        assertTrue(sink.layers.isEmpty())
    }
}
