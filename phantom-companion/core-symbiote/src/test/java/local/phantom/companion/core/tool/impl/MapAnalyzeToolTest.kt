package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** LEVIATHAN-5 — the brief speaks only what the instruments know. */
class MapAnalyzeToolTest {

    private val ctx = ToolContext(
        appContext = Application(),
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = emptySet(),
    )

    @Test
    fun `a full snapshot yields a full brief`() = runTest {
        val tool = MapAnalyzeTool {
            MapAnalyzeTool.Snapshot(
                lat = 50.4501, lon = 30.5234, accuracyM = 8f,
                navigationGrade = true, dead = false, trust = "TRUSTED",
                motionState = "WALK", motionConfidence = 0.85f,
                guiding = true, remainingDistanceM = 1200.0, remainingDurationS = 900.0,
                routeDrawn = true,
                layers = listOf("уникати · обстріл", "транспорт"),
                shelterName = "Укриття №3", shelterDistanceM = 340.0,
            )
        }
        val res = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(res.output.contains("Ти тут: 50.45010, 30.52340"))
        assertFalse("певне місце не потребує слів: ${res.output}", res.output.contains("Місце"))
        assertTrue(res.output.contains("пішки"))
        assertTrue(res.output.contains("1.2 км"))
        assertTrue(res.output.contains("Укриття №3"))
        assertTrue(res.output.contains("уникати · обстріл"))
        assertEquals("TRUSTED", res.structured?.get("trust")?.jsonPrimitive?.content)
    }

    @Test
    fun `dark instruments stay out of the brief`() = runTest {
        val tool = MapAnalyzeTool { MapAnalyzeTool.Snapshot() }
        val res = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(res.output.contains("Де ти — поки не знаю"))
        assertTrue(res.output.contains("Активного маршруту немає"))
        assertFalse(res.output.contains("укриття"))
        assertFalse(res.output.contains("На мапі"))
    }

    @Test
    fun `a spoofed sky is shouted, not whispered`() = runTest {
        val tool = MapAnalyzeTool {
            MapAnalyzeTool.Snapshot(
                lat = 50.0, lon = 30.0, trust = "SPOOFED", navigationGrade = true,
            )
        }
        val res = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(res.output.contains("Увага: телефону підсовують чуже місце"))
    }

    @Test
    fun `a doubtful position is said in words that change the next turn`() = runTest {
        val tool = MapAnalyzeTool {
            MapAnalyzeTool.Snapshot(lat = 50.0, lon = 30.0, accuracyM = 900f, trust = "DEGRADED")
        }
        val res = tool.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertTrue(res.output.contains("Місце приблизне — можу помилитись із поворотом"))
        assertFalse(res.output.contains("900"))
        assertEquals("900.0", res.structured?.get("accuracy_m")?.jsonPrimitive?.content)
    }

    @Test
    fun `a missing barometer stays in the payload, not in the sentence`() = runTest {
        val noBaro = MapAnalyzeTool {
            MapAnalyzeTool.Snapshot(lat = 50.0, lon = 30.0, trust = "TRUSTED", baroAvailable = false)
        }
        val res = noBaro.execute(buildJsonObject { }, ctx) { } as ToolResult.Ok
        assertFalse(res.output.contains("Барометр"))
        assertEquals("false", res.structured?.get("barometer")?.jsonPrimitive?.content)
    }

    @Test
    fun `a snapshot failure is an honest error`() = runTest {
        val tool = MapAnalyzeTool { error("container gone") }
        val res = tool.execute(buildJsonObject { }, ctx) { }
        assertTrue(res is ToolResult.Err)
    }
}
