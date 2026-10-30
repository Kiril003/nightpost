package local.phantom.companion.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The metered-network warning quotes this number at a man deciding whether to
 * spend his LTE quota, so it is pinned against the verified ~398 MB asset
 * total. The warning sentence itself now lives in strings.xml and takes the
 * number as an argument.
 */
class VoiceSettingsScreenTest {

    @Test
    fun `SUPERTONIC_SIZE_MB matches the verified asset total`() {
        assertEquals(400, VoiceSettingsCopy.SUPERTONIC_SIZE_MB)
    }
}
