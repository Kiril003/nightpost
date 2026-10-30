package local.phantom.companion.ui.home

import android.app.Application
import android.graphics.Bitmap
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
import androidx.compose.ui.text.TextLayoutResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomText
import local.phantom.companion.core.design.theme.PhantomTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** «Я» як сторінка: без банера — шапка з двома кнопками; з банером — QR і шестерня на ньому, аватар навпіл на краю. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
class ProfileYouHeaderTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val actions = mutableListOf<ProfileAction>()
    private var edits = 0
    private var moments = 0
    private var bannerOpens = 0

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun bitmap() = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)

    private fun shots(n: Int) = List(n) { i ->
        BannerShot(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), i.toByte()), Bitmap.createBitmap(390, 210, Bitmap.Config.ARGB_8888))
    }

    private fun open(banner: Int = 0, video: Boolean = false): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) { face(banner, video) }
        }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        return c.get()
    }

    @Composable
    private fun face(banner: Int, video: Boolean) = ProfileFaceBody(
        me = Me(
            name = "Ira Koval",
            photo = bitmap(),
            banner = shots(if (video) 1 else banner),
            video = if (video) File("/nonexistent/banner-video") else null,
            videoMs = if (video) 6_000L else 0L,
            statusText = "Studio until 18:00",
        ),
        handle = "ira",
        activeProfile = "Studio",
        circles = listOf(ProfileFace.Circle("close", local.phantom.companion.R.string.circle_close, 3)),
        highlights = emptyList(),
        failed = false,
        naming = false,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = { actions += it },
        openTab = {}, composeMoment = { moments++ },
        onEdit = { edits++ },
        onBanner = { bannerOpens++ },
    )

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) = roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.words() = texts() + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun tag(a: ComponentActivity, tag: String) =
        screen(a).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun button(a: ComponentActivity, label: String) =
        screen(a).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { label in it.words() } }
            .minByOrNull { all(it).size }

    private fun dp(a: ComponentActivity, px: Number) = px.toFloat() / a.resources.displayMetrics.density

    private fun near(expected: Float, actual: Float) = kotlin.math.abs(expected - actual) <= 1f

    @Test fun `my code and settings are 48dp buttons in a 56dp top bar`() {
        val a = open()
        val bar = tag(a, "you-topbar")
        assertNotNull("шапки «Я» немає", bar)
        assertTrue("шапка 56dp: ${dp(a, bar!!.size.height)}", near(56f, dp(a, bar.size.height)))
        assertTrue("шапка від верху екрана", dp(a, bar.boundsInRoot.top) <= 1f)
        for (label in listOf("My code", "Settings")) {
            val b = button(a, label)
            assertNotNull("кнопки «$label» немає або вона не тисне", b)
            assertTrue("«$label» 48dp: ${dp(a, b!!.size.width)}x${dp(a, b.size.height)}", near(48f, dp(a, b.size.width)) && near(48f, dp(a, b.size.height)))
            assertTrue("«$label» у шапці", b.boundsInRoot.bottom <= bar.boundsInRoot.bottom + 1f)
        }
    }

    @Test fun `the title is You in title1 and the buttons carry no text`() {
        val a = open()
        val title = screen(a).firstOrNull { "You" in it.texts() }
        assertNotNull("заголовка «You» немає", title)
        val layouts = mutableListOf<TextLayoutResult>()
        title!!.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertEquals(PhantomText.title1.fontSize, layouts.single().layoutInput.style.fontSize)
        val bar = tag(a, "you-topbar")!!
        assertTrue("заголовок у шапці", title.boundsInRoot.bottom <= bar.boundsInRoot.bottom + 1f)
        val labelled = screen(a).flatMap { it.texts() }.filter { it in setOf("My code", "Settings", "Look") }
        assertTrue("плиток із підписами немає: $labelled", labelled.isEmpty())
    }

    @Test fun `no Look and no tile row`() {
        val a = open()
        val words = screen(a).flatMap { it.words() }
        assertFalse("«Look» повернувся: $words", words.contains("Look"))
        assertTrue(ProfileAction.entries.map { it.name } == listOf("CODE", "SETTINGS"))
    }

    @Test fun `the buttons fire their actions`() {
        val a = open()
        button(a, "My code")!!.config[SemanticsActions.OnClick].action!!.invoke()
        button(a, "Settings")!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf(ProfileAction.CODE, ProfileAction.SETTINGS), actions)
    }

    @Test fun `without a banner the avatar is 88dp in a 2dp cut-out under the bar and Edit is the one primary`() {
        val a = open()
        val bar = tag(a, "you-topbar")!!
        assertTrue("банера немає", tag(a, "you-banner") == null)
        val cut = tag(a, "you-avatar")
        assertNotNull("аватара немає", cut)
        assertTrue("зріз 2dp: аватар 88 у 92, а не ${dp(a, cut!!.size.width)}", near(92f, dp(a, cut.size.width)))
        val photo = screen(a).first { a.getString(local.phantom.companion.R.string.profile_photo) in it.words() }
        assertTrue("саме фото 88dp: ${dp(a, photo.size.width)}", near(88f, dp(a, photo.size.width)))
        assertTrue("аватар під шапкою", cut.boundsInRoot.top >= bar.boundsInRoot.bottom - 1f)
        assertTrue("«Add banner» більше не головна дія", tag(a, "me-banner-add") == null)
        val edit = tag(a, "me-edit")
        assertNotNull("«Edit» немає", edit)
        assertTrue("«Edit» 48dp", near(48f, dp(a, edit!!.size.height)))
        assertTrue("на кнопці «Edit»", all(edit).any { "Edit" in it.texts() })
        edit.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, edits)
        assertTrue("«Moment» не дублює плитку моментів", tag(a, "me-moment") == null)
    }

    @Test fun `with a banner it is 210dp at the top, QR and gear sit on it, and the avatar sits half on its edge`() {
        val a = open(banner = 3)
        assertTrue("шапки над банером немає", tag(a, "you-topbar") == null)
        val banner = tag(a, "you-banner")
        assertNotNull("банера немає", banner)
        assertTrue("банер 210dp: ${dp(a, banner!!.size.height)}", near(210f, dp(a, banner.size.height)))
        assertTrue("банер від верху", dp(a, banner.boundsInRoot.top) <= 1f)
        for (label in listOf("My code", "Settings")) {
            val b = button(a, label)
            assertNotNull("кнопки «$label» немає", b)
            assertTrue("«$label» 48dp", near(48f, dp(a, b!!.size.width)) && near(48f, dp(a, b.size.height)))
            assertTrue("«$label» на банері", b.boundsInRoot.bottom <= banner.boundsInRoot.bottom && b.boundsInRoot.top >= banner.boundsInRoot.top)
        }
        button(a, "My code")!!.config[SemanticsActions.OnClick].action!!.invoke()
        button(a, "Settings")!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf(ProfileAction.CODE, ProfileAction.SETTINGS), actions)
        val cut = tag(a, "you-avatar")!!
        val middle = (cut.boundsInRoot.top + cut.boundsInRoot.bottom) / 2
        assertTrue("аватар навпіл на краю банера", kotlin.math.abs(middle - banner.boundsInRoot.bottom) <= 2f * a.resources.displayMetrics.density)
        val dots = tag(a, "you-banner-dots")
        assertNotNull("крапок немає", dots)
        assertTrue("крапки внизу справа", dots!!.boundsInRoot.right > banner.boundsInRoot.center.x && dots.boundsInRoot.bottom <= banner.boundsInRoot.bottom)
        val label = tag(a, "me-profile-label")!!
        assertTrue("мітка профілю праворуч від аватара", label.boundsInRoot.left > cut.boundsInRoot.right)
        assertTrue("@нік · статус одним рядком", screen(a).any { "@ira · Studio until 18:00" in it.texts() })
        tag(a, "me-edit")!!.config[SemanticsActions.OnClick].action!!.invoke()
        tag(a, "you-moment-add")!!.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, edits)
        assertEquals(1, moments)
        assertTrue("«Add banner» з банером не показується", tag(a, "me-banner-add") == null)
    }

    @Test fun `one photo has no dots and a video banner says others see the first frame`() {
        val one = open(banner = 1)
        assertTrue("одне фото без крапок", tag(one, "you-banner-dots") == null)
        close()
        val a = open(video = true)
        assertNotNull("відео без підпису першого кадру", tag(a, "you-banner-first-frame"))
        assertTrue(screen(a).any { "others see the first frame" in it.texts() })
        assertTrue(screen(a).any { "0:06" in it.texts() })
    }

    @Test fun `backup, devices and scan cells are gone and stay reachable behind the gear and the code`() {
        val a = open(banner = 2)
        listOf("me-cell-backup", "me-cell-devices", "me-scan", "you-cover").forEach { assertTrue("$it лишився", tag(a, it) == null) }
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val home = File(root, "app/src/main/java/local/phantom/companion/ui/home")
        val settings = File(home, "ProfileSettings.kt").readText()
        assertTrue("пристрої не досяжні з шестерні", "go(ProfileSection.DEVICES.route)" in settings)
        assertTrue("копія не досяжна з шестерні", "go(ProfileSection.DATA.route)" in settings)
        val tab = File(home, "ProfileTab.kt").readText()
        assertTrue("сканування не досяжне з коду", "secondary = stringResource(R.string.scan_action) to onScan" in tab)
        assertTrue("сканування не веде в Scan", "go(Routes.Scan.path)" in tab)
    }

    @Test fun `exactly one amber fill on the page`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        val tab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        val body = tab.substringAfter("internal fun ProfileFaceBody(").substringBefore("\n/** Рядок під банером")
        assertEquals("c.accent у тілі сторінки не вжито", 0, Regex("""\bc\.accent\b""").findAll(body).count())
        assertEquals("на сторінці одна первинна дія — з банером і без", 1, Regex("primary = true").findAll(body).count())
        val action = tab.substringAfter("private fun PageAction(").substringBefore("\n}\n")
        assertEquals("заливка акцентом лише на первинній дії", 1, Regex("""c\.accent\b""").findAll(action).count())
    }

    @Test fun `the My code sentence and the Look label are gone from every language`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        for (dir in listOf("values", "values-uk", "values-cs")) {
            val xml = File(root, "app/src/main/res/$dir/strings_profile_face.xml").readText()
            assertFalse("$dir: речення про код лишилось", "profile_code_wait" in xml)
            assertFalse("$dir: підпис «Look» лишився", "profile_action_look" in xml)
        }
        val tab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        assertFalse("діалог коду ще читає речення", "profile_code_wait" in tab)
        assertFalse("профіль ще кличе «Look»", "profile_action_look" in tab)
        assertFalse("кільце 4dp повернулось", "BorderStroke(4.dp" in tab)
        assertFalse("плитки повернулись", "ActionTile(" in tab)
        assertFalse("обкладинка повернулась", "coverPicker" in tab || "CoverHeight" in tab)
    }
}
