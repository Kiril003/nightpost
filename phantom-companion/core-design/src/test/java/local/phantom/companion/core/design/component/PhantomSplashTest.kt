package local.phantom.companion.core.design.component

import org.junit.Assert.assertTrue
import org.junit.Test

class PhantomSplashTest {

    @Test
    fun `the handoff is a fade, not a second splash`() {
        assertTrue("hold + fade must stay under 600ms", SPLASH_HOLD_MS + SPLASH_FADE_MS <= 600)
    }

    /** `animateTo` їде на годиннику кадрів, а холодний старт саме його й морить. */
    @Test
    fun `the veil has a deadline longer than the fade but bounded`() {
        assertTrue(SPLASH_DEADLINE_MS > SPLASH_HOLD_MS + SPLASH_FADE_MS)
        assertTrue("a stuck launch may not hold the operator past 3s", SPLASH_DEADLINE_MS <= 3_000L)
    }
}
