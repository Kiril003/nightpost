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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.data.ProfilesMirror
import org.junit.After
import org.junit.Assert.assertEquals
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

/** N1-2: Моменти з «Add», що відкриває створення, і кола рядками, що відкривають Today на своєму колі. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h1200dp-xhdpi")
class ProfileYouBodyTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val tabs = mutableListOf<String>()
    private val moments = mutableListOf<String>()
    private var composed = 0
    private var managed = 0
    private var profiles: List<ProfilesMirror.Profile> = emptyList()

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun open(
        circles: List<ProfileFace.Circle>,
        highlights: List<ProfileFace.Highlight> = emptyList(),
        name: String = "Ira Koval",
        naming: Boolean = false,
        nameSaveFailed: Boolean = false,
    ): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) { PhantomTheme(spec = AmberNight) { face(circles, highlights, name, naming, nameSaveFailed) } }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            frameNanos += 50_000_000L
            clock.sendFrame(frameNanos)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        }
        return c.get()
    }

    @Composable
    private fun face(
        circles: List<ProfileFace.Circle>,
        highlights: List<ProfileFace.Highlight> = emptyList(),
        name: String = "Ira Koval",
        naming: Boolean = false,
        nameSaveFailed: Boolean = false,
    ) = ProfileFaceBody(
        me = Me(name = name, photo = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)),
        handle = "ira",
        activeProfile = "Studio",
        circles = circles,
        highlights = highlights,
        failed = false,
        naming = naming,
        nameSaveFailed = nameSaveFailed,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = {},
        openTab = { tabs += it },
        openMoment = { moments += it },
        composeMoment = { composed++ },
        profiles = profiles,
        activeId = profiles.firstOrNull()?.id,
        onProfiles = { managed++ },
    )

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) = roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun tagged(a: ComponentActivity, tag: String) = screen(a).filter { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun button(a: ComponentActivity, label: String) =
        screen(a).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { label in it.texts() } }
            .minByOrNull { all(it).size }

    private fun dp(a: ComponentActivity, px: Number) = px.toFloat() / a.resources.displayMetrics.density

    private fun near(expected: Float, actual: Float) = kotlin.math.abs(expected - actual) <= 1f

    private val two = listOf(
        ProfileFace.Circle("close", R.string.circle_close, 3),
        ProfileFace.Circle("work", R.string.circle_work, 0),
    )

    @Test fun `the avatar only changes the photo, no hidden long-press`() {
        val a = open(two)
        val photo = screen(a).single { n -> n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().contains("Change photo") }
        assertNotNull("тап по аватару зник", photo.config.getOrNull(SemanticsActions.OnClick))
        assertTrue("на «Я» не лишилось прихованого довгого натискання", screen(a).none { it.config.getOrNull(SemanticsActions.OnLongClick) != null })
    }

    @Test fun `the active profile is a plain label, not a second switcher`() {
        val a = open(two)
        val label = tagged(a, "me-profile-label").singleOrNull()
        assertNotNull("мітки профілю не видно", label)
        assertTrue("на мітці немає імені профілю", all(label!!).flatMap { it.texts() }.contains("Studio"))
        assertTrue("мітка не тисне", all(label).none { it.config.getOrNull(SemanticsActions.OnClick) != null })
    }

    @Test fun `a circle with people is a tile that opens Today on its own tab`() {
        val a = open(two)
        val tile = button(a, "Close")
        assertNotNull("плитки «Close» немає або вона не тисне", tile)
        assertEquals("you-circle-tile", tile!!.config.getOrNull(SemanticsProperties.TestTag))
        assertTrue("кількість на плитці", "3" in all(tile).flatMap { it.texts() })
        tile.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(listOf("c:close"), tabs)
    }

    @Test fun `a circle with nobody in it is shown but has no click`() {
        val a = open(two)
        assertTrue("плитку «Work» намальовано", screen(a).any { "Work" in it.texts() })
        assertNull("порожнє коло не тисне", button(a, "Work"))
    }

    @Test fun `People are tiles in a row, Moments sit above them`() {
        val a = open(two)
        val tiles = tagged(a, "you-circle-tile")
        assertEquals(2, tiles.size)
        assertTrue("плитки в один ряд", near(dp(a, tiles[0].boundsInRoot.top), dp(a, tiles[1].boundsInRoot.top)))
        assertTrue("плитка не менша за 48dp", tiles.all { dp(a, it.size.height) >= 48f })
        val circles = tagged(a, "you-circles").single()
        val moments = tagged(a, "you-moments").single()
        assertTrue("моменти над людьми", moments.boundsInRoot.bottom + 8f <= circles.boundsInRoot.top)
        assertTrue("заголовок «People»", all(circles).any { "People" in it.texts() })
        assertTrue("заголовок «Moments»", all(moments).any { "Moments" in it.texts() })
    }

    @Test fun `the add tile composes a moment`() {
        val a = open(two)
        val add = tagged(a, "you-moment-add").singleOrNull()
        assertNotNull("плитки додавання немає", add)
        assertTrue("плитка 64dp: ${dp(a, add!!.size.height)}", near(64f, dp(a, add.size.height)))
        add.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals(1, composed)
        assertTrue("додавання не відкриває вкладку кола", tabs.isEmpty())
    }

    @Test fun `no moments today stays an honest line and the add tile is still there`() {
        val a = open(emptyList())
        assertTrue(screen(a).any { "No moments today" in it.texts() })
        assertNotNull(tagged(a, "you-moment-add").singleOrNull())
        assertTrue("порожні кола — чесний рядок", screen(a).any { "No one here yet" in it.texts() })
        assertTrue("порожніх плиток кіл немає", tagged(a, "you-circle-tile").isEmpty())
    }

    @Test fun `highlight tiles are clickable and route to the moment door`() {
        val bmpBytes = java.io.ByteArrayOutputStream().also {
            Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val a = open(emptyList(), highlights = listOf(ProfileFace.Highlight(id = "m-1", thumb = bmpBytes)))
        val tile = tagged(a, "you-moment-tile").singleOrNull()
        assertNotNull("highlight tile must be rendered", tile)
        assertTrue("highlight tile must be >= 48dp: ${dp(a, tile!!.size.width)}x${dp(a, tile.size.height)}", dp(a, tile.size.width) >= 48f && dp(a, tile.size.height) >= 48f)
        val click = tile.config.getOrNull(SemanticsActions.OnClick)?.action
        assertNotNull("highlight tile must be clickable", click)
        click!!.invoke()
        assertEquals(listOf("m-1"), moments)
        assertTrue(tabs.isEmpty())
    }

    @Test fun `blank name shows Add your name action and failed save shows Not saved`() {
        val a = open(emptyList(), name = "")
        val addName = button(a, "Add your name")
        assertNotNull("blank name must render clickable Add your name", addName)
        val b = open(emptyList(), name = "Ira", naming = true, nameSaveFailed = true)
        assertTrue("failed name save must render Not saved", screen(b).any { "Not saved" in it.texts() })
    }

    @Test fun `Add is in every language and the profile wires both doors`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "app/src/main/java").isDirectory }
        for ((dir, word) in listOf("values" to "Add", "values-uk" to null, "values-cs" to null)) {
            val xml = File(root, "app/src/main/res/$dir/strings_profile_face.xml").readText()
            val v = Regex("""<string name="profile_moment_add">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
            assertTrue("$dir: profile_moment_add немає", !v.isNullOrBlank())
            if (word != null) assertEquals(word, v)
        }
        val tab = File(root, "app/src/main/java/local/phantom/companion/ui/home/ProfileTab.kt").readText()
        assertTrue("коло не відкриває вкладку веба", "WebNav.request(WebNav.Door.OpenTab(" in tab)
        assertTrue("«Add» не відкриває створення моменту", "WebNav.request(WebNav.Door.ComposeMoment" in tab)
        assertTrue("після дверей шелл не переходить на Розмови", tab.split("homeTabRequest.value = HomeTab.CHATS").size - 1 >= 2)
        assertTrue("чипів кіл більше немає", "CircleChips(" !in tab && "FlowRow" !in tab)
        val host = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue("двері ніхто не доставляє у WebView", "WebNav.pending.collectLatest" in host && "WebNav.deliver(" in host)
    }

    @Test fun `profiles card lists every profile and opens the manager, not a second switch`() {
        profiles = listOf(
            ProfilesMirror.Profile(id = "p", name = "Personal", accent = "sea"),
            ProfilesMirror.Profile(id = "w", name = "Work", accent = "rose"),
        )
        val a = open(two)
        val card = tagged(a, "you-profiles").single()
        val texts = all(card).flatMap { it.texts() }
        assertTrue("у картці немає імен профілів: $texts", texts.any { "Personal" in it } && texts.any { "Work" in it })
        assertNotNull("картка не тапається", card.config.getOrNull(SemanticsActions.OnClick))
        card.config[SemanticsActions.OnClick].action!!.invoke()
        assertEquals("тап не відчинив керування профілями", 1, managed)
        assertTrue("картка профілів стоїть під колами", tagged(a, "you-circles").single().boundsInRoot.top < card.boundsInRoot.top)
    }

    @Test fun `profiles card without a mirror offers one next step`() {
        val a = open(two)
        val card = tagged(a, "you-profiles").single()
        assertTrue("порожня картка без наступного кроку", all(card).flatMap { it.texts() }.contains("New"))
    }
}
