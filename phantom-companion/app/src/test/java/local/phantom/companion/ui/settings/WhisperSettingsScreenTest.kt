package local.phantom.companion.ui.settings

import local.phantom.companion.R
import local.phantom.companion.core.design.component.WhisperFreq
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Phase 3-E5 — pure JVM tests for the WhisperSettings copy helpers.
 *
 * Acceptance from the prompt: «permission flow, toggle states».
 * The Composable + Settings intent dispatch + service start/stop
 * live behind an Android Context; what we CAN unit-pin is the
 * operator-visible copy that drives the freq picker labels and
 * cycle descriptions, plus the WhisperFreq enum coverage.
 */
class WhisperSettingsScreenTest {

    /* ---- freqLabelRes ---- */

    @Test
    fun `freq chips use catalogue words, the middle one is a bare dot`() {
        assertEquals(R.string.kt_whisper_slow, freqLabelRes(WhisperFreq.Slow))
        assertEquals(null, freqLabelRes(WhisperFreq.Medium))
        assertEquals(R.string.kt_whisper_fast, freqLabelRes(WhisperFreq.Fast))
    }

    /* ---- freqDescriptionRes ---- */

    @Test
    fun `freqDescription maps the three cycles to their own copy`() {
        assertEquals(R.string.kt_whisper_desc_slow, freqDescriptionRes(0))
        assertEquals(R.string.kt_whisper_desc_mid, freqDescriptionRes(1))
        assertEquals(R.string.kt_whisper_desc_fast, freqDescriptionRes(2))
    }

    @Test
    fun `freqDescription clamps out-of-range ordinals`() {
        assertEquals(R.string.kt_whisper_desc_fast, freqDescriptionRes(3))
        assertEquals(R.string.kt_whisper_desc_fast, freqDescriptionRes(Int.MAX_VALUE))
        assertEquals(R.string.kt_whisper_desc_slow, freqDescriptionRes(-1))
        assertEquals(R.string.kt_whisper_desc_slow, freqDescriptionRes(Int.MIN_VALUE))
    }

    @Test
    fun `freqDescription differs across the three valid bands`() {
        assertEquals(3, (0..2).map(::freqDescriptionRes).toSet().size)
    }
}
