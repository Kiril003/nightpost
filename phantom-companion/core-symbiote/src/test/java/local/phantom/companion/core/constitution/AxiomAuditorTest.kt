package local.phantom.companion.core.constitution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AxiomAuditorTest {

    private val auditor = AxiomAuditor()

    @Test
    fun `clean manifest passes`() {
        val m = FeatureManifest(id = "test.clean", tier = 14, displayName = "ok")
        val v = auditor.audit(m)
        assertTrue(v is AuditVerdict.Passed)
    }

    @Test
    fun `erodes agency violates operator-advocate`() {
        val m = FeatureManifest(
            id = "test.bad", tier = 14, displayName = "dependency-maker",
            erodesOperatorAgency = true,
        )
        val v = auditor.audit(m) as AuditVerdict.Failed
        assertEquals(1, v.violations.size)
        assertEquals(Axiom.OPERATOR_ADVOCATE, v.violations.single().axiom)
    }

    @Test
    fun `multiple flags accumulate independent violations`() {
        val m = FeatureManifest(
            id = "test.worst", tier = 14, displayName = "all-bad",
            erodesOperatorAgency = true,
            exfiltratesPrivate = true,
            actsWithoutConsent = true,
            abusableAgainstWeak = true,
        )
        val v = auditor.audit(m) as AuditVerdict.Failed
        assertEquals(4, v.violations.size)
    }

    @Test
    fun `sunsetAfterDaysIdle of zero is rejected as ambiguous`() {
        val m = FeatureManifest(
            id = "test.zero", tier = 14, displayName = "zero-sunset",
            sunsetAfterDaysIdle = 0,
        )
        val v = auditor.audit(m) as AuditVerdict.Failed
        assertEquals(Axiom.SUNSET_CLAUSE, v.violations.single().axiom)
    }

    @Test
    fun `negative sunsetAfterDaysIdle means never and is allowed`() {
        val m = FeatureManifest(
            id = "test.forever", tier = 14, displayName = "never-sunset",
            sunsetAfterDaysIdle = -1,
        )
        assertTrue(auditor.audit(m) is AuditVerdict.Passed)
    }
}
