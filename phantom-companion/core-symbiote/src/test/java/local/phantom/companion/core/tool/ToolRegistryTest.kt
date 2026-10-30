package local.phantom.companion.core.tool

import kotlinx.serialization.json.JsonObject
import local.phantom.companion.core.constitution.Axiom
import local.phantom.companion.core.constitution.ConstitutionRegistry
import local.phantom.companion.core.constitution.FeatureManifest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRegistryTest {

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun makeManifest(
        id: String,
        erodesOperatorAgency: Boolean = false,
    ) = FeatureManifest(
        id = id,
        tier = 14,
        displayName = "Test tool $id",
        erodesOperatorAgency = erodesOperatorAgency,
    )

    private fun makeTool(id: String, manifest: FeatureManifest): Tool = object : Tool {
        override val id = id
        override val description = "tool $id"
        override val argsSchema = JsonObject(emptyMap())
        override val permissions = emptySet<ToolPermission>()
        override val risk: ToolRisk = ToolRisk.READ
        override val streaming = false
        override val manifest = manifest
        override suspend fun execute(
            args: JsonObject,
            context: ToolContext,
            emit: suspend (ToolEvent) -> Unit,
        ): ToolResult = ToolResult.Ok("ok", null, 0L)
    }

    // ---------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------

    @Test
    fun `happy path - two passing tools both appear in list and get`() {
        val registry = ConstitutionRegistry()
        val toolA = makeTool("alpha", makeManifest("alpha"))
        val toolB = makeTool("beta", makeManifest("beta"))

        val toolRegistry = ToolRegistry(listOf(toolA, toolB), registry)

        assertEquals(2, toolRegistry.list().size)
        assertNotNull(toolRegistry.get("alpha"))
        assertNotNull(toolRegistry.get("beta"))
        assertEquals("alpha", toolRegistry.get("alpha")?.id)
        assertEquals("beta", toolRegistry.get("beta")?.id)
    }

    @Test
    fun `mixed - tool with erodesOperatorAgency is excluded, clean tool is present`() {
        val registry = ConstitutionRegistry()
        val cleanTool = makeTool("clean", makeManifest("clean", erodesOperatorAgency = false))
        val badTool = makeTool("bad", makeManifest("bad", erodesOperatorAgency = true))

        val toolRegistry = ToolRegistry(listOf(cleanTool, badTool), registry)

        assertEquals(1, toolRegistry.list().size)
        assertNotNull(toolRegistry.get("clean"))
        assertNull(toolRegistry.get("bad"))
        // The bad manifest surfaces in constitutionRegistry.rejections
        assertEquals(1, registry.rejections.value.size)
        assertEquals("bad", registry.rejections.value.first().featureId)
    }

    @Test
    fun `empty tool list - list is empty and get returns null`() {
        val registry = ConstitutionRegistry()
        val toolRegistry = ToolRegistry(emptyList(), registry)

        assertTrue(toolRegistry.list().isEmpty())
        assertNull(toolRegistry.get("anything"))
    }

    @Test
    fun `rejectionFor is keyed by tool id, not manifest id`() {
        // Mirrors MissionArmTool in production: manifest id ("tool.mission.arm")
        // is not the same string as the tool id ("mission.arm") — rejectionFor
        // must still resolve by the id the model actually calls.
        val registry = ConstitutionRegistry()
        val manifest = FeatureManifest(
            id = "tool.mission.arm",
            tier = 14,
            displayName = "Озброєння місії",
            actsWithoutConsent = true,
        )
        val toolRegistry = ToolRegistry(listOf(makeTool("mission.arm", manifest)), registry)

        assertNull(toolRegistry.get("mission.arm"))
        val rejection = toolRegistry.rejectionFor("mission.arm")
        assertNotNull(rejection)
        assertEquals("Озброєння місії", rejection?.displayName)
        assertTrue(rejection!!.violations.isNotEmpty())
    }

    @Test
    fun `rejectionFor is null for a tool id that was never registered at all`() {
        val registry = ConstitutionRegistry()
        val toolRegistry = ToolRegistry(emptyList(), registry)
        assertNull(toolRegistry.rejectionFor("never.existed"))
    }

    /**
     * The diagnostics card renders `rejections.size`. A registry rebuilt while
     * the process lives must not make one refusal read as two.
     */
    @Test
    fun `the same refusal registered twice is still one refusal`() {
        val registry = ConstitutionRegistry()
        val refused = FeatureManifest(id = "tool.acts.alone", tier = 14, displayName = "Діє сам", actsWithoutConsent = true)
        ToolRegistry(listOf(makeTool("acts.alone", refused)), registry)
        ToolRegistry(listOf(makeTool("acts.alone", refused)), registry)

        assertEquals(1, registry.rejections.value.size)
        assertEquals("tool.acts.alone", registry.rejections.value.first().featureId)
    }

    @Test
    fun `transparent aliases resolve to target tools`() {
        val registry = ConstitutionRegistry()
        val geoSearch = makeTool("geo.search", makeManifest("geo.search"))
        val placeInfo = makeTool("place.info", makeManifest("place.info"))
        val weatherNow = makeTool("weather.now", makeManifest("weather.now"))
        val phoneDial = makeTool("phone.dial", makeManifest("phone.dial"))

        val toolRegistry = ToolRegistry(listOf(geoSearch, placeInfo, weatherNow, phoneDial), registry)

        assertEquals(geoSearch, toolRegistry.get("map.search"))
        assertEquals(geoSearch, toolRegistry.get("spatial.search"))
        assertEquals(placeInfo, toolRegistry.get("map.dossier"))
        assertEquals(weatherNow, toolRegistry.get("weather.get"))
        assertEquals(phoneDial, toolRegistry.get("phone.call"))
    }
}
