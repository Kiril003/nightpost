package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertTrue
import org.junit.Test

class ShelterVectorToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_shelter_vec_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun executesShelterVectorLookup() = runTest {
        val tool = ShelterVectorTool(
            getVector = { _, _, _ ->
                ShelterVectorResult(
                    name = "Укриття №12",
                    distanceM = 150,
                    bearingDeg = 45,
                    compassDir = "Північний Схід",
                    clockDir = "на 2 годин",
                    walkEtaMin = 2,
                    runEtaMin = 1,
                    instruction = "Найближче укриття: Укриття №12. Дистанція: 150 м, напрямок Північний Схід (на 2 годин).",
                )
            }
        )

        val args = buildJsonObject {
            put("lat", 50.450)
            put("lon", 30.520)
            put("heading_deg", 0f)
        }

        val result = tool.execute(args, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue(ok.output.contains("Укриття №12"))
        assertTrue(ok.output.contains("150 м"))
    }
}
