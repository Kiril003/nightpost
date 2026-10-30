package local.phantom.companion.core.agent

import local.phantom.companion.core.constitution.ConstitutionRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [AgentConstitutionGate].
 *
 * Note on "auditor rejects" case: [AgentSpec.toManifest] produces a manifest
 * with all boolean risk flags as `false` and [FeatureManifest.sunsetAfterDaysIdle]
 * defaulting to 1 (set explicitly in [toManifest]), which is positive and
 * therefore passes the [AxiomAuditor] check. There is no way to produce a
 * failing manifest from an [AgentSpec] without modifying [toManifest] itself,
 * so we only test the passing path here. The "auditor rejects" path is
 * already covered by [core-constitution/AxiomAuditorTest].
 */
class AgentConstitutionGateTest {

    private fun registry() = ConstitutionRegistry()

    private fun spec(id: String = "test-agent") = AgentSpec(
        agentId = id,
        goal = "test goal",
    )

    @Test
    fun `acquire on clean registry returns true and manifest is registered`() {
        val reg = registry()
        val gate = AgentConstitutionGate(reg)
        val s = spec("agent-abc")

        val accepted = gate.acquire(s)

        assertTrue("acquire should return true for valid spec", accepted)
        val manifestId = s.toManifest().id
        assertTrue(
            "manifest '$manifestId' should be in registry after acquire",
            reg.manifests.value.any { it.id == manifestId },
        )
    }

    @Test
    fun `release removes the manifest from registry`() {
        val reg = registry()
        val gate = AgentConstitutionGate(reg)
        val s = spec("agent-xyz")

        gate.acquire(s)
        gate.release(s)

        val manifestId = s.toManifest().id
        assertFalse(
            "manifest '$manifestId' should be removed after release",
            reg.manifests.value.any { it.id == manifestId },
        )
    }

    @Test
    fun `release without acquire is a no-op`() {
        val reg = registry()
        val gate = AgentConstitutionGate(reg)
        // Must not throw.
        gate.release(spec("phantom-release"))
    }

    @Test
    fun `two different specs registered independently`() {
        val reg = registry()
        val gate = AgentConstitutionGate(reg)
        val s1 = spec("agent-1")
        val s2 = spec("agent-2")

        gate.acquire(s1)
        gate.acquire(s2)

        val ids = reg.manifests.value.map { it.id }
        assertTrue(ids.contains(s1.toManifest().id))
        assertTrue(ids.contains(s2.toManifest().id))

        gate.release(s1)
        val idsAfter = reg.manifests.value.map { it.id }
        assertFalse(idsAfter.contains(s1.toManifest().id))
        assertTrue(idsAfter.contains(s2.toManifest().id))
    }
}
