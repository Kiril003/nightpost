package local.phantom.companion.core.solana

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SigningConsentTest {

    @Test
    fun withoutAScreenLockNothingIsSignedAndNoWarningCanSayYes() = runTest {
        var asked = false
        assertFalse(SigningConsent.ask(false) { asked = true; true })
        assertFalse(asked)
    }

    @Test
    fun withAScreenLockTheLockDecides() = runTest {
        assertFalse(SigningConsent.ask(true) { false })
        assertTrue(SigningConsent.ask(true) { true })
    }
}
