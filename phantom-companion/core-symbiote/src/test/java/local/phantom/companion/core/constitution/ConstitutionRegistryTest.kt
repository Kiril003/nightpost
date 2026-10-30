package local.phantom.companion.core.constitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionRegistryTest {

    @Test
    fun `passing manifest lands in registry`() {
        val r = ConstitutionRegistry()
        val v = r.register(FeatureManifest("a", 14, "good"))
        assertTrue(v is AuditVerdict.Passed)
        assertEquals(1, r.manifests.value.size)
    }

    @Test
    fun `failing manifest stays out and surfaces rejection`() {
        val r = ConstitutionRegistry()
        val v = r.register(FeatureManifest("bad", 14, "no", erodesOperatorAgency = true))
        assertTrue(v is AuditVerdict.Failed)
        assertTrue(r.manifests.value.isEmpty())
        assertEquals(1, r.rejections.value.size)
    }

    @Test
    fun `unregister removes from manifests`() {
        val r = ConstitutionRegistry()
        r.register(FeatureManifest("a", 14, "good"))
        r.unregister("a")
        assertTrue(r.manifests.value.isEmpty())
    }
}
