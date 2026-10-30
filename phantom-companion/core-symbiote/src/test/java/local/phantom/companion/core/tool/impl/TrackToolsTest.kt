package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackToolsTest {

    private fun ctx() = ToolContext(
        appContext = Application(),
        operatorId = "operator",
        requestId = "req_track_1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun exportsTrackDataSuccessfully() = runTest {
        val tool = TrackExportTool(
            exportCurrentTrack = { format ->
                ExportedTrack(
                    format = format,
                    pointCount = 42,
                    data = "<gpx><trk><trkseg><trkpt lat=\"50.45\" lon=\"30.52\"/></trkseg></trk></gpx>",
                )
            },
        )

        val args = buildJsonObject {
            put("format", "gpx")
        }

        val result = tool.execute(args, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertTrue(ok.output.contains("42 точок"))
        assertTrue(ok.output.contains("GPX"))
    }
}
