package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelFenceToolsTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_fence_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun setsAndListsGeofences() = runTest {
        val memoryFences = mutableListOf<FenceItem>()

        val provider = LocationContextProvider {
            LocationFix(
                lat = 50.450,
                lon = 30.520,
                bearing = 0f,
                accuracyM = 5f,
                speedMps = 0f,
                grade = FixGrade.NAVIGATION,
            )
        }

        val setTool = FenceSetTool(
            locationProvider = provider,
            setFence = { label, radiusM, kind, lat, lon ->
                val id = "fence_${memoryFences.size + 1}"
                memoryFences += FenceItem(id, label, radiusM, kind, lat, lon)
                id
            },
        )

        val listTool = FenceListTool(
            listFences = { memoryFences },
        )

        val setArgs = buildJsonObject {
            put("label", "Базовий табір")
            put("radius_m", 400.0)
            put("kind", "safe")
        }

        val setResult = setTool.execute(setArgs, ctx()) {}
        assertTrue(setResult is ToolResult.Ok)
        val setOk = setResult as ToolResult.Ok
        assertTrue(setOk.output.contains("Базовий табір"))
        assertTrue(setOk.output.contains("400 м"))

        val listResult = listTool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(listResult is ToolResult.Ok)
        val listOk = listResult as ToolResult.Ok
        assertTrue(listOk.output.contains("Активних геозон: 1"))
        assertTrue(listOk.output.contains("Базовий табір"))
    }
}
