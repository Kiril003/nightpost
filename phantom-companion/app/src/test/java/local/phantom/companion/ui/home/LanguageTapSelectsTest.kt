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
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.core.data.locale.AppLanguage
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.feature.onboarding.ui.LanguageStep
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
class LanguageTapSelectsTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val picks = mutableListOf<AppLanguage>()

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun pump() = repeat(40) {
        Snapshot.sendApplyNotifications()
        frameNanos += 50_000_000L
        clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))
    }

    private fun open(): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) { PhantomTheme { LanguageStep(onPick = { picks += it }) } }
        pump()
        return c.get()
    }

    private fun roots(v: View): List<ViewRootForTest> = when (v) {
        is ViewRootForTest -> listOf(v)
        is ViewGroup -> (0 until v.childCount).flatMap { roots(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun all(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::all)

    private fun screen(a: ComponentActivity) = roots(a.window.decorView).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun row(a: ComponentActivity, name: String) =
        screen(a).first { n -> SemanticsProperties.Selected in n.config && all(n).any { name in it.texts() } }

    private fun selected(a: ComponentActivity, name: String) = row(a, name).config[SemanticsProperties.Selected]

    @Test fun `a tap on a language selects it and only Continue moves on`() {
        val a = open()
        row(a, AppLanguage.CS.nativeName).config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertTrue("дотик до мови вже повів далі: $picks", picks.isEmpty())
        assertTrue("Čeština не позначена після дотику", selected(a, AppLanguage.CS.nativeName))
        assertTrue("English лишилась позначеною", !selected(a, AppLanguage.EN.nativeName))

        row(a, AppLanguage.UK.nativeName).config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertTrue("друга зміна мови теж повела далі: $picks", picks.isEmpty())
        assertTrue("Українська не позначена", selected(a, AppLanguage.UK.nativeName))

        val next = screen(a).first { n ->
            SemanticsActions.OnClick in n.config && SemanticsProperties.Selected !in n.config &&
                all(n).any { t -> t.texts().any { it in setOf("Далі", "Continue", "Pokračovat") } }
        }
        next.config[SemanticsActions.OnClick].action!!.invoke()
        next.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
        assertEquals(listOf(AppLanguage.UK), picks)
    }
}
