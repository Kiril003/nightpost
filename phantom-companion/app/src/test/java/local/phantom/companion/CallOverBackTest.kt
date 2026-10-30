package local.phantom.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallOverBackTest {

    @Test
    fun `guard consumes back over the lock`() {
        var passed = 0
        val back = CallOverBack(leave = { true }, passThrough = { passed++ })
        back.isEnabled = true
        back.handleOnBackPressed()
        assertEquals(0, passed)
        assertTrue(back.isEnabled)
    }

    @Test
    fun `unlocked back passes through once`() {
        var passed = 0
        var enabledDuringPass = true
        lateinit var back: CallOverBack
        back = CallOverBack(
            leave = { false },
            passThrough = {
                enabledDuringPass = back.isEnabled
                passed++
            },
        )
        back.isEnabled = true
        back.handleOnBackPressed()
        assertEquals(1, passed)
        assertEquals(false, enabledDuringPass)
        assertTrue(back.isEnabled)
    }
}
