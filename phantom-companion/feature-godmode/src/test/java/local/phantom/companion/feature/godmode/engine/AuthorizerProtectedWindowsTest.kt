package local.phantom.companion.feature.godmode.engine

import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthorizerProtectedWindowsTest {

    private val own = "local.phantom.companion.debug"
    private val auth = AppActionAuthorizer(isSensitiveApp = { false }, ownPackage = own)

    private val touches = listOf(
        AppAction.Click(ActionTarget(text = "Allow")),
        AppAction.Tap(540, 1800),
        AppAction.Swipe(540, 0, 540, 1200),
        AppAction.LongPress(540, 1800),
        AppAction.Type("1234"),
        AppAction.Scroll(ScrollDirection.DOWN),
    )

    private val protectedWindows = listOf(
        "local.phantom.companion",
        "local.phantom.companion.debug",
        "local.phantom.companion.store",
        "local.phantom.companion.store.debug",
        "dev.mobilabs.app",
        "dev.mobilabs.app.debug",
        "local.phantom.companion.seeker",
        "com.android.systemui",
        "com.google.android.permissioncontroller",
        "com.android.permissioncontroller",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.android.settings",
        "com.android.settings.intelligence",
        " com.android.settings ",
        "COM.ANDROID.SYSTEMUI",
    )

    @Test
    fun `every touch is refused in our app, system UI, permission dialogs, the installer and Settings`() {
        for (pkg in protectedWindows) for (a in touches) {
            val r = auth.authorize(a, pkg)
            assertTrue("$a in «$pkg» went through", r is AuthorizationResult.Denied)
            assertEquals("$a in «$pkg»", AppActionAuthorizer.REASON_PROTECTED_WINDOW, (r as AuthorizationResult.Denied).reasonKey)
            assertTrue(r.displayText.isNotBlank())
        }
    }

    @Test
    fun `a foreground nobody can name is refused for touches`() {
        for (pkg in listOf("", "   ")) for (a in touches) {
            assertTrue("$a in an unknown window went through", auth.authorize(a, pkg) is AuthorizationResult.Denied)
        }
    }

    @Test
    fun `the running package is protected even under another name`() {
        val renamed = AppActionAuthorizer(isSensitiveApp = { false }, ownPackage = "com.example.renamed")
        assertTrue(renamed.authorize(AppAction.Tap(1, 1), "com.example.renamed") is AuthorizationResult.Denied)
    }

    @Test
    fun `escape keys work in a protected window and touches work in other apps`() {
        for (a in listOf(AppAction.Back, AppAction.Home, AppAction.Recents, AppAction.OpenApp("telegram"))) {
            assertEquals("$a", AuthorizationResult.Allowed, auth.authorize(a, "com.android.settings"))
        }
        for (pkg in listOf("org.telegram.messenger", "com.android.chrome", "com.android.settingsx", "local.phantom.companionx")) {
            for (a in touches) assertEquals("$a in «$pkg»", AuthorizationResult.Allowed, auth.authorize(a, pkg))
        }
    }

    @Test
    fun `a sequence stops before the tap that would land in Settings`() = runTest {
        var foreground = "org.telegram.messenger"
        val executed = mutableListOf<AppAction>()
        val dispatcher = AccessibilityActionDispatcher(
            authorizer = auth,
            controller = object : AccessibilityActionController {
                override fun execute(action: AppAction): Boolean {
                    executed += action
                    if (action is AppAction.OpenApp) foreground = "com.android.settings"
                    return true
                }
            },
            currentPackage = { foreground },
            emitProgress = {},
            minStepIntervalMs = 0L,
        )
        val out = dispatcher.dispatchDetailed(listOf(AppAction.OpenApp("settings"), AppAction.Tap(540, 900)))
        assertEquals(listOf<AppAction>(AppAction.OpenApp("settings")), executed)
        assertEquals(AppActionAuthorizer.REASON_PROTECTED_WINDOW, out.stop?.reasonKey)
    }

    @Test
    fun `the live service guard refuses the same windows`() {
        for (pkg in protectedWindows) for (a in touches) {
            assertFalse("$a in «$pkg» passed the service guard", SensitiveScreens.mayTouch(pkg, own, a))
        }
        for (a in touches) assertTrue("$a in another app", SensitiveScreens.mayTouch("org.telegram.messenger", own, a))
    }

    @Test
    fun `the service judges the live window and the window under a gesture`() {
        val src = File("src/main/java/local/phantom/companion/feature/godmode/service/ScreenAwarenessService.kt").readText()
        val dispatcher = src.substringAfter("private fun buildDispatcher(").substringBefore("emitProgress =")
        assertTrue("the authorizer is fed a cached screen, not the live window", dispatcher.contains("svc.activeWindowPackage()"))
        val exec = src.substringAfter("internal fun executeAppAction(").substringBefore("return when (action)")
        assertTrue("executeAppAction does not judge the window under the gesture", exec.contains("SensitiveScreens.mayTouch(packageUnder(action"))
        val gesture = src.substringAfter("internal suspend fun performGestureAwait(").substringBefore("val (path, durationMs)")
        assertTrue("performGestureAwait does not judge the window under the gesture", gesture.contains("SensitiveScreens.mayTouch(packageUnder(action"))
        for ((name, body) in listOf("executeAppAction" to exec, "performGestureAwait" to gesture)) {
            assertTrue("$name runs PC orders and the pilot past the authorizer", body.contains("AccessibilityServiceActionRegistry.authorizer?.authorize(action"))
            assertTrue("$name acts when no authorizer is armed yet", body.contains("!= AuthorizationResult.Allowed) return false"))
        }
    }
}
