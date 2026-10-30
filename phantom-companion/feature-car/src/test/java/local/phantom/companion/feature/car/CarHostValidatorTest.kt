package local.phantom.companion.feature.car

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CarHostValidatorTest {

    @Test
    fun `release build takes the host allowlist, only a debuggable build accepts any host`() {
        assertEquals("allowlist", carHostValidator(debuggable = false, allowAll = { "all" }, allowlist = { "allowlist" }))
        assertEquals("all", carHostValidator(debuggable = true, allowAll = { "all" }, allowlist = { "allowlist" }))
    }

    @Test
    fun `the service decides by the debuggable flag and the sample allowlist`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "feature-car").isDirectory }
        val src = File(root, "feature-car/src/main/java/local/phantom/companion/feature/car/PhantomCarAppService.kt").readText()
        val body = src.substringAfter("override fun createHostValidator()").substringBefore("override fun onCreateSession")
        assertTrue(body.contains("FLAG_DEBUGGABLE"))
        assertTrue(body.contains("hosts_allowlist_sample"))
        assertTrue(Regex("""=\s*carHostValidator\(""").containsMatchIn(body))
    }
}
