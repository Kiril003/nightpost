package local.phantom.companion.ui.home

import android.app.Application
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.data.PairedDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "uk-w411dp-h1200dp-xhdpi")
class DevicesBodyTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private var unpaired = 0
    private var pairedClicks = 0
    private var licenseClicks = 0

    @After
    fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
        controller = null
        recomposer = null
    }

    private val fixture = PairedDevice(
        lanUrl = "https://192.168.1.10:8443",
        devNoPin = false,
        deviceId = "dev-1",
        userId = "u-1",
        username = "ira",
        deviceJwt = "jwt",
        expiresAt = "2026-12-31T00:00:00Z",
        deviceEd25519Priv = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        deviceEd25519Pub = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=",
        computerName = "ThinkPad",
        nodePeerId = "node-1",
        nodePubKey = "pub",
        nodeDhPubKey = "dh",
    )

    private fun pump() {
        repeat(20) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
    }

    private fun open(
        paired: PairedDevice? = fixture,
        canPair: Boolean = true,
    ): ComponentActivity {
        close()
        unpaired = 0
        pairedClicks = 0
        licenseClicks = 0
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) {
                DevicesBody(
                    paired = paired,
                    canPair = canPair,
                    onUnpair = { unpaired++ },
                    onPair = { pairedClicks++ },
                    onOpenLicense = { licenseClicks++ },
                )
            }
        }
        pump()
        return c.get()
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    @Suppress("UNCHECKED_CAST")
    private fun windows(a: ComponentActivity): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(instance) as List<View>).toList()
    }.getOrDefault(emptyList()).let { (it + a.window.decorView).distinct() }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) =
        windows(a).flatMap(::roots).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun tagged(a: ComponentActivity, tag: String) =
        screen(a).filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun button(a: ComponentActivity, label: String) =
        screen(a).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { label in it.texts() } }
            .minByOrNull { all(it).size }

    @Test
    fun `paired device shows one dev-pc and one dev-this-phone and null paired shows zero dev-pc`() {
        val a = open(paired = fixture)
        assertEquals(1, tagged(a, "dev-this-phone").size)
        assertEquals(1, tagged(a, "dev-pc").size)
        assertNull(button(a, "Цей телефон"))
        assertTrue("ThinkPad" in all(tagged(a, "dev-pc").single()).flatMap { it.texts() })

        val b = open(paired = null, canPair = true)
        assertEquals(1, tagged(b, "dev-this-phone").size)
        assertEquals(0, tagged(b, "dev-pc").size)
    }

    @Test
    fun `tapping disconnect and confirming calls onUnpair once while dismissing does not`() {
        val a = open(paired = fixture)
        val disconnect = button(a, "Відключити")
        assertNotNull("кнопки «Відключити» немає", disconnect)
        disconnect!!.config[SemanticsActions.OnClick].action!!.invoke()
        pump()

        val cancel = button(a, a.getString(R.string.action_cancel))
        assertNotNull("кнопки скасування в діалозі немає", cancel)
        cancel!!.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertEquals(0, unpaired)

        button(a, "Відключити")!!.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        val confirm = button(a, a.getString(R.string.look_unpair))
        assertNotNull("кнопки підтвердження в діалозі немає", confirm)
        confirm!!.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertEquals(1, unpaired)
    }

    @Test
    fun `without the pairing licence the page offers the licence instead of a dead end`() {
        val a = open(paired = null, canPair = false)
        val gate = button(a, "Звʼязка з ПК — у рівні «Особистий»")
        assertNotNull("licence row missing", gate)
        gate!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, licenseClicks)
        assertEquals(0, pairedClicks)
    }

    @Test
    fun `a paired record without a drop key asks to pair again`() {
        val fresh = open(paired = fixture)
        val stale = screen(fresh).flatMap { it.texts() }
        assertFalse(stale.any { "Спаруйте ще раз" in it })
        val old = open(paired = fixture.copy(nodeDhPubKey = ""))
        assertTrue(screen(old).flatMap { it.texts() }.any { "Спаруйте ще раз" in it })
    }

    @Test
    fun `no node with Site or Сайт in any state`() {
        val states = listOf(
            open(paired = fixture, canPair = true),
            open(paired = null, canPair = true),
            open(paired = null, canPair = false),
        )
        for (a in states) {
            val words = screen(a).flatMap { it.texts() }
            assertFalse("є Сайт/Site у: $words", words.any { "Сайт" in it || "Site" in it })
        }
    }

    @Test
    fun `strings exist in all three locales`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/res").isDirectory }
        fun readStrings(dir: String): Map<String, String> =
            Regex("""<string name="([^"]+)">([^<]*)</string>""")
                .findAll(File(root, "app/src/main/res/$dir/strings_home.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2] }

        val en = readStrings("values")
        val uk = readStrings("values-uk")
        val cs = readStrings("values-cs")

        assertEquals("This phone", en["devices_this_phone"])
        assertEquals("Цей телефон", uk["devices_this_phone"])
        assertEquals("Tento telefon", cs["devices_this_phone"])

        assertEquals("Disconnect", en["devices_disconnect"])
        assertEquals("Відключити", uk["devices_disconnect"])
        assertEquals("Odpojit", cs["devices_disconnect"])

    }
}
