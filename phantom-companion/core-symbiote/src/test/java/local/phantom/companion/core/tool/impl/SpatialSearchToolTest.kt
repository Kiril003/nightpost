package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialSearchToolTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_spatial_search_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun executesSpatialSearch() = runTest {
        val tool = SpatialSearchTool(
            executeSearch = { q, _, _, _ ->
                SpatialSearchExecutionResult(
                    query = q,
                    items = listOf(
                        SpatialSearchItemOutput(
                            id = "cat_energy_generator",
                            title = "Генератори",
                            subtitle = "Пошук автономного електроживлення та світла",
                            kind = "CATEGORY_INTENT",
                            icon = "⚡",
                            lat = null,
                            lon = null,
                            distanceM = null,
                            compassDir = null,
                            clockDir = null,
                            isCritical = true,
                        )
                    ),
                    summary = "Знайдено 1 варіант: Генератори",
                )
            }
        )

        val args = buildJsonObject {
            put("query", "генератор")
        }

        val result = tool.execute(args, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue(ok.output.contains("Генератори"))
    }
}
