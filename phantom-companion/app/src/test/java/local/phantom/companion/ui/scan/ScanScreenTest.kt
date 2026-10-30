package local.phantom.companion.ui.scan

import android.app.Application
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ScanScreenTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    @After
    fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun show(content: @Composable () -> Unit): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r, content = content)
        pump()
        return c.get()
    }

    private fun pump() = repeat(20) {
        Snapshot.sendApplyNotifications()
        frameNanos += 50_000_000L
        clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun SemanticsNode.words(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun screen(a: ComponentActivity): List<SemanticsNode> =
        roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun words(a: ComponentActivity): List<String> = screen(a).flatMap { it.words() }

    private fun tap(a: ComponentActivity, text: String) {
        val hit = screen(a)
            .filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { m -> text in m.words() } }
            .minByOrNull { all(it).size } ?: error("немає кнопки «$text»: ${words(a)}")
        hit.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun string(dir: String, file: String, name: String): String? =
        Regex("""<string name="$name">([^<]*)</string>""")
            .find(File(root, "app/src/main/res/$dir/$file").readText())
            ?.groupValues
            ?.get(1)

    @Test
    fun `notOursCard returns card for non-blank text with null route and null otherwise`() {
        assertEquals(ScanCard(R.string.scan_card_not_ours), notOursCard("https://example.com", null))
        assertEquals(ScanCard(R.string.scan_card_not_ours), notOursCard("WIFI:S:home;T:WPA;P:secret;;", null))
        assertNull(notOursCard("", null))
        assertNull(notOursCard("   ", null))
        assertNull(notOursCard("PH1:abc:def", CodeRoute.OldInvite("PH1:abc:def")))
        assertNull(notOursCard("{}", CodeRoute.OwnDevice))
    }

    @Test
    fun `with the camera on the space under it holds a hint and enter code`() {
        var entered = 0
        val a = show {
            PhantomTheme {
                ScanScreen(
                    onBack = {},
                    read = { null },
                    onCode = { null },
                    onEnterCode = { entered += 1 },
                    hasCameraOverride = true,
                )
            }
        }
        val w = words(a)
        assertTrue("під камерою немає підказки: $w", context.getString(R.string.scan_hint) in w)
        val enterLabel = context.getString(R.string.scan_enter_code)
        assertTrue("під камерою немає «$enterLabel»: $w", enterLabel in w)
        tap(a, enterLabel)
        assertEquals(1, entered)
    }

    @Test
    fun `camera denied shows enter code row and tapping it calls onEnterCode`() {
        var entered = 0
        val a = show {
            PhantomTheme {
                ScanScreen(
                    onBack = {},
                    read = { null },
                    onCode = { null },
                    onEnterCode = { entered += 1 },
                    hasCameraOverride = false,
                )
            }
        }
        val enterLabel = context.getString(R.string.scan_enter_code)
        val w = words(a)
        assertTrue("немає тексту відмови камери: $w", context.getString(R.string.pair_camera_denied) in w)
        assertTrue("немає рядка «$enterLabel»: $w", enterLabel in w)

        tap(a, enterLabel)
        assertEquals(1, entered)
    }

    @Test
    fun `scan strings exist in all three locales and ScanDestination wires onEnterCode to the code field`() {
        assertEquals("Not a Nightpost code", string("values", "strings_scan.xml", "scan_card_not_ours"))
        assertEquals("Це не код Nightpost", string("values-uk", "strings_scan.xml", "scan_card_not_ours"))
        assertEquals("Toto není kód Nightpost", string("values-cs", "strings_scan.xml", "scan_card_not_ours"))

        assertEquals("Enter code", string("values", "strings_scan.xml", "scan_enter_code"))
        assertEquals("Ввести код", string("values-uk", "strings_scan.xml", "scan_enter_code"))
        assertEquals("Zadat kód", string("values-cs", "strings_scan.xml", "scan_enter_code"))

        val dest = File(root, "app/src/main/java/local/phantom/companion/navigation/ScanDestination.kt").readText()
        assertTrue(dest.contains("onEnterCode = { entering = true }"))
    }
}
