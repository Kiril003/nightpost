package local.phantom.companion.core.tool.impl

import android.app.Application
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/** R4.4–R4.6 — the living-world tools parse REAL feeds and push WORLD layers. */
class WorldToolsTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "t",
        requestId = "r",
        approvedPermissions = setOf(ToolPermission.NETWORK, ToolPermission.LOCATION),
    )
    private val kyiv = LocationContextProvider { LocationFix(50.4501, 30.5234) }

    private class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        val focus = mutableListOf<FocusTarget>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            val id = UUID.randomUUID().toString()
            layers.add(0, SpatialLayer(id, spec, 0L, true, spec.ttlMs)); return id
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) {}
        override suspend fun clear(layerId: String) = layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?) = 0
        override suspend fun list(threadId: String?) =
            layers.filter { threadId == null || it.spec.ownerThreadId == threadId }
        override suspend fun requestFocus(target: FocusTarget) { focus.add(target) }
    }

    private fun http(body: String) =
        GeoHttp(HttpClient(MockEngine { respond(content = body, status = HttpStatusCode.OK) }))

    @Test
    fun `transit nearby - parses stops by mode and pushes a world layer`() = runTest {
        val overpass = """
          {"elements":[
            {"type":"node","lat":50.4502,"lon":30.5230,"tags":{"highway":"bus_stop","name":"Хрещатик","route_ref":"5;24"}},
            {"type":"node","lat":50.4510,"lon":30.5240,"tags":{"railway":"tram_stop","name":"Поштова"}},
            {"type":"node","lat":50.4490,"lon":30.5250,"tags":{"station":"subway","railway":"station","name":"Майдан"}}
          ]}
        """.trimIndent()
        val sink = FakeSink()
        val tool = TransitNearbyTool(http(overpass), sink, kyiv, { "t1" })
        val res = tool.execute(buildJsonObject {}, ctx) {}
        assertTrue("expected Ok, got $res", res is ToolResult.Ok)
        val layer = sink.layers.single()
        assertEquals(LayerOrigin.WORLD, layer.spec.origin)
        assertEquals("transit", layer.spec.kind)
        assertEquals(3, layer.spec.markers.size)
        assertTrue(layer.spec.markers.any { it.glyph == "трамвай" })
        assertTrue(layer.spec.markers.any { it.glyph == "метро" })
        assertTrue(layer.spec.markers.first { it.label == "Хрещатик" }.detail!!.contains("5;24"))
        // widget map echoed
        assertTrue((res as ToolResult.Ok).structured!!["map"]!!.jsonObject.containsKey("markers"))
    }

    @Test
    fun `transit routes - concatenates member geometry into named lines`() = runTest {
        val overpass = """
          {"elements":[
            {"type":"relation","tags":{"route":"tram","ref":"1","name":"Трамвай 1"},
             "members":[{"type":"way","geometry":[{"lat":50.45,"lon":30.52},{"lat":50.46,"lon":30.53}]},
                        {"type":"way","geometry":[{"lat":50.46,"lon":30.53},{"lat":50.47,"lon":30.54}]}]}
          ]}
        """.trimIndent()
        val sink = FakeSink()
        val tool = TransitRoutesTool(http(overpass), sink, kyiv, { "t1" })
        val res = tool.execute(buildJsonObject {}, ctx) {}
        assertTrue(res is ToolResult.Ok)
        val layer = sink.layers.single()
        assertEquals(1, layer.spec.lines.size)
        // both member ways concatenated (2 + 2, incl. the shared join vertex)
        assertEquals(4, layer.spec.lines.first().points.size)
        assertEquals("№1", layer.spec.lines.first().label)
    }

    @Test
    fun `world cameras - splits surveillance from speed cameras`() = runTest {
        val overpass = """
          {"elements":[
            {"type":"node","lat":50.451,"lon":30.523,"tags":{"man_made":"surveillance","surveillance:type":"camera"}},
            {"type":"node","lat":50.452,"lon":30.524,"tags":{"highway":"speed_camera"}},
            {"type":"node","lat":50.453,"lon":30.525,"tags":{"enforcement":"maxspeed"}}
          ]}
        """.trimIndent()
        val sink = FakeSink()
        val tool = WorldCamerasTool(http(overpass), sink, kyiv, { "t1" })
        val res = tool.execute(buildJsonObject {}, ctx) {}
        assertTrue(res is ToolResult.Ok)
        val s = (res as ToolResult.Ok).structured!!
        assertEquals(1, s["surveillance"]!!.jsonPrimitive.content.toInt())
        assertEquals(2, s["speed_cameras"]!!.jsonPrimitive.content.toInt())
        assertEquals("cameras", sink.layers.single().spec.kind)
    }

    @Test
    fun `world aircraft - parses ADS-B into a live layer with heading`() = runTest {
        val adsb = """
          {"ac":[
            {"hex":"abc123","flight":"UIA123 ","lat":50.46,"lon":30.55,"alt_baro":33000,"gs":420,"track":270,"t":"B738"},
            {"hex":"def456","r":"UR-ABC","lat":50.40,"lon":30.50,"alt_baro":"ground","gs":0,"track":90}
          ]}
        """.trimIndent()
        val sink = FakeSink()
        val tool = WorldAircraftTool(http(adsb), sink, kyiv, { "t1" })
        val res = tool.execute(buildJsonObject {}, ctx) {}
        assertTrue("expected Ok, got $res", res is ToolResult.Ok)
        val layer = sink.layers.single()
        assertEquals("aircraft", layer.spec.kind)
        assertEquals(2, layer.spec.markers.size)
        val ua = layer.spec.markers.first { it.label == "UIA123" }
        assertEquals(270.0, ua.bearing!!, 1e-9)
        assertTrue(ua.detail!!.contains("B738"))
        // live layer self-expires
        assertEquals(4 * 60_000L, layer.spec.ttlMs)
    }

    @Test
    fun `world report - appends operator self-report and focuses it`() = runTest {
        val sink = FakeSink()
        val tool = HazardReportTool(sink, kyiv, { "t1" })
        tool.execute(buildJsonObject { put("kind", "ДТП"); put("note", "на розі") }, ctx) {}
        assertEquals(1, sink.layers.size)
        assertEquals("hazard", sink.layers.first().spec.kind)
        // a second report at a different point appends to the same layer
        val res = tool.execute(buildJsonObject {
            put("kind", "дтп"); put("lat", 50.40); put("lon", 30.40)
        }, ctx) {}
        assertTrue(res is ToolResult.Ok)
        assertEquals(1, sink.layers.size) // still one merged layer
        assertEquals(2, sink.layers.first().spec.markers.size)
        assertTrue(sink.focus.isNotEmpty())
    }

    @Test
    fun `world report - the model is never offered police, patrol or checkpoint marks`() {
        val tool = HazardReportTool(FakeSink(), kyiv, { "t1" })
        val offered = Regex("""kind \(([^)]*)\)""").find(tool.description)!!.groupValues[1].split('|')
        assertEquals(listOf("дтп", "ремонт", "затор", "небезпека", "камера", "інше"), offered)
        val stems = listOf("патрул", "поліц", "блокпост", "patrol", "police", "checkpoint")
        assertEquals(emptyList<String>(), stems.filter { tool.description.contains(it, ignoreCase = true) })
        offered.forEach { assertTrue("«$it» відмовлено", !local.phantom.companion.core.data.spatial.isRefusedMarkKind(it)) }
    }
}
