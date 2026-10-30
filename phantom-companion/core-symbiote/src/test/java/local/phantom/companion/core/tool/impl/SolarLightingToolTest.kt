package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertTrue
import org.junit.Test

class SolarLightingToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_solar_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun queriesSolarLightingTelemetry() = runTest {
        val tool = SolarLightingTool(
            getSolar = { _, _, _ ->
                SolarLightingResult(
                    elevationDeg = 45.0,
                    azimuthDeg = 180.0,
                    phase = "FULL_DAYLIGHT",
                    intensityLux = 80000f,
                    colorTempK = 5800,
                    description = "Денне світло: висота сонця 45°, азимут 180°, освітленість 80000 лк.",
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
        assertTrue(ok.output.contains("45°"))
        assertTrue(ok.output.contains("Денне світло"))
    }
}
