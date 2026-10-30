package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AtmosphericRadarToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_radar_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun queriesRadarAtmosphere() = runTest {
        val tool = AtmosphericRadarTool(
            getRadar = { _, _ ->
                RadarResult(
                    conditionType = "MODERATE_RAIN",
                    reflectivityDbz = 35,
                    temperatureC = 16.0,
                    windKmh = 20.0,
                    windDir = "Південний",
                    activeLayer = "layer_radar_rain_moderate",
                    brief = "Помірний дощ, радар 35 dBZ, вітер Південний 20 км/год.",
                    observedAtMs = System.currentTimeMillis(),
                )
            }
        )

        val args = buildJsonObject {
            put("lat", 50.450)
            put("lon", 30.520)
        }

        val result = tool.execute(args, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue(ok.output.contains("35 dBZ"))
        assertTrue(ok.output.contains("Помірний дощ"))
        assertNotNull("показник поїхав без часу виміру", ok.structured?.get("observed_ts"))
    }

    /** Дві години — це вже не «зараз»; вік має їхати словом, а не мовчки. */
    @Test
    fun staleReadingCarriesItsAge() = runTest {
        val tool = AtmosphericRadarTool(
            getRadar = { _, _ ->
                RadarResult(
                    conditionType = "CLEAR",
                    reflectivityDbz = 0,
                    temperatureC = 16.0,
                    windKmh = 20.0,
                    windDir = "Південний",
                    activeLayer = null,
                    brief = "Небо ясне, температура 16°C.",
                    observedAtMs = System.currentTimeMillis() - 120L * 60_000L,
                )
            }
        )

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        assertTrue((result as ToolResult.Ok).output.contains("хв тому"))
    }
}
