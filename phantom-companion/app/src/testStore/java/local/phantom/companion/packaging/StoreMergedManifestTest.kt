package local.phantom.companion.packaging

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class StoreMergedManifestTest {

    private val ns = "http://schemas.android.com/apk/res/android"

    private val merged: Element by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/store").isDirectory) dir = dir.parentFile
        val root = File(dir ?: error("не знайшов корінь дерева"), "app/build/intermediates/merged_manifest/storeDebug")
        val file = root.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
            ?: error("немає злитого маніфесту в $root — тест має йти після processStoreDebugMainManifest")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun attrs(tag: String, attr: String): Set<String> {
        val n = merged.getElementsByTagName(tag)
        return (0 until n.length).map { (n.item(it) as Element).getAttributeNS(ns, attr) }.toSet()
    }

    @Test
    fun `store asks no battery exemption, special use, health or car`() {
        val forbidden = setOf(
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            "android.permission.FOREGROUND_SERVICE_SPECIAL_USE",
            "android.permission.FOREGROUND_SERVICE_HEALTH",
            "androidx.car.app.NAVIGATION_TEMPLATES",
            "androidx.car.app.ACCESS_SURFACE",
        )
        val left = attrs("uses-permission", "name") intersect forbidden
        assertTrue("store просить $left", left.isEmpty())
    }

    @Test
    fun `store has no alert watch or car service`() {
        val services = attrs("service", "name")
        assertFalse(services.any { it.endsWith("AirAlertService") })
        assertFalse(services.any { it.endsWith("PhantomCarAppService") })
        val actions = attrs("action", "name")
        assertFalse("androidx.car.app.CarAppService" in actions)
        val meta = attrs("meta-data", "name")
        assertFalse("com.google.android.gms.car.application" in meta)
        assertFalse("androidx.car.app.minCarApiLevel" in meta)
    }

    @Test
    fun `store FGS types are location, dataSync, microphone, camera only`() {
        val allowed = setOf("location", "dataSync", "microphone", "camera")
        val nodes = merged.getElementsByTagName("service")
        val types = (0 until nodes.length)
            .map { (nodes.item(it) as Element).getAttributeNS(ns, "foregroundServiceType") }
            .filter { it.isNotBlank() }
            .flatMap { it.split("|") }
            .toSet()
        val extra = types - allowed
        assertTrue("недозволені типи FGS у store: $extra", extra.isEmpty())
    }

    @Test
    fun `store keeps full screen for calls`() {
        assertTrue("android.permission.USE_FULL_SCREEN_INTENT" in attrs("uses-permission", "name"))
    }
}
