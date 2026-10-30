package local.phantom.companion.feature.godmode.service

import java.io.File
import local.phantom.companion.feature.godmode.engine.ActionTarget
import local.phantom.companion.feature.godmode.engine.AppAction
import local.phantom.companion.feature.godmode.engine.GodModeCommand
import local.phantom.companion.feature.godmode.engine.ScrollAmount
import local.phantom.companion.feature.godmode.engine.ScrollDirection
import local.phantom.companion.feature.godmode.engine.SensitiveScreens
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Помічник не натискає «Підписати» чи «Надіслати» у власному вікні Nightpost. */
class OwnWindowTouchGuardTest {

    private val own = "local.phantom.companion.debug"
    private val touches = listOf(
        AppAction.Tap(500, 1800),
        AppAction.Swipe(1, 2, 3, 4, 200),
        AppAction.LongPress(500, 1800, 600),
        AppAction.Click(ActionTarget(text = "Підписати")),
        AppAction.Scroll(ScrollDirection.DOWN, ScrollAmount.PAGE),
        AppAction.Type("x"),
    )

    @Test
    fun `no touch lands in our own window or in a window nobody can name`() {
        touches.forEach { a ->
            assertFalse("$a in own window", SensitiveScreens.mayTouch(own, own, a))
            assertFalse("$a in unknown window", SensitiveScreens.mayTouch(null, own, a))
            assertFalse("$a in blank window", SensitiveScreens.mayTouch(" ", own, a))
        }
    }

    @Test
    fun `other apps and global keys still work`() {
        touches.forEach { a -> assertTrue("$a in another app", SensitiveScreens.mayTouch("org.telegram.messenger", own, a)) }
        listOf(AppAction.Back, AppAction.Home, AppAction.OpenApp("maps")).forEach { a ->
            assertTrue("$a", SensitiveScreens.mayTouch(own, own, a))
        }
    }

    @Test
    fun `ClickText and TypeText commands are refused on own package and banking screens`() {
        val click = GodModeCommand.ClickText("Підписати")
        val type = GodModeCommand.TypeText("Поле", "secret")
        val global = GodModeCommand.GlobalAction(1)

        assertFalse(SensitiveScreens.mayTouch(own, own, click))
        assertFalse(SensitiveScreens.mayTouch(own, own, type))
        assertFalse(SensitiveScreens.mayTouch(null, own, click))
        assertFalse(SensitiveScreens.mayTouch("com.ftband.mono", own, click))
        assertFalse(SensitiveScreens.mayTouch("com.ftband.mono", own, type))
        assertFalse(SensitiveScreens.mayTouch("com.ftband.mono", own, AppAction.Click(ActionTarget(text = "Pay"))))

        assertTrue(SensitiveScreens.mayTouch("org.telegram.messenger", own, click))
        assertTrue(SensitiveScreens.mayTouch("org.telegram.messenger", own, type))
        assertTrue(SensitiveScreens.mayTouch(own, own, global))
    }

    @Test
    fun `the live service asks before every dispatched step`() {
        val src = File("src/main/java/local/phantom/companion/feature/godmode/service/ScreenAwarenessService.kt").readText()
        val body = src.substringAfter("internal fun executeAppAction(").substringBefore("return when (action)")
        assertTrue("guard is not ahead of the dispatch", body.contains("SensitiveScreens.mayTouch(root?.packageName"))
        val cmdBody = src.substringAfter("private fun executeCommand(").substringBefore("when (command)")
        assertTrue("guard is not ahead of executeCommand", cmdBody.contains("SensitiveScreens.mayTouch(root?.packageName"))
        val gestureBody = src.substringAfter("internal suspend fun performGestureAwait(").substringBefore("val (path, durationMs)")
        assertTrue("guard is not ahead of performGestureAwait", gestureBody.contains("SensitiveScreens.mayTouch(root?.packageName"))
    }
}
