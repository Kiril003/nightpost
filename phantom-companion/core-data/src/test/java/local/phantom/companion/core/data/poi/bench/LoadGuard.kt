package local.phantom.companion.core.data.poi.bench

import java.io.File
import org.junit.Assume

// Стеля процесорного часу бреше, коли машина зайнята іншими воротами або пригальмована енергоощадністю: тоді замір пропускається, а не послаблюється.
internal object LoadGuard {
    // Модуль компілюється проти android.jar без java.lang.management, тож бін — через рефлексію.
    fun load(): Double {
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getOperatingSystemMXBean").invoke(null)
        return Class.forName("java.lang.management.OperatingSystemMXBean").getMethod("getSystemLoadAverage").invoke(bean) as Double
    }

    fun cores(): Int = Runtime.getRuntime().availableProcessors()

    fun busy(load: Double, cores: Int): Boolean = load > cores

    // intel_pstate/amd-pstate тримають governor powersave і в «балансі»: там гальмо видно лише з EPP і профілю платформи.
    fun throttled(sys: File = File("/sys")): String? {
        fun read(path: String) = runCatching { File(sys, path).readText().trim() }.getOrNull()
        val cpu = "devices/system/cpu/cpu0/cpufreq"
        val profile = read("firmware/acpi/platform_profile")
        val epp = read("$cpu/energy_performance_preference")
        val governor = read("$cpu/scaling_governor")
        val driver = read("$cpu/scaling_driver").orEmpty()
        return when {
            profile == "low-power" || profile == "quiet" -> "power-saver profile ($profile)"
            epp == "power" || epp == "balance_power" -> "power-saver profile (epp $epp)"
            governor == "powersave" && "pstate" !in driver -> "powersave governor"
            else -> null
        }
    }

    fun assumeQuiet(what: String, load: Double, cores: Int = cores(), throttle: String? = throttled()) {
        println("[$what] навантаження $load на $cores ядер")
        if (throttle != null) println("[$what] skipped: $throttle, ceiling not measured")
        Assume.assumeTrue("[$what] skipped: $throttle, ceiling not measured", throttle == null)
        Assume.assumeFalse("[$what] машина зайнята: навантаження $load > $cores ядер", busy(load, cores))
    }
}
