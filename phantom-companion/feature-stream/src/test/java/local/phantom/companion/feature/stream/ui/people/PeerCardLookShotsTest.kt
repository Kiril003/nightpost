package local.phantom.companion.feature.stream.ui.people

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.core.net.peer.PeerPurge
import local.phantom.companion.core.net.peer.PeerRoster
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Кадри картки людини на екрані A52, у двох темах. Лягають у build/peer-card-look/;
 * «-end» — той самий стан, прокручений донизу. Падає, лише якщо картка не намалювалась.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-w411dp-h891dp-xhdpi")
class PeerCardLookShotsTest {

    private val nowMs = 1_790_000_000_000L
    private val me = PeerInvite.Invite(
        peerId = "f00dfeedcafe0001",
        name = "Кирило",
        pubKey = "MY-SIGN-KEY",
        dhPubKey = "MY-DH-KEY",
        host = "10.0.0.2",
        port = 47921,
    )
    private val history = PeerPurge.Scope(ids = (1..42).map { "m$it" }.toSet(), count = 42, fromMs = nowMs - 40L * 86_400_000L, toMs = nowMs - 3_600_000L)

    private fun peer(
        name: String = "Оксана Литвин",
        source: PeerIdentity.KeySource,
        verified: Boolean,
        unreadable: Boolean = false,
        keyChanged: Boolean = false,
    ) = PeerIdentity(
        id = "a1b2c3d4e5f60718",
        name = if (unreadable) PeerRoster.shortId("a1b2c3d4e5f60718") else name,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "THEIR-SIGN-KEY",
        dhPubKey = "THEIR-DH-KEY",
        lastSeenMs = nowMs - 30_000L,
        lanSeenMs = nowMs - 30_000L,
        verifiedAtMs = if (verified) nowMs - 86_400_000L else 0L,
        keySource = source,
        nameUnreadable = unreadable,
        keyChangedAtMs = if (keyChanged) nowMs - 60_000L else 0L,
    )

