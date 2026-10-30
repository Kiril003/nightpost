package local.phantom.companion.feature.onboarding.ui

import local.phantom.companion.feature.onboarding.domain.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class OnboardingNarrationTest {

    private val every = listOf(
        OnboardingState.NameStep(),
        OnboardingState.CreatingProfile,
        OnboardingState.Complete(profileId = "p1", displayName = "Я"),
        OnboardingState.Error(message = "boom"),
    )

    @Test
    fun `NameStep narrates the opening greeting`() {
        assertEquals("вітаю · як до тебе звертатись?", narrationLineFor(OnboardingState.NameStep()))
    }

    @Test
    fun `Complete narrates the arrival line`() {
        assertEquals("готовий · тут і зараз", narrationLineFor(every[2]))
    }

    @Test
    fun `transient and failure states do not narrate`() {
        assertNull(narrationLineFor(OnboardingState.CreatingProfile))
        assertNull(narrationLineFor(OnboardingState.Error(message = "boom")))
    }

    @Test
    fun `nothing on first run asks to see the screen`() {
        every.mapNotNull { narrationLineFor(it) }.forEach {
            assertFalse(it, it.contains("екран"))
        }
    }
}
