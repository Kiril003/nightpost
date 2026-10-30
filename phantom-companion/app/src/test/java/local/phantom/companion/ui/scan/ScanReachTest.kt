package local.phantom.companion.ui.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanReachTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String): String =
        File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `scanner route and entries are wired end to end`() {
        assertTrue(src("navigation/Routes.kt").contains("""data object Scan : Routes("scan")"""))

        val navGraph = src("navigation/PhantomNavGraph.kt")
        assertTrue(navGraph.contains("composable(Routes.Scan.path) { ScanDestination(navController) }"))
        assertTrue(navGraph.contains("events.pairPayload"))

        val bridge = src("ui/messenger/WebMessengerBridge.kt")
        assertTrue(bridge.contains("WebNav.Act.Scan ->"))
        assertTrue(bridge.contains("Routes.Scan.path"))

        val solana = src("data/SolanaWiring.kt")
        assertTrue(solana.contains("scan = {"))
        assertTrue(solana.contains("Routes.Scan.path"))

        assertTrue(src("ui/home/ProfileTab.kt").contains("Routes.Scan.path"))

        val dest = src("navigation/ScanDestination.kt")
        assertTrue(dest.contains("ScanScreen("))
        assertTrue(dest.contains("events.pairPayload.value"))
        assertTrue(dest.contains("events.webSignInScan.value"))
        assertTrue(dest.contains("events.mapPointRequest.value"))
        assertTrue(dest.contains("peerCardRequest.value"))
        assertTrue(dest.contains("WalletActivity.scannedIntent("))

        assertTrue(src("ui/scan/ScanScreen.kt").contains("LiveQrCamera("))

        val webSignIn = src("ui/screens/WebSignInScreen.kt")
        assertTrue(webSignIn.contains("webSignInScan"))
        assertTrue(webSignIn.contains("LaunchedEffect(scannedPayload)"))
    }

    private fun res(path: String): String = File(root, "app/src/main/res/$path").readText()

    private fun string(dir: String, file: String, name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""").find(res("$dir/$file"))?.groupValues?.get(1)

    @Test
    fun `unknown code keeps the camera looking and the screen shows no raw payload`() {
        val screen = src("ui/scan/ScanScreen.kt")
        assertTrue("аналізатор не віддає false на чужий код", screen.contains("read(text)?.let") && screen.contains("?: false"))
        assertFalse("екран сканера показує сирий вміст коду", screen.contains("scan_copy") || screen.contains("ACTION_VIEW"))
        val dest = src("navigation/ScanDestination.kt")
        assertTrue(dest.contains("read = { CodeRoute.classify(it, SolanaLayer.state.value.cluster) }"))
        listOf("values-uk", "values").forEach { dir ->
            listOf("scan_card_text", "scan_card_link", "scan_copy", "scan_open").forEach {
                assertEquals("$dir: $it лишився без вжитку", null, string(dir, "strings_scan.xml", it))
            }
        }
    }

    @Test
    fun `my code dialog has two labels, Scan and Send link, and no sentence`() {
        val tab = src("ui/home/ProfileTab.kt")
        val dialog = tab.substringAfter("private fun MyCodeDialog(").substringBefore("private fun qrBitmap(")
        assertFalse("пояснення «Хай людина відсканує» лишилось", dialog.contains("profile_code_body"))
        assertTrue(dialog.contains("confirm = stringResource(R.string.profile_code_send)"))
        assertTrue("«Сканувати» не стоїть кнопкою діалогу", dialog.contains("secondary = stringResource(R.string.scan_action) to onScan"))
        assertFalse(dialog.contains("GhostButton("))

        val frame = src("ui/home/ProfileDialog.kt")
        assertTrue(frame.contains("secondary: Pair<String, () -> Unit>? = null"))
        assertTrue(frame.contains("val (second, onSecond) = secondary ?: (stringResource(R.string.action_cancel) to onDismiss)"))

        assertEquals("Scan", string("values", "strings_scan.xml", "scan_action"))
        assertEquals("Сканувати", string("values-uk", "strings_scan.xml", "scan_action"))
        assertEquals("Send link", string("values", "strings_profile_face.xml", "profile_code_send"))
        listOf("values-uk", "values").forEach {
            assertEquals(null, string(it, "strings_profile_face.xml", "profile_code_body"))
        }
    }
}