    @Test fun provenNight() = shots("proven-night", AmberNight) { card(peer(source = PeerIdentity.KeySource.CAMERA, verified = true)) }
    @Test fun provenDay() = shots("proven-day", SunriseWarm) { card(peer(source = PeerIdentity.KeySource.CAMERA, verified = true)) }
    @Test fun sharedNight() = shots("shared-night", AmberNight) { card(peer(source = PeerIdentity.KeySource.SHARED, verified = false)) }
    @Test fun sharedDay() = shots("shared-day", SunriseWarm) { card(peer(source = PeerIdentity.KeySource.SHARED, verified = false)) }
    @Test fun unreadableNight() = shots("unreadable-night", AmberNight) {
        card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false, unreadable = true))
    }
    @Test fun unreadableDay() = shots("unreadable-day", SunriseWarm) {
        card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false, unreadable = true))
    }
    @Test fun removeArmedNight() = shots("remove-armed-night", AmberNight, tap = REMOVE) {
        card(peer(source = PeerIdentity.KeySource.CAMERA, verified = true))
    }
    @Test fun removeArmedDay() = shots("remove-armed-day", SunriseWarm, tap = REMOVE) {
        card(peer(source = PeerIdentity.KeySource.CAMERA, verified = true))
    }

    @Test fun verifyOpenNight() = shots("verify-open-night", AmberNight, tap = VERIFY) {
        card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false), handle = "oksana_test1")
    }
    @Test fun verifyOpenDay() = shots("verify-open-day", SunriseWarm, tap = VERIFY) {
        card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false), handle = "oksana_test1")
    }

    @Test
    fun `scanned card leads with the person, verification is one optional row`() {
        val texts = texts { card(peer(source = PeerIdentity.KeySource.CAMERA, verified = false)) }
        assertFalse("«число розмови» на картці: $texts", texts.any { it.contains("число розмови", ignoreCase = true) })
        assertFalse("код ключа на картці: $texts", texts.any { SHORT_ID.containsMatchIn(it) })
        assertTrue("немає рядка «$VERIFY»: $texts", texts.any { it.equals(VERIFY, ignoreCase = true) })
        assertTrue(texts.any { it.equals("Необовʼязково", ignoreCase = true) })
        assertFalse("звірка розкрита без потреби", texts.any { it == "Код безпеки" })
        assertFalse("знята звірка, а шапка каже «Наживо»: $texts", texts.any { it.startsWith("Наживо") })
    }

    @Test
    fun `nameless contact is titled by the handle, never by the key`() {
        val texts = texts { card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false, unreadable = true), handle = "oksana_test1") }
        assertTrue(texts.toString(), texts.contains("@oksana_test1"))
        assertTrue(texts.toString(), texts.any { it.startsWith("Знайдено за ніком") })
        assertFalse(texts.any { it.startsWith("За @") })
        assertFalse("код ключа на картці: $texts", texts.any { SHORT_ID.containsMatchIn(it) })
    }

    @Test
    fun `tapping the optional row opens the safety code`() {
        val texts = texts(tap = VERIFY) { card(peer(source = PeerIdentity.KeySource.REMOTE, verified = false), handle = "oksana_test1") }
        assertTrue("звірка не розкрилась по дотику: $texts", texts.any { it == "Код безпеки" })
        assertTrue(texts.any { it.equals("Цифри збігаються", ignoreCase = true) })
    }

    @Test
    fun `a changed key keeps the verification open on top`() {
        val texts = texts { card(peer(source = PeerIdentity.KeySource.CAMERA, verified = true, keyChanged = true)) }
        assertTrue("звірка згорнута при зміні ключа: $texts", texts.any { it == "Код безпеки" })
        assertFalse(texts.any { it.equals(VERIFY, ignoreCase = true) })
    }

    private fun texts(tap: String? = null, content: @Composable () -> Unit): List<String> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val recomposer = mount(activity, SunriseWarm, content)
        if (tap != null) {
            val node = nodes(activity).firstOrNull { text(it).equals(tap, ignoreCase = true) && clickable(it) != null }
            assertTrue("на картці немає «$tap»", node != null)
            clickable(node!!)!!.invoke()
            pump()
        }
        val out = nodes(activity).flatMap { listOf(text(it), edited(it)) }.filter { it.isNotEmpty() }
        recomposer.cancel()
        controller.pause().stop().destroy()
        return out
    }

    @Composable
    private fun card(peer: PeerIdentity, handle: String? = null) = PeerCardScreen(
        peer = peer,
        handle = handle,
        onBack = {},
        onRename = {},
        onSetVerified = {},
        onRemove = {},
        onOpenChat = {},
        nowMs = nowMs,
        onScanInvite = { PeerInvite.Decoded.NotInvite },
        myInvite = me,
        history = history,
        vaultCounts = SharedVaultCounts(media = 3, files = 1, places = 2),
        onClearHistory = {},
        sharesGroup = true,
        onShareStart = {},
        onShareStop = {},
        theirPositionAtMs = nowMs - 12 * 60_000L,
    )

    private fun shots(
        name: String,
        spec: PhantomThemeSpec,
        tap: String? = null,
        content: @Composable () -> Unit,
    ) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val recomposer = if (tap != null) mount(activity, spec, content) else null
        if (recomposer == null) {
            activity.setContent {
                PhantomTheme(spec = spec) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        local.phantom.companion.core.design.motion.LocalMotionScale provides 0f,
                    ) { content() }
                }
            }
            settle()
        }
        if (tap != null) {
            val node = nodes(activity).firstOrNull { text(it).equals(tap, ignoreCase = true) && clickable(it) != null }
            assertTrue("$name: на картці немає «$tap»", node != null)
            clickable(node!!)!!.invoke()
            pump()
        }
        capture(activity, name)
        recomposer?.cancel()
        if (tap == null) {
            val view = findRoot(activity.window.decorView) as View
            repeat(6) { swipeUp(view) }
            capture(activity, "$name-end")
        }
        controller.pause().stop().destroy()
    }

    private val clock = androidx.compose.runtime.BroadcastFrameClock()
    private var frameNanos = 0L

    // Свій перескладач із ручним годинником: стандартний у Robolectric не бачить змін стану після дотику.
    private fun mount(activity: ComponentActivity, spec: PhantomThemeSpec, content: @Composable () -> Unit): androidx.compose.runtime.Recomposer {
        val ctx = kotlinx.coroutines.Dispatchers.Main + clock
        val r = androidx.compose.runtime.Recomposer(ctx)
        kotlinx.coroutines.CoroutineScope(ctx + kotlinx.coroutines.Job()).launch { r.runRecomposeAndApplyChanges() }
        activity.setContent(parent = r) {
            PhantomTheme(spec = spec) {
                androidx.compose.runtime.CompositionLocalProvider(
                    local.phantom.companion.core.design.motion.LocalMotionScale provides 0f,
                ) { content() }
            }
        }
        pump()
        return r
    }

    private fun pump() = repeat(40) {
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        frameNanos += 50_000_000L
        clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
    }

    private fun settle() = repeat(40) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100)) }

    private fun swipeUp(view: View) {
        val x = view.width / 2f
        val from = view.height * 0.85f
        val to = view.height * 0.15f
        val t0 = android.os.SystemClock.uptimeMillis()
        fun event(action: Int, y: Float, at: Long) =
            android.view.MotionEvent.obtain(t0, at, action, x, y, 0).also { view.dispatchTouchEvent(it); it.recycle() }
        event(android.view.MotionEvent.ACTION_DOWN, from, t0)
        val steps = 30
        for (i in 1..steps) {
            event(android.view.MotionEvent.ACTION_MOVE, from + (to - from) * i / steps, t0 + i * 40L)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(40))
        }
        event(android.view.MotionEvent.ACTION_UP, to, t0 + steps * 40L + 400L)
        settle()
    }

    private fun capture(activity: ComponentActivity, name: String) {
        val root = activity.findViewById<View>(android.R.id.content)
        val full = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
        // PixelCopy малює через HardwareRenderer — інакше шари graphicsLayer не потрапляють у кадр.
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
        val out = File("build/peer-card-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: картка не намалювалась", inked > 50)
    }

    private fun nodes(activity: ComponentActivity): List<SemanticsNode> {
        val owner = findRoot(activity.window.decorView)?.semanticsOwner ?: return emptyList()
        val out = mutableListOf<SemanticsNode>()
        fun walk(node: SemanticsNode) {
            out += node
            node.children.forEach(::walk)
        }
        walk(owner.unmergedRootSemanticsNode)
        return out
    }

    private fun findRoot(view: View): RootForTest? {
        if (view is RootForTest) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findRoot(view.getChildAt(i))?.let { return it }
        return null
    }

    private fun edited(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()

    private fun text(node: SemanticsNode): String =
        node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text }.orEmpty()

    private fun clickable(node: SemanticsNode): (() -> Boolean)? {
        var at: SemanticsNode? = node
        while (at != null) {
            at.config.getOrNull(SemanticsActions.OnClick)?.action?.let { return it }
            at = at.parent
        }
        return null
    }

    private companion object {
        const val REMOVE = "прибрати з реєстру"
        const val VERIFY = "Перевірити наживо"
        val SHORT_ID = Regex("[0-9a-f]{4}·[0-9a-f]{4}")
    }
}
