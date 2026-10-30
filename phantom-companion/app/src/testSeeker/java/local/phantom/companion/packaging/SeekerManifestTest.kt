package local.phantom.companion.packaging

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

// Читає ЗЛИТИЙ маніфест (з бібліотеками і модулями), а не джерела: саме його бачить рецензент dApp Store.
class SeekerManifestTest {

    private val ns = "http://schemas.android.com/apk/res/android"

    private val merged: Element by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "app/src/seeker").isDirectory) dir = dir.parentFile
        val root = File(dir ?: error("не знайшов корінь дерева"), "app/build/intermediates/merged_manifest/seekerDebug")
        val file = root.walkTopDown().firstOrNull { it.name == "AndroidManifest.xml" }
            ?: error("немає злитого маніфесту в $root — тест має йти після processSeekerDebugMainManifest")
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun attrs(tag: String, attr: String): Set<String> {
        val n = merged.getElementsByTagName(tag)
        return (0 until n.length).map { (n.item(it) as Element).getAttributeNS(ns, attr) }.toSet()
    }

    @Test fun `немає дозволів, на які рецензент відповість відмовою`() {
        val forbidden = setOf(
            "android.permission.SEND_SMS",
            "android.permission.RECEIVE_SMS",
            "android.permission.READ_SMS",
            "android.permission.ACCESS_MOCK_LOCATION",
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.QUERY_ALL_PACKAGES",
            "com.android.vending.BILLING",
        )
        val left = attrs("uses-permission", "name") intersect forbidden
        assertTrue("seeker просить $left", left.isEmpty())
    }

    @Test fun `немає служб спецможливостей, слухача сповіщень, SMS-приймача й оверлеїв`() {
        val binds = setOf(
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.permission.BROADCAST_SMS",
        )
        val bound = (attrs("service", "permission") + attrs("receiver", "permission")) intersect binds
        assertTrue("seeker оголошує компоненти з $bound", bound.isEmpty())
        val gone = setOf(
            "local.phantom.companion.service.FalsePositionService",
            "local.phantom.companion.feature.ambient.service.FloatingOrbService",
            "local.phantom.companion.feature.ambient.service.WhisperLineService",
            "local.phantom.companion.feature.godmode.service.NotificationReceiverService",
            "local.phantom.companion.feature.godmode.service.ScreenAwarenessService",
            "local.phantom.companion.data.intent.IntentSmsReceiver",
        )
        val left = (attrs("service", "name") + attrs("receiver", "name")) intersect gone
        assertTrue("у seeker лишились $left", left.isEmpty())
    }
}
