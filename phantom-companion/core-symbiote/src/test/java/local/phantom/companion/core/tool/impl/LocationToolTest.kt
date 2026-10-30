package local.phantom.companion.core.tool.impl

import android.app.Application
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import local.phantom.companion.core.tool.ToolContext
import local.phantom.companion.core.tool.ToolPermission
import local.phantom.companion.core.tool.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [LocationTool] — R1.1.
 *
 * [snapshotProvider] and [reverseGeocode] are injected fakes; no
 * Android Location / Geocoder dependency.
 */
class LocationToolTest {

    private val stubContext = Application()

    private fun ctx() = ToolContext(
        appContext = stubContext,
        operatorId = "tester",
        requestId = "req-1",
        approvedPermissions = setOf(ToolPermission.LOCATION),
    )

    private val fullSnapshot = LocationSnapshot(
        lat = 50.4501,
        lon = 30.5234,
        accuracyM = 12.5f,
        speedMps = 1.4f,
        bearingDeg = 270f,
        ageMs = 4_000L,
        source = "gps",
    )

    @Test
    fun `full snapshot returns Ok with all structured fields`() = runTest {
        val tool = LocationTool(
            snapshotProvider = { fullSnapshot },
            reverseGeocode = { _, _ -> "Хрещатик, Київ" },
        )
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        assertEquals("50.4501", s["lat"]!!.jsonPrimitive.content)
        assertEquals("30.5234", s["lon"]!!.jsonPrimitive.content)
        assertEquals("12.5", s["accuracy_m"]!!.jsonPrimitive.content)
        assertEquals("1.4", s["speed_mps"]!!.jsonPrimitive.content)
        assertEquals("270.0", s["bearing_deg"]!!.jsonPrimitive.content)
        assertEquals("4", s["age_s"]!!.jsonPrimitive.content)
        assertEquals("gps", s["source"]!!.jsonPrimitive.content)
        assertEquals("Хрещатик, Київ", s["address"]!!.jsonPrimitive.content)
        assertTrue(result.output.contains("Хрещатик"))
    }

    @Test
    fun `no fix returns honest no_fix error, never a fabricated position`() = runTest {
        val tool = LocationTool(snapshotProvider = { null })
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        assertEquals("no_fix", (result as ToolResult.Err).code)
    }

    @Test
    fun `address=false skips reverse geocoding`() = runTest {
        var geocodeCalled = false
        val tool = LocationTool(
            snapshotProvider = { fullSnapshot },
            reverseGeocode = { _, _ -> geocodeCalled = true; "не має зʼявитись" },
        )
        val result = tool.execute(
            buildJsonObject { put("address", false) },
            ctx(),
        ) {}
        assertTrue(result is ToolResult.Ok)
        assertNull((result as ToolResult.Ok).structured!!["address"])
        assertEquals(false, geocodeCalled)
    }

    @Test
    fun `failed reverse geocode degrades to coords without address`() = runTest {
        val tool = LocationTool(
            snapshotProvider = { fullSnapshot },
            reverseGeocode = { _, _ -> throw IllegalStateException("geocoder down") },
        )
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        assertNull((result as ToolResult.Ok).structured!!["address"])
    }

    @Test
    fun `minimal snapshot omits optional fields`() = runTest {
        val tool = LocationTool(
            snapshotProvider = { LocationSnapshot(lat = 49.84, lon = 24.03, source = "ip-geo") },
        )
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Ok)
        val s = (result as ToolResult.Ok).structured!!
        assertNull(s["accuracy_m"])
        assertNull(s["speed_mps"])
        assertNull(s["bearing_deg"])
        assertNull(s["age_s"])
        assertEquals("ip-geo", s["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `throwing snapshot provider is treated as no fix`() = runTest {
        val tool = LocationTool(snapshotProvider = { throw IllegalStateException("boom") })
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue(result is ToolResult.Err)
        assertEquals("no_fix", (result as ToolResult.Err).code)
    }

    @Test
    fun `refused position answers with the gate sentence and no coordinates`() = runTest {
        var geocoded = false
        val tool = LocationTool(
            snapshotProvider = {
                LocationSnapshot(
                    lat = null,
                    lon = null,
                    source = "gate/spoofed",
                    refusedUa = "Телефону підсовують чуже місце. Де ти — не знаю",
                    refusedEn = "The phone is being fed a false position. Where you are is unknown",
                )
            },
            reverseGeocode = { _, _ -> geocoded = true; "не має зʼявитись" },
        )
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Err, got $result", result is ToolResult.Err)
        val err = result as ToolResult.Err
        assertEquals("position_refused", err.code)
        assertTrue(err.message.contains("підсовують чуже місце"))
        assertEquals(false, geocoded)
    }

    @Test
    fun `uncertain position is stated with its doubt attached`() = runTest {
        val tool = LocationTool(
            snapshotProvider = {
                LocationSnapshot(
                    lat = 50.45,
                    lon = 30.52,
                    accuracyM = 1_800f,
                    source = "gate/fused",
                    advisoryUa = "Місце приблизне — можу помилитись із поворотом",
                )
            },
        )
        val result = tool.execute(buildJsonObject {}, ctx()) {}
        assertTrue("expected Ok, got $result", result is ToolResult.Ok)
        val ok = result as ToolResult.Ok
        assertEquals("1800.0", ok.structured!!["accuracy_m"]!!.jsonPrimitive.content)
        assertTrue(ok.output.startsWith("Місце приблизне"))
        assertFalse("радіус лишається в даних, не в реченні: ${ok.output}", ok.output.contains("±"))
    }

    @Test
    fun `declares LOCATION permission`() {
        val tool = LocationTool(snapshotProvider = { null })
        assertEquals(setOf(ToolPermission.LOCATION), tool.permissions)
        assertEquals("location.current", tool.id)
    }
}
