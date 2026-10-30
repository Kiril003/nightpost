package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * F-02 — the whole point of this tool is that the answer comes off the ROAD.
 * These tests build the case the straight-line implementation gets wrong: the
 * facility nearest by line sits across water, and the road answer is a
 * different building.
 */
class MedevacNearestToolTest {

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

    private val here = LocationFix(
        lat = 50.4501, lon = 30.5234, accuracyM = 4f, grade = FixGrade.NAVIGATION,
    )
    private val fixed = LocationContextProvider { here }

    /** Across the river: 900 m by line, no road. */
    private val acrossWater = MedevacPoi("Лікарня за річкою", 50.4581, 30.5234, mapOf("emergency" to "yes"))

    /** Down the road: 2.5 km by line, but a road actually goes there. */
    private val downTheRoad = MedevacPoi("Міська лікарня №7", 50.4726, 30.5234, emptyMap())

    private val village = MedevacPoi("Іванівка", 50.4400, 30.5234, mapOf("place" to "village"))

    private fun lookup(facilities: List<MedevacPoi>, places: List<MedevacPoi> = listOf(village)): PoiLookupFn =
        { _, _, _, selector -> if (selector.startsWith("place")) places else facilities }

    @Test
    fun `the nearest by line is not the answer when the road says otherwise`() = runTest {
        val sink = FakeSink()
        val tool = MedevacNearestTool(
            findPois = lookup(listOf(acrossWater, downTheRoad)),
            sink = sink,
            locationProvider = fixed,
            currentThreadId = { "t1" },
            routeMatrix = { _, _, _, dest ->
                // The line-nearest has no route at all; the further one does.
                dest.map { (lat, _) -> if (lat == acrossWater.lat) null else 600.0 }
            },
            routeLeg = { _, _, _, tLat, _ ->
                if (tLat == acrossWater.lat) null else RoutedLeg(4_200.0, 600.0, listOf(50.4501 to 30.5234, tLat to 30.5234))
            },
        )
        val res = tool.execute(buildJsonObject { }, ctx) { }
        assertTrue(res is ToolResult.Ok)
        val ok = res as ToolResult.Ok
        assertEquals("Міська лікарня №7", ok.structured!!["name"]?.jsonPrimitive?.content)
        assertEquals(true, ok.structured!!["routed"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("routed_duration", ok.structured!!["ranked_by"]?.jsonPrimitive?.content)
        // The routed distance is what the crew is told, not the 890 m of line.
        assertEquals("4200.0", ok.structured!!["routed_distance_m"]?.jsonPrimitive?.content)
        assertTrue(ok.output, ok.output.contains("4.2 км"))
        assertTrue(ok.output, ok.output.contains("ЗА ДОРОГОЮ"))
        assertEquals(1, sink.layers.size)
        assertEquals("medevac_route", sink.layers.single().spec.kind)
    }

    @Test
    fun `when nothing routes the answer is a refusal, not a straight line`() = runTest {
        val sink = FakeSink()
        val tool = MedevacNearestTool(
            findPois = lookup(listOf(acrossWater, downTheRoad)),
            sink = sink,
            locationProvider = fixed,
            currentThreadId = { "t1" },
            routeMatrix = { _, _, _, dest -> dest.map { null } },
            routeLeg = { _, _, _, _, _ -> null },
        )
        val res = tool.execute(buildJsonObject { }, ctx) { }
        assertTrue(res is ToolResult.Ok)
        val ok = res as ToolResult.Ok
        assertTrue(ok.output, ok.output.contains("МАРШРУТУ НЕМАЄ"))
        assertEquals("no_route", ok.structured!!["refusal"]!!.jsonPrimitive.content)
        assertEquals(false, ok.structured!!["routed"]!!.jsonPrimitive.content.toBoolean())
        // No number wearing the word "distance". These are the exact strings a
        // great-circle implementation prints for these two points.
        assertFalse(ok.output, ok.output.contains("890 м"))
        assertFalse(ok.output, ok.output.contains("2.5 км"))
        assertFalse(ok.structured!!.containsKey("routed_distance_m"))
        assertTrue(sink.layers.isEmpty())
    }

    @Test
    fun `a router that never answered is not reported as a road that does not exist`() = runTest {
        val tool = MedevacNearestTool(
            findPois = lookup(listOf(downTheRoad)),
            sink = FakeSink(),
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { _, _, _, _ -> null },
            routeLeg = { _, _, _, _, _ -> null },
        )
        val ok = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertEquals(false, ok.structured!!["router_answered"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(ok.output, ok.output.contains("маршрутизатор не відповів"))
    }

    @Test
    fun `the handover card is generated unasked on every medevac path`() = runTest {
        val tool = MedevacNearestTool(
            findPois = lookup(listOf(downTheRoad)),
            sink = FakeSink(),
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { _, _, _, dest -> dest.map { 600.0 } },
            routeLeg = { _, _, _, tLat, _ -> RoutedLeg(4_200.0, 600.0, listOf(50.4501 to 30.5234, tLat to 30.5234)) },
        )
        val ok = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(ok.output, ok.output.contains("36U UA 24182 91607"))
        assertTrue(ok.output, ok.output.contains("Plus Code"))
        assertTrue(ok.output, ok.output.contains("Іванівка"))
        assertTrue(ok.structured!!.containsKey("handover"))
    }

    @Test
    fun `no facility at all still hands the crew its own position`() = runTest {
        val tool = MedevacNearestTool(
            findPois = lookup(emptyList()),
            sink = FakeSink(),
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { _, _, _, _ -> null },
            routeLeg = { _, _, _, _, _ -> null },
        )
        val ok = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertEquals("no_candidates", ok.structured!!["refusal"]!!.jsonPrimitive.content)
        assertTrue(ok.output, ok.output.contains("36U UA 24182 91607"))
    }

    @Test
    fun `an untagged ER is never rendered as an absent one`() {
        assertTrue(erLine(mapOf("emergency" to "yes")).contains("позначено"))
        val untagged = erLine(emptyMap())
        assertTrue(untagged, untagged.contains("НЕВІДОМО"))
        assertTrue(untagged, untagged.contains("не означає"))
    }

    @Test
    fun `a casualty run drives unless the operator says otherwise`() = runTest {
        var seen: RouteProfile? = null
        val tool = MedevacNearestTool(
            findPois = lookup(listOf(downTheRoad)),
            sink = FakeSink(),
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { p, _, _, dest -> seen = p; dest.map { 600.0 } },
            routeLeg = { _, _, _, tLat, _ -> RoutedLeg(4_200.0, 600.0, listOf(50.45 to 30.52, tLat to 30.5234)) },
        )
        tool.execute(buildJsonObject { }, ctx) { }
        assertEquals(RouteProfile.CAR, seen)
        tool.execute(buildJsonObject { put("profile", "пішки") }, ctx) { }
        assertEquals(RouteProfile.FOOT, seen)
    }

    @Test
    fun `a caller-supplied point does not inherit the device fix's accuracy`() = runTest {
        val tool = HandoverCardTool(findPois = lookup(emptyList()), locationProvider = fixed)
        val own = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        // ±4 m fix earns five digits.
        assertEquals(5, (own.structured!!["handover"] as JsonObject)["mgrs_digits"]!!.jsonPrimitive.content.toInt())
        val foreign = tool.execute(
            buildJsonObject { put("lat", 50.4501); put("lon", 30.5234) }, ctx,
        ) { } as ToolResult.Ok
        // The same point handed in by a caller carries no accuracy at all.
        assertEquals(3, (foreign.structured!!["handover"] as JsonObject)["mgrs_digits"]!!.jsonPrimitive.content.toInt())
    }
}
