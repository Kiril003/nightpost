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
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.solana.Cluster
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class CodeEntryScreenTest {

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

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Олена",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = "",
        port = 0,
    )

    private val pcQr = """{"v":2,"pair_id":"p-12345678","server_pub":"AAAA","exp":4102444800,"nonce":"bm9uY2U=",""" +
        """"endpoints":[{"type":"lan","url":"https://192.168.1.5:8443"}]}"""

    private fun pasting(clip: String, seen: MutableList<CodeRoute>): ComponentActivity = show {
        PhantomTheme {
            CodeEntryScreen(
                onBack = {},
                read = { CodeRoute.classify(it, Cluster.DEVNET) },
                onCode = { seen += it; null },
                paste = { clip },
            )
        }
    }

    @Test
    fun `pasted person code reaches onCode as a person`() {
        val seen = mutableListOf<CodeRoute>()
        val a = pasting(PeerInvite.encode(invite), seen)
        val w = words(a)
        assertTrue("немає поля «Код або посилання»: $w", context.getString(R.string.scan_code_field) in w)
        tap(a, context.getString(R.string.scan_paste))
        assertEquals(listOf<CodeRoute>(CodeRoute.Person(invite)), seen)
    }

    @Test
    fun `pasted computer code goes to pairing`() {
        val seen = mutableListOf<CodeRoute>()
        tap(pasting(pcQr, seen), context.getString(R.string.scan_paste))
        assertEquals(listOf<CodeRoute>(CodeRoute.Computer(pcQr)), seen)
        val dest = File(root, "app/src/main/java/local/phantom/companion/navigation/ScanDestination.kt").readText()
        val computer = dest.substringAfter("is CodeRoute.Computer ->").substringBefore("CodeRoute.OwnDevice")
        assertTrue(computer.contains("replaceWith(Routes.Pair.path)"))
    }

    @Test
    fun `pasted junk says it is not a Nightpost code`() {
        val seen = mutableListOf<CodeRoute>()
        val a = pasting("hello there", seen)
        tap(a, context.getString(R.string.scan_paste))
        assertTrue(seen.isEmpty())
        val w = words(a)
        assertTrue("немає «${context.getString(R.string.scan_card_not_ours)}»: $w", context.getString(R.string.scan_card_not_ours) in w)
    }

    @Test
    fun `scanner enter code opens the code field, a typed person is not marked in person`() {
        val dest = File(root, "app/src/main/java/local/phantom/companion/navigation/ScanDestination.kt").readText()
        assertTrue(dest.contains("onEnterCode = { entering = true }"))
        assertFalse("«Ввести код» знову веде у сполучення з компʼютером", dest.contains("onEnterCode = { replaceWith(Routes.Pair.path) }"))
        assertTrue(dest.contains("CodeEntryScreen("))
        assertTrue(dest.contains("onCode = { land(it, typed = true) }"))
        assertTrue(dest.contains("if (typed) PeerIdentity.KeySource.SHARED else PeerIdentity.KeySource.CAMERA"))
    }
}
