package local.phantom.companion.ui.messenger

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.internal.AssumptionViolatedException

class LoadGuardTest {

    @Test
    fun `зайнята машина пропускає замір`() {
        assertTrue(LoadGuard.busy(31.0, 12))
        val skipped = runCatching { LoadGuard.assumeQuiet("тест", 31.0, 12, throttle = null) }.exceptionOrNull()
        assertTrue(skipped is AssumptionViolatedException)
    }

    @Test
    fun `тиха машина мірить і стелю перевіряє`() {
        assertFalse(LoadGuard.busy(3.5, 12))
        assertFalse(LoadGuard.busy(-1.0, 12))
        LoadGuard.assumeQuiet("тест", 3.5, 12, throttle = null)
    }

    @Test
    fun `навантаження машини видно з тесту`() {
        val load = LoadGuard.load()
        assertTrue("навантаження $load", load == -1.0 || load >= 0.0)
    }

    private fun sys(vararg files: Pair<String, String>): File {
        val root = Files.createTempDirectory("sys").toFile()
        files.forEach { (path, text) -> File(root, path).apply { parentFile.mkdirs(); writeText(text + "\n") } }
        return root
    }

    private val cpu = "devices/system/cpu/cpu0/cpufreq"

    @Test
    fun `енергоощадність пропускає замір`() {
        assertEquals("power-saver profile (epp power)", LoadGuard.throttled(sys("$cpu/scaling_driver" to "intel_pstate", "$cpu/scaling_governor" to "powersave", "$cpu/energy_performance_preference" to "power")))
        assertEquals("power-saver profile (quiet)", LoadGuard.throttled(sys("firmware/acpi/platform_profile" to "quiet")))
        assertEquals("powersave governor", LoadGuard.throttled(sys("$cpu/scaling_driver" to "acpi-cpufreq", "$cpu/scaling_governor" to "powersave")))
        val skipped = runCatching { LoadGuard.assumeQuiet("тест", 1.0, 12, throttle = "powersave governor") }.exceptionOrNull()
        assertTrue(skipped is AssumptionViolatedException)
    }

    @Test
    fun `продуктивний профіль мірить`() {
        assertNull(LoadGuard.throttled(sys("$cpu/scaling_driver" to "intel_pstate", "$cpu/scaling_governor" to "powersave", "$cpu/energy_performance_preference" to "performance", "firmware/acpi/platform_profile" to "performance")))
        assertNull(LoadGuard.throttled(sys()))
    }
}
