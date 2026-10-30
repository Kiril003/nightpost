package local.phantom.companion.packaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class TargetSdkContractTest {

    private val tree: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun parse(f: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(f).documentElement

    private val androidNs = "http://schemas.android.com/apk/res/android"

    @Test
    fun `every module compiles against 36`() {
        val gradleFiles = tree.listFiles().orEmpty()
            .filter { it.isDirectory }
            .map { File(it, "build.gradle.kts") }
            .filter { it.isFile && it.readText().contains("compileSdk") }
        assertTrue(gradleFiles.isNotEmpty())
        gradleFiles.forEach { f ->
            val text = f.readText()
            assertTrue("${f.parentFile.name}/build.gradle.kts must have compileSdk = 36", text.contains("compileSdk = 36"))
            assertFalse("${f.parentFile.name}/build.gradle.kts still has compileSdk = 34", text.contains("compileSdk = 34"))
        }
    }

    @Test
    fun `app targets 36`() {
        val text = File(tree, "app/build.gradle.kts").readText()
        assertTrue(text.contains("targetSdk = 36"))
        assertFalse(text.contains("targetSdk = 34"))
    }

    @Test
    fun `agp supports 36`() {
        val toml = File(tree, "gradle/libs.versions.toml").readText()
        assertTrue(toml.contains("agp = \"8.10.1\""))
    }

    @Test
    fun `every dataSync service stops on timeout`() {
        val manifest = parse(File(tree, "app/src/main/AndroidManifest.xml"))
        val nodes = manifest.getElementsByTagName("service")
        val services = (0 until nodes.length).map { nodes.item(it) as Element }
            .filter { it.getAttributeNS(androidNs, "foregroundServiceType").contains("dataSync") }
            .map { it.getAttributeNS(androidNs, "name") }
        assertTrue(services.isNotEmpty())
        services.forEach { name ->
            val rel = name.removePrefix(".service.")
            val src = File(tree, "app/src/main/java/local/phantom/companion/service/$rel.kt")
            assertTrue("missing source for $name at ${src.path}", src.isFile)
            assertTrue(
                "$name must override onTimeout(startId: Int, fgsType: Int)",
                src.readText().contains("override fun onTimeout(startId: Int, fgsType: Int)"),
            )
        }
    }

    @Test
    fun `back over the lock is a callback`() {
        val main = File(tree, "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue(main.contains("CallOverBack("))
        assertTrue(main.contains("OnBackInvokedDispatcher.PRIORITY_OVERLAY"))
    }
}
