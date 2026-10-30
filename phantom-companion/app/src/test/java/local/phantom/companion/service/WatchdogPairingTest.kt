package local.phantom.companion.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

// Сховище парування стоїть на AndroidKeyStore, якого Robolectric не має:
// без парування — запуск, з паруванням — скан джерела.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WatchdogPairingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStartedServices() {
        val shadow = shadowOf(app)
        while (shadow.nextStartedService != null) Unit
    }

    private fun startedServiceNames(): List<String> {
        val shadow = shadowOf(app)
        val started = mutableListOf<String>()
        while (true) {
            val next = shadow.nextStartedService ?: break
            next.component?.className?.let(started::add)
        }
        return started
    }

    @Test
    fun unpaired_noLinkStart() {
        WatchdogReceiver().onReceive(context, Intent(context, WatchdogReceiver::class.java))

        val started = startedServiceNames()
        assertFalse(
            "Unpaired device must not start PhantomLinkService from Watchdog: $started",
            PhantomLinkService::class.java.name in started,
        )
    }

    @Test
    fun linkStartSitsBehindPairing() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val text = File(root, "app/src/main/java/local/phantom/companion/service/Watchdog.kt").readText()
        val receiver = text.substringAfter("class WatchdogReceiver")
        assertTrue(
            "paired must come from the store and fail closed",
            Regex("""val paired = runCatching \{[^}]*store\.load\(\) != null \}\.getOrDefault\(false\)""").containsMatchIn(receiver),
        )
        val gate = receiver.indexOf("if (paired)")
        val start = receiver.indexOf("PhantomLinkService::class.java")
        assertTrue("PhantomLinkService starts only inside if (paired)", gate in 0 until start)
    }
}
