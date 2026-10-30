package local.phantom.companion.data

import java.io.File
import local.phantom.companion.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Переїзд Fly → Oracle (vc30): людина нічого не перемикає, збірка вже ходить куди треба. */
class OracleHostTest {

    private val fly = "https://phantom-license.fly.dev"

    private fun legacyHost(): String =
        BuildConfig::class.java.getField("LEGACY_RELAY_HOST").get(null) as String

    @Test
    fun `the server address is Oracle, even when local properties still name Fly`() {
        assumeTrue(BuildConfig.LICENSE_BASE_URL.isNotBlank())
        assertNotEquals(fly, BuildConfig.LICENSE_BASE_URL.trimEnd('/'))
        assertEquals("https://api.phantom-os.dev", BuildConfig.LICENSE_BASE_URL.trimEnd('/'))
    }

    @Test
    fun `Fly stays only as the second relay`() {
        assumeTrue(BuildConfig.LICENSE_BASE_URL.isNotBlank())
        assertEquals(fly, legacyHost())
    }

    @Test
    fun `the relay the phone opens carries the Fly fan-out and its poll cadence`() {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val root = File(dir ?: error("не знайшов корінь дерева"), "app/src/main/java/local/phantom/companion/data")
        val container = File(root, "AppContainer.kt").readText()
        val mesh = File(root, "PeerMeshRelay.kt").readText()
        assertTrue(container.contains("legacyUrl = local.phantom.companion.BuildConfig.LEGACY_RELAY_HOST"))
        assertTrue(mesh.contains("transport?.pollEveryMs"))
    }
}
