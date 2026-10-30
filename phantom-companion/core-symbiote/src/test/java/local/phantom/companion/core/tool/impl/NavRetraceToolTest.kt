package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import local.phantom.companion.core.net.map.RouteProfile
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRetraceToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun returnsErrorWhenTrackIsEmpty() = runTest {
        val tool = NavRetraceTool(
            buildRetraceRoute = { null },
            requestNavStart = { _, _, _, _ -> },
        )

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("insufficient_breadcrumbs", err.code)
    }

    @Test
    fun startsNavigationWhenTrackIsValid() = runTest {
        var startTarget: Pair<Double, Double>? = null
        var startProfile: RouteProfile? = null
        var startVia: List<Pair<Double, Double>>? = null
        var mapOpened = false

        val samplePoints = listOf(
            50.455 to 30.525,
            50.453 to 30.523,
            50.450 to 30.520,
        )

        val tool = NavRetraceTool(
            buildRetraceRoute = { samplePoints },
            requestNavStart = { toLat, toLon, profile, via ->
                startTarget = toLat to toLon
                startProfile = profile
                startVia = via
            },
            requestMapOpen = { mapOpened = true },
        )

        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        assertTrue(mapOpened)
        assertEquals(50.450, startTarget?.first ?: 0.0, 1e-4)
        assertEquals(30.520, startTarget?.second ?: 0.0, 1e-4)
        assertEquals(RouteProfile.FOOT, startProfile)
        assertEquals(1, startVia?.size)
        assertEquals(50.453, startVia?.first()?.first ?: 0.0, 1e-4)
    }
}
