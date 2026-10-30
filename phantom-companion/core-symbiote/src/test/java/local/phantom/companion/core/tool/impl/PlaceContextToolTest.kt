package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceContextToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_place_ctx_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun queriesPlaceContext() = runTest {
        val tool = PlaceContextTool(
            getContext = { _, _, _ ->
                PlaceContextResult(
                    mode = "HOME_BASE",
                    activeAnchor = "База (Київ)",
                    speedKmh = 0.0,
                    homeSentryActive = false,
                    suggestedMapMode = "ZEN_MINIMAL",
                    summary = "Оператор на домашній базі (База (Київ)). Дистанційна варта вимкнена.",
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
        assertTrue(ok.output.contains("домашній базі"))
        assertTrue(ok.output.contains("База (Київ)"))
    }
}
