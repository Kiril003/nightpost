package local.phantom.companion.core.bioloop

import org.junit.Assert.assertEquals
import org.junit.Test

class CognitiveLoadEstimatorTest {

    @Test
    fun `zero everything is low`() {
        val e = CognitiveLoadEstimator()
        assertEquals(CognitiveLoad.LOW, e.load.value)
    }

    @Test
    fun `all maxed is high`() {
        val e = CognitiveLoadEstimator()
        e.ingestStress(1.0)
        e.ingestTypo(1.0)
        e.ingestChurn(1.0)
        assertEquals(CognitiveLoad.HIGH, e.load.value)
    }

    @Test
    fun `moderate stress alone is medium`() {
        val e = CognitiveLoadEstimator()
        e.ingestStress(1.0)
        // weight 0.5 → score 0.5 → MEDIUM
        assertEquals(CognitiveLoad.MEDIUM, e.load.value)
    }
}
