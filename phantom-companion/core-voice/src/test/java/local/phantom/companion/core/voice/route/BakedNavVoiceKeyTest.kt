package local.phantom.companion.core.voice.route

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The pack is baked on a laptop and looked up on a phone. If the two sides
 * disagree about an apostrophe, every affected cue silently falls back to
 * System TTS — which is the exact symptom this pack exists to remove.
 */
class BakedNavVoiceKeyTest {

    private fun key(s: String) = BakedNavVoice.normalizeKey(s)

    @Test
    fun `apostrophe variants collapse to one key`() {
        val baked = key("Через пʼятдесят метрів поверніть праворуч")
        assertEquals(baked, key("Через пʼятдесят метрів поверніть праворуч"))
        assertEquals(baked, key("Через пʼятдесят метрів поверніть праворуч"))
        assertEquals(baked, key("Через п`ятдесят метрів поверніть праворуч"))
    }

    @Test
    fun `case and stray whitespace do not miss`() {
        assertEquals(
            key("Поверніть праворуч"),
            key("  поверніть   ПРАВОРУЧ "),
        )
    }

    @Test
    fun `trailing period is cosmetic`() {
        assertEquals(
            key("Зійшли з маршруту — перепрокладаю"),
            key("Зійшли з маршруту — перепрокладаю."),
        )
    }

    @Test
    fun `different maneuvers stay different`() {
        assertNotEquals(key("Поверніть праворуч"), key("Поверніть ліворуч"))
        assertNotEquals(
            key("Через 200 метрів поверніть праворуч"),
            key("Через 250 метрів поверніть праворуч"),
        )
    }
}
