package local.phantom.companion.ui.messenger

import local.phantom.companion.BuildConfig
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * DL-3 (ніч 09.10, два телефони): debug-APK без `-Pphantom.licenseHost` не мав
 * адреси скриньки (`roads.relay.configured = false`), і лист між A52 (Wi-Fi) та
 * A56 (LTE) годинами стояв `queued/local`. Власник носить лише `.debug`, тож
 * ця збірка мусить вміти перетнути мережу без ручного прапора.
 */
class DebugBuildHasRelayTest {

    @Test
    fun `a debug phone build carries a relay address`() {
        assumeTrue(BuildConfig.DEBUG)
        assertTrue(
            "debug-збірка без адреси скриньки: LICENSE_BASE_URL='${BuildConfig.LICENSE_BASE_URL}'",
            BuildConfig.LICENSE_BASE_URL.startsWith("https://"),
        )
    }
}
