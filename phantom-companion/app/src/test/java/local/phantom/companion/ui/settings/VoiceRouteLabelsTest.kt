package local.phantom.companion.ui.settings

import local.phantom.companion.R
import local.phantom.companion.core.voice.SttRouteKind
import local.phantom.companion.core.voice.route.TtsRoute
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The «голос: …» / «слух: …» readouts are the operator's proof that a
 * download was picked up, so the route→label mapping is pinned. It pins
 * resource ids rather than sentences: which route reads as which line is the
 * contract, the wording is the translator's.
 */
class VoiceRouteLabelsTest {

    @Test
    fun `every tts route maps to its own label`() {
        assertEquals(R.string.tts_route_baked, ttsRouteLabel(TtsRoute.Baked))
        assertEquals(R.string.tts_route_premium, ttsRouteLabel(TtsRoute.Premium))
        assertEquals(R.string.tts_route_system, ttsRouteLabel(TtsRoute.System))
        assertEquals(R.string.route_off, ttsRouteLabel(TtsRoute.None))
    }

    @Test
    fun `every stt route maps to its own label`() {
        assertEquals(R.string.stt_route_hybrid, sttRouteLabel(SttRouteKind.Hybrid))
        assertEquals(R.string.stt_route_moonshine, sttRouteLabel(SttRouteKind.MoonshineOnly))
        assertEquals(R.string.stt_route_vosk, sttRouteLabel(SttRouteKind.VoskOnly))
        assertEquals(R.string.route_off, sttRouteLabel(SttRouteKind.None))
    }

    @Test
    fun `a live route never reads as the disabled one`() {
        val live = listOf(TtsRoute.Baked, TtsRoute.Premium, TtsRoute.System).map(::ttsRouteLabel)
        assertEquals(live.distinct().size, live.size)
        assertEquals(false, live.contains(R.string.route_off))
    }
}
