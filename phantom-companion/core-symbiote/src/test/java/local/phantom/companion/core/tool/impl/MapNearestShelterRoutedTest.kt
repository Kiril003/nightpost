package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.data.poi.OfflinePoiPack
import local.phantom.companion.core.data.poi.PoiDao
import local.phantom.companion.core.data.poi.PoiEntity
import local.phantom.companion.core.data.poi.PoiRepository
import local.phantom.companion.core.data.spatial.FocusTarget
import local.phantom.companion.core.data.spatial.LayerOrigin
import local.phantom.companion.core.data.spatial.SpatialLayer
import local.phantom.companion.core.data.spatial.SpatialLayerSink
import local.phantom.companion.core.data.spatial.SpatialLayerSpec
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * F-02 applied to `shelter.nearest`: under a siren the shelter across the rail
 * cutting is not the nearest one, and "no walkable route" is an answer the
 * operator must be given rather than a straight-line number.
 */
class MapNearestShelterRoutedTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    private class FakeDao : PoiDao {
        override fun getPoisInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double,
        ): Flow<List<PoiEntity>> = flowOf(emptyList())
        override suspend fun insertPois(pois: List<PoiEntity>) = Unit
        override suspend fun clearExpired(expiryTime: Long) = Unit
    }

    private class FakeSink : SpatialLayerSink {
        val layers = mutableListOf<SpatialLayer>()
        override suspend fun push(spec: SpatialLayerSpec): String {
            val id = UUID.randomUUID().toString()
            layers.add(0, SpatialLayer(id, spec, 0L, true, spec.ttlMs))
            return id
        }
        override suspend fun setVisible(layerId: String, visible: Boolean) = Unit
        override suspend fun clear(layerId: String): Boolean = layers.removeAll { it.id == layerId }
        override suspend fun clearThread(threadId: String?, origin: LayerOrigin?): Int = 0
        override suspend fun list(threadId: String?): List<SpatialLayer> = layers
        override suspend fun requestFocus(target: FocusTarget) = Unit
    }

    /**
     * Near by line (≈220 m), no footway across; and far by line (≈780 m), walkable.
     * Обидва несуть `shelter_type` — без нього це навіси, і інструмент їх не бере.
     */
    private val pack = OfflinePoiPack.parse(
        """
        {"pois":[
          {"id":1,"lat":50.45210,"lon":30.52340,"name":"Підвал за колією","cat":"amenity","sub":"shelter",
           "tags":{"amenity":"shelter","shelter_type":"bomb_shelter"}},
          {"id":2,"lat":50.45710,"lon":30.52340,"name":"Паркінг ТРЦ","cat":"amenity","sub":"shelter",
           "tags":{"amenity":"shelter","shelter_type":"bomb_shelter"}}
        ]}
        """.trimIndent(),
    )

    private val repo = PoiRepository(poiDao = FakeDao(), overpassClient = null, offlinePack = { pack })

    private val fixed = LocationContextProvider {
        LocationFix(lat = 50.4501, lon = 30.5234, accuracyM = 6f, grade = FixGrade.NAVIGATION)
    }

    @Test
    fun `the shelter you can walk to wins over the one that is merely closer`() = runTest {
        val sink = FakeSink()
        val tool = MapNearestShelterTool(
            poiRepository = repo,
            sink = sink,
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { _, _, _, dest -> dest.map { (lat, _) -> if (lat < 50.454) null else 540.0 } },
            routeLeg = { _, _, _, tLat, tLon ->
                if (tLat < 50.454) null else RoutedLeg(760.0, 540.0, listOf(50.4501 to 30.5234, tLat to tLon))
            },
        )
        val ok = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertEquals("Паркінг ТРЦ", ok.structured!!["name"]?.jsonPrimitive?.content)
        assertEquals("routed_duration", ok.structured!!["ranked_by"]?.jsonPrimitive?.content)
        assertTrue(ok.output, ok.output.contains("ЗА ДОРОГОЮ"))
        assertEquals(1, sink.layers.size)
    }

    @Test
    fun `no walkable route is said plainly, with no straight-line number`() = runTest {
        val sink = FakeSink()
        val tool = MapNearestShelterTool(
            poiRepository = repo,
            sink = sink,
            locationProvider = fixed,
            currentThreadId = { null },
            routeMatrix = { _, _, _, dest -> dest.map { null } },
            routeLeg = { _, _, _, _, _ -> null },
        )
        val ok = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(ok.output, ok.output.contains("МАРШРУТУ НЕМАЄ"))
        assertEquals("no_route", ok.structured!!["refusal"]!!.jsonPrimitive.content)
        // 222 м / 778 м are what a great-circle answer would have printed.
        assertFalse(ok.output, ok.output.contains("222 м"))
        assertFalse(ok.output, ok.output.contains("778 м"))
        assertTrue(sink.layers.isEmpty())
    }
}
