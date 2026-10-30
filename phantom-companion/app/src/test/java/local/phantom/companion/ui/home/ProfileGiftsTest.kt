package local.phantom.companion.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
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
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.DayClear
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.net.peer.PeerCard
import local.phantom.companion.core.net.peer.PeerMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Подарунки» в рідному Профілі: лише отримані (свої й криві — ні, повтор
 * доставки — раз), найновіші першими; рядок із числом і трьома останніми,
 * без подарунків рядка немає. Дані — той самий лог листів, що й Моменти.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "uk-w390dp-h1200dp-xhdpi")
class ProfileGiftsTest {

    private val me = "me_fp"
    private val oks = "oks_fp"
    private val and = "and_fp"

    private fun gift(id: String, from: String, json: String, at: Long) = PeerMessage(
        id = id,
        fromId = from,
        toId = if (from == me) oks else me,
        body = PeerCard.encode(PeerCard.Body(PeerCard.Card.GIFT, json))!!,
        sentAtMs = at,
    )

    private val log = listOf(
        PeerMessage(id = "t1", fromId = oks, toId = me, body = "привіт", sentAtMs = 1_000L),
        gift("a", oks, """{"v":1,"id":"g_a","gift":"flowers","note":"З днем"}""", 2_000L),
        gift("b", me, """{"v":1,"id":"g_b","gift":"coffee"}""", 3_000L),
        gift("c", oks, """{"v":1,"id":"g_c","gift":"tea"}""", 7_000L),
        gift("bad", oks, """{"v":1,"id":"g bad","gift":"<x>"}""", 8_000L),
        gift("d", and, """{"v":1,"id":"g_d","gift":"book"}""", 5_000L),
        gift("d-again", and, """{"v":1,"id":"g_d","gift":"book"}""", 5_000L),
        gift("e", and, """{"v":1,"id":"g_e","gift":"comet"}""", 4_000L),
        PeerMessage(id = "p", fromId = oks, toId = me, body = PeerCard.encode(PeerCard.Body(PeerCard.Card.PERSON, """{"v":1}"""))!!, sentAtMs = 9_000L),
    )

    @Test fun `only gifts that came to me, newest first, once each`() {
        val got = ProfileGifts.received(log, me)
        assertEquals(listOf("g_c", "g_d", "g_e", "g_a"), got.map { it.id })
        assertEquals(ProfileGifts.Got("g_a", "flowers", "З днем", oks, 2_000L), got.last())
        assertEquals("невідомий рід лишається подарунком", "comet", got[2].gift)
        assertTrue("без мене — нічого", ProfileGifts.received(log, "").isEmpty())
    }

    // ── рядок у Профілі ─────────────────────────────────────────────────

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private var opened = 0

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    @Composable
    private fun face(gifts: List<ProfileGifts.Got>) = ProfileFaceBody(
        me = Me(name = "Кирило", photo = null),
        handle = "kyrylo",
        circles = emptyList(),
        highlights = emptyList(),
        failed = false,
        naming = false,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = {},
        openTab = {}, composeMoment = {},
        gifts = gifts,
        onGifts = { opened++ },
    )

    private val names = mapOf(oks to "Оксана", and to "Андрій")

    private fun open(gifts: List<ProfileGifts.Got>, spec: PhantomThemeSpec = AmberNight, list: Boolean = false): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(local.phantom.companion.core.design.motion.LocalMotionScale provides 0f) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        if (list) androidx.compose.foundation.layout.Column { GiftsList(gifts) { names[it] } } else face(gifts)
                    }
                }
            }
        }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        return c.get()
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) = roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun tagged(a: ComponentActivity, tag: String) = screen(a).filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun texts(n: SemanticsNode) = all(n).flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty().map { t -> t.text } }

    @Test fun `the row shows the count and the last three, a tap opens the list`() {
        val a = open(ProfileGifts.received(log, me))
        val row = tagged(a, "you-gifts").single()
        val words = texts(row)
        assertTrue(words.toString(), words.contains("Подарунки") && words.contains("4"))
        assertEquals("три останні", 3, all(row).count { it.config.getOrNull(SemanticsProperties.TestTag) == "you-gift-icon" })
        row.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, opened)
    }

    @Test fun `no gifts, no row`() {
        val a = open(emptyList())
        assertTrue(tagged(a, "you-gifts").isEmpty())
    }

    @Test fun `the list names each gift and who sent it, unknown kinds stay a gift`() {
        val a = open(ProfileGifts.received(log, me), list = true)
        val rows = tagged(a, "gift-row")
        assertEquals(4, rows.size)
        val first = texts(rows.first())
        assertTrue(first.toString(), first.contains("Чай") && first.any { "Оксана" in it })
        assertTrue(texts(rows[2]).toString(), texts(rows[2]).contains("Подарунок"))
        assertTrue(texts(rows.last()).toString(), texts(rows.last()).any { "З днем" in it })
    }

    @Test fun `the Profile tab feeds the row from the message log and opens the list`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val tab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        val body = tab.substringAfter("internal fun ProfileTab(").substringBefore("internal fun ProfileFaceBody(")
        assertTrue("лог листів → подарунки", body.contains("ProfileGifts.received(outbox, meId)"))
        assertTrue("дотик відкриває список", body.contains("GiftsSheet("))
    }

    @Test fun shotsDayAndNight() {
        listOf("night" to AmberNight, "day" to DayClear).flatMap { (n, s) -> listOf(Triple("you-gifts-390-$n", s, false), Triple("you-gifts-list-390-$n", s, true)) }.forEach { (name, spec, list) ->
            val a = open(ProfileGifts.received(log, me), spec, list)
            val decor = a.window.decorView
            val full = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            android.view.PixelCopy.request(a.window, full, {}, android.os.Handler(Looper.getMainLooper()))
            shadowOf(Looper.getMainLooper()).idle()
            val out = File("build/profile-gifts-look").apply { mkdirs() }
            File(out, "$name.png").outputStream().use { full.compress(Bitmap.CompressFormat.PNG, 100, it) }
            close()
            controller = null
            recomposer = null
        }
    }
}
