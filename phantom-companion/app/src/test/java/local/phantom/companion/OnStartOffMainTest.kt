package local.phantom.companion

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cold start on the A52: Keystore and encrypted prefs must not run on main before the first frame. */
class OnStartOffMainTest {

    private val body: String = run {
        val src = listOf("src/main/java/local/phantom/companion/MainActivity.kt", "app/src/main/java/local/phantom/companion/MainActivity.kt")
            .map(::File).first { it.exists() }.readText()
        val from = src.indexOf("override fun onStart()")
        src.substring(from, src.indexOf("override fun onStop()", from))
    }

    @Test
    fun `the link service starts off the main thread`() {
        body.lines().filter { "ensureRunning(" in it }.forEach { line ->
            assertTrue("на головному потоці: ${line.trim()}", "launch(Dispatchers.IO)" in line)
        }
        assertTrue("служба звʼязку зникла з onStart", "ensureRunning(" in body)
    }
}
