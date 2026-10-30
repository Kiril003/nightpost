package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.ai.context.FixGrade
import local.phantom.companion.core.ai.context.LocationContextProvider
import local.phantom.companion.core.ai.context.LocationFix
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavExfilToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_exfil_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun returnsErrorWhenLocationIsUnavailable() = runTest {
        val tool = NavExfilTool(
            locationProvider = null,
            requestExfil = { true },
        )

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("location_unavailable", err.code)
    }

    @Test
    fun triggersExfilWhenLocationIsAvailable() = runTest {
        var exfilTriggered = false
        var mapOpened = false

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

        val tool = NavExfilTool(
            locationProvider = provider,
            requestExfil = {
                exfilTriggered = true
                true
            },
            requestMapOpen = { mapOpened = true },
        )

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        assertTrue(exfilTriggered)
        assertTrue(mapOpened)
    }
}
