package local.phantom.companion.feature.stream.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Один вигляд для всіх пінів мапи — і причина, чому вони «люфтили».
 *
 * Ці тести перевіряють геометрію, а не колір: кольори стереже
 * MapSymbolContrastTest. Тут — три речі, які ламалися мовчки: підкладка, яку
 * зрізав край бітмапа; поверх, вимірюваний у пікселях замість dp; і гліф, якому
 * кільця не лишили місця.
 */
class MapPinFaceTest {

    /** Реальні щільності екранів: mdpi … xxxhdpi, разом із 420dpi Pixel'ів. */
    private val densities = floatArrayOf(1.0f, 1.5f, 2.0f, 2.625f, 3.0f, 3.5f, 4.0f)

    @Test
    fun `the pad is real, so the rim's own edge is never shaved off`() {
        assertTrue("піни", MapPinFace.PAD_DP > 0f)
        assertTrue("значки загроз", BADGE_PAD_DP > 0f)
        assertEquals(
            MapPinFace.BITMAP_DP,
            MapPinFace.CONTENT_DP + 2 * MapPinFace.PAD_DP,
            0.0001f,
        )
    }

    /**
     * Поверх у пікселях — це різний фізичний розмір на кожному екрані. Старий
     * `.coerceAtLeast(48)` на mdpi роздував 34dp-пін до 48px: 41% похибки саме
     * там, де екран найменший.
     */
    @Test
    fun `one written size stays one drawn size at every density`() {
        densities.forEach { d ->
            val pin = MapPinFace.bitmapPx(d) / d
            assertTrue(
                "пін на щільності $d: ${MapPinFace.bitmapPx(d)}px = ${pin}dp",
                abs(pin - MapPinFace.BITMAP_DP) < 1f,
            )
            val badge = MapPinFace.bitmapPx(d, BADGE_CONTENT_DP, BADGE_PAD_DP) / d
            assertTrue(
                "значок на щільності $d: ${badge}dp",
                abs(badge - (BADGE_CONTENT_DP + 2 * BADGE_PAD_DP)) < 1f,
            )
        }
    }

    @Test
    fun `the rim and the ring leave the glyph a field to sit in`() {
        val pinField = MapPinFace.CONTENT_DP - 2 * (MapPinFace.RIM_DP + MapPinFace.RING_DP)
        assertTrue("пін: ${pinField}dp на гліф ${MapPinFace.GLYPH_DP}dp", pinField > MapPinFace.GLYPH_DP)

        val badgeField = BADGE_CONTENT_DP - 2 * (MapPinFace.RIM_DP + BADGE_RING_DP)
        assertTrue("значок: ${badgeField}dp на гліф ${BADGE_GLYPH_DP}dp", badgeField > BADGE_GLYPH_DP)
    }

    /** Ставки купують вагу, не чорноту: найширше кільце на мапі — у загрози. */
    @Test
    fun `the threat badge wears the widest ring on the map`() {
        assertTrue(BADGE_RING_DP > MapPinFace.RING_DP)
        assertTrue(BADGE_CONTENT_DP > MapPinFace.CONTENT_DP)
    }
}
