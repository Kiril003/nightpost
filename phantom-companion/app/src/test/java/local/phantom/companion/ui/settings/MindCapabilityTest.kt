package local.phantom.companion.ui.settings

import local.phantom.companion.R
import local.phantom.companion.core.ai.ModelState
import local.phantom.companion.core.ai.ModelType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MindCapabilityTest {

    private fun caps(paired: Boolean = false, key: Boolean = false, vararg models: ModelType) = capabilities(
        modelStates = models.associateWith { ModelState(it, isPresent = true) },
        keysStored = key,
        ttsEnabled = false,
        offlineRegions = null,
        paired = paired,
    )

    private fun mind(paired: Boolean = false, key: Boolean = false) =
        caps(paired, key).first { it.label == R.string.settings_cap_mind }

    @Test
    fun `розум через ПК, коли телефон спарений`() {
        assertEquals(R.string.settings_cap_mind_pc, mind(paired = true).value)
        assertEquals(CapabilityTone.Good, mind(paired = true).tone)
    }

    @Test
    fun `розум через хмару, коли є ключ`() {
        assertEquals(R.string.settings_cap_mind_cloud, mind(key = true).value)
    }

    @Test
    fun `без ПК і ключа розуму нема, хоч би що лежало на диску`() {
        val c = caps(false, false, ModelType.VOSK, ModelType.SUPERTONIC, ModelType.YAMNET)
            .first { it.label == R.string.settings_cap_mind }
        assertEquals(R.string.settings_cap_mind_none, c.value)
        assertEquals(CapabilityTone.Bad, c.tone)
    }

    @Test
    fun `every ear in the catalogue is wired`() {
        listOf(ModelType.VOSK, ModelType.MOONSHINE_UK).forEach { assertTrue("$it без рушія", it.wired) }
    }

    @Test
    fun `a non-ear model on disk is not an ear and keeps the remedy`() {
        val c = caps(false, false, ModelType.YAMNET).first { it.label == R.string.settings_cap_hearing }
        assertEquals(R.string.settings_cap_hearing_none, c.value)
        assertNotNull(hearingAction(mapOf(ModelType.YAMNET to ModelState(ModelType.YAMNET, isPresent = true))))
    }

    @Test
    fun `a wired ear is, and removes the remedy`() {
        val c = caps(false, false, ModelType.MOONSHINE_UK).first { it.label == R.string.settings_cap_hearing }
        assertEquals(R.string.settings_cap_hearing_on, c.value)
        assertEquals(null, hearingAction(mapOf(ModelType.VOSK to ModelState(ModelType.VOSK, isPresent = true))))
    }
}
