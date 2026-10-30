package local.phantom.companion.feature.ambient.denylist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3-E4 — package classification tests for the
 * [SensitiveAppDenylist]. Acceptance from the prompt: «package
 * classification». Covered: every prompt-named exact package
 * resolves true; common substring patterns (banks, password
 * managers, OTP) catch the long tail; benign apps (chat, browsers,
 * launchers) resolve false; null/blank inputs default to false
 * (overlay stays visible when foreground is unknown).
 */
class SensitiveAppDenylistTest {

    /* ---- prompt-named exact packages ---- */

    @Test
    fun `monobank resolves sensitive (prompt-named)`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.ftband.mono"))
    }

    @Test
    fun `privat24 resolves sensitive (prompt-named)`() {
        assertTrue(SensitiveAppDenylist.isSensitive("ua.privatbank.ap24"))
        assertTrue(SensitiveAppDenylist.isSensitive("ua.privatbank.privat24"))
    }

    @Test
    fun `lastpass resolves sensitive (prompt-named)`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.lastpass.lpandroid"))
    }

    @Test
    fun `google authenticator resolves sensitive (prompt-named)`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.google.android.apps.authenticator2"))
    }

    /* ---- substring catch-alls ---- */

    @Test
    fun `unknown bitwarden variant resolves via substring hint`() {
        // Not in EXACT — caught by the "bitwarden" substring hint.
        assertTrue(SensitiveAppDenylist.isSensitive("com.bitwarden.experimental.beta"))
    }

    @Test
    fun `unknown 1password variant resolves via substring hint`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.agilebits.onepassword.fork"))
        // Substring lane is case-insensitive on the input side.
        assertTrue(SensitiveAppDenylist.isSensitive("com.AgileBits.ONEPASSWORD"))
    }

    @Test
    fun `regional bank with bank prefix in package resolves sensitive`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.regionalbank.banking.android"))
        assertTrue(SensitiveAppDenylist.isSensitive("com.fancyfintech.bank.app"))
    }

    @Test
    fun `wallet apps resolve sensitive via substring`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.wallet.crypto.trustapp"))
        assertTrue(SensitiveAppDenylist.isSensitive("com.example.metamask.lite"))
    }

    @Test
    fun `authenticator catch-all matches diverse OTP apps`() {
        assertTrue(SensitiveAppDenylist.isSensitive("com.azure.authenticator"))
        assertTrue(SensitiveAppDenylist.isSensitive("io.indie.someauthenticator"))
    }

    /* ---- benign apps stay visible ---- */

    @Test
    fun `whatsapp does not resolve sensitive`() {
        assertFalse(SensitiveAppDenylist.isSensitive("com.whatsapp"))
    }

    @Test
    fun `telegram does not resolve sensitive`() {
        assertFalse(SensitiveAppDenylist.isSensitive("org.telegram.messenger"))
    }

    @Test
    fun `chrome browser does not resolve sensitive`() {
        assertFalse(SensitiveAppDenylist.isSensitive("com.android.chrome"))
    }

    @Test
    fun `system launcher does not resolve sensitive`() {
        assertFalse(SensitiveAppDenylist.isSensitive("com.google.android.apps.nexuslauncher"))
        assertFalse(SensitiveAppDenylist.isSensitive("com.sec.android.app.launcher"))
    }

    @Test
    fun `notion and obsidian and other productivity apps stay visible`() {
        assertFalse(SensitiveAppDenylist.isSensitive("notion.id"))
        assertFalse(SensitiveAppDenylist.isSensitive("md.obsidian"))
        assertFalse(SensitiveAppDenylist.isSensitive("com.todoist"))
    }

    /* ---- defensive null/blank handling ---- */

    @Test
    fun `null package treated as not sensitive (overlay stays visible)`() {
        // Failing-closed here would mean the overlay blanks on every
        // launcher transition — operator would notice as a regression.
        assertFalse(SensitiveAppDenylist.isSensitive(null))
    }

    @Test
    fun `blank package treated as not sensitive`() {
        assertFalse(SensitiveAppDenylist.isSensitive(""))
        assertFalse(SensitiveAppDenylist.isSensitive("   "))
    }

    /* ---- denylist set self-consistency ---- */

    @Test
    fun `EXACT set every entry round-trips through isSensitive`() {
        SensitiveAppDenylist.EXACT.forEach { pkg ->
            assertTrue("EXACT entry '$pkg' must resolve sensitive", SensitiveAppDenylist.isSensitive(pkg))
        }
    }

    @Test
    fun `SUBSTRING_HINTS set is non-empty and lowercase`() {
        assertTrue(SensitiveAppDenylist.SUBSTRING_HINTS.isNotEmpty())
        SensitiveAppDenylist.SUBSTRING_HINTS.forEach { hint ->
            assertTrue("hint '$hint' must be lowercase", hint == hint.lowercase())
        }
    }

    @Test
    fun `EXACT contains all four prompt-named packages verbatim`() {
        // Anchor — pinning the verbatim list from the Phase 3-E4 spec
        // («monobank | privat | com.lastpass | com.google.android.apps
        // .authenticator2»). Reordering is fine, dropping one of these
        // four breaks the test loudly.
        assertTrue("monobank present", SensitiveAppDenylist.EXACT.contains("com.ftband.mono"))
        assertTrue("privat present",
            SensitiveAppDenylist.EXACT.any { it.contains("privatbank") })
        assertTrue("lastpass present", SensitiveAppDenylist.EXACT.contains("com.lastpass.lpandroid"))
        assertTrue("google authenticator present",
            SensitiveAppDenylist.EXACT.contains("com.google.android.apps.authenticator2"))
    }
}
