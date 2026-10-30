package local.phantom.companion.core.solana

import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VaultKeysTest {

    @Test
    fun `the bound key opens only after a strong biometric or the screen lock, for a short window`() {
        val spec = VaultKeys.spec("a", sdk = 34)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(VaultKeys.UNLOCK_WINDOW_S, spec.userAuthenticationValidityDurationSeconds)
        assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL, spec.userAuthenticationType)
        assertTrue(VaultKeys.UNLOCK_WINDOW_S in 1..30)
    }

    @Test
    fun `android 10 gets the same binding through the older call`() {
        val spec = VaultKeys.spec("a", sdk = 29)
        assertTrue(spec.isUserAuthenticationRequired)
        assertEquals(VaultKeys.UNLOCK_WINDOW_S, spec.userAuthenticationValidityDurationSeconds)
    }

    @Test
    fun `every key the vault can make needs the unlock and the old unbound key is read only`() {
        assertTrue(VaultKeys.spec("a").isUserAuthenticationRequired)
        val vault = File("src/main/java/local/phantom/companion/core/solana/BuiltInVault.kt").readText()
        assertTrue(vault.contains("legacy = KeystoreLock(ALIAS, spec = null)"))
        assertFalse(vault.contains("bound = false"))
    }

    @Test
    fun `the prompt asks only for what unlocks the key`() {
        val auth = VaultKeys.authenticators(34)
        assertEquals(BIOMETRIC_STRONG or DEVICE_CREDENTIAL, auth)
        assertEquals("a weak face unlock passes the prompt but never the Keystore", BIOMETRIC_STRONG, auth and BIOMETRIC_WEAK)
    }

    @Test
    fun `the wallet screen and the vault use those rules`() {
        val src = File("src/main/java/local/phantom/companion/core/solana")
        val activity = File(src, "WalletActivity.kt").readText()
        assertFalse(activity.contains("BIOMETRIC_WEAK"))
        assertEquals(2, Regex("""VaultKeys\.authenticators\(\)""").findAll(activity).count())
        assertTrue("no lock-required gate on the wallet screen", activity.contains("WalletLockGate(door, state, ::say)"))
        assertTrue("the gate does not lead to the lock settings", activity.contains("Settings.ACTION_SECURITY_SETTINGS"))
        assertEquals("a restore path skips the unlock", 3, Regex("""restoreSeed\(door, say\)""").findAll(activity + File(src, "WalletBackupUi.kt").readText()).count())
        val vault = File(src, "BuiltInVault.kt").readText()
        assertTrue(vault.contains("VaultKeys.spec(ALIAS_BOUND)"))
        assertTrue(vault.contains("box.open()"))
        assertTrue("the seed is not reachable from other modules", vault.contains("internal class BuiltInVault("))
    }
}
