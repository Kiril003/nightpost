package local.phantom.companion.feature.godmode.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnAppIgnoredTest {

    private val ours = listOf(
        "local.phantom.companion",
        "local.phantom.companion.debug",
        "local.phantom.companion.seeker",
        "dev.mobilabs.app",
        "dev.mobilabs.app.debug",
    )

    private val auth = AppActionAuthorizer(isSensitiveApp = { false }, ownPackage = "local.phantom.companion.debug")

    @Test
    fun `nothing but leaving runs while our own window is in front`() {
        val refused = listOf(
            AppAction.Back, AppAction.Recents, AppAction.Notifications, AppAction.Screenshot,
            AppAction.Click(ActionTarget(text = "Надіслати")), AppAction.Click(ActionTarget(text = "Підписати")),
            AppAction.Type("1 SOL"), AppAction.Tap(540, 1800), AppAction.Scroll(ScrollDirection.DOWN),
        )
        for (pkg in ours) for (a in refused) {
            val r = auth.authorize(a, pkg)
            assertTrue("$a in «$pkg» went through", r is AuthorizationResult.Denied)
            assertEquals(AppActionAuthorizer.REASON_PROTECTED_WINDOW, (r as AuthorizationResult.Denied).reasonKey)
        }
        for (pkg in ours) for (a in listOf(AppAction.Home, AppAction.OpenApp("telegram"), AppAction.SearchPlace("кава"))) {
            assertEquals("$a in «$pkg»", AuthorizationResult.Allowed, auth.authorize(a, pkg))
        }
        assertEquals(AuthorizationResult.Allowed, auth.authorize(AppAction.Back, "org.telegram.messenger"))
    }

    @Test
    fun `only our family counts as ours`() {
        for (pkg in listOf("org.telegram.messenger", "local.phantom.companionx", "dev.mobilabs.apps")) {
            assertEquals(pkg, AuthorizationResult.Allowed, auth.authorize(AppAction.Back, pkg))
        }
        val renamed = AppActionAuthorizer(isSensitiveApp = { false }, ownPackage = "com.example.renamed")
        assertTrue(renamed.authorize(AppAction.Back, "com.example.renamed.debug") is AuthorizationResult.Denied)
    }

    @Test
    fun `the live service neither reads nor acts on our own windows`() {
        val src = File("src/main/java/local/phantom/companion/feature/godmode/service/ScreenAwarenessService.kt").readText()
        fun body(start: String, end: String) = src.substringAfter(start, "").substringBefore(end)
        val event = body("override fun onAccessibilityEvent(", "private fun captureSelection(")
        assertTrue("events from our window still feed capture", event.indexOf("isOwn(") in 0 until event.indexOf("scheduleCapture("))
        assertTrue("capture reads whatever root is in front", "isOwn(root.packageName" in body("private fun captureNow()", "private fun walk("))
        assertTrue("the model's eyes read our window", "isOwn(" in body("internal fun snapshotInteractiveTree()", "private fun collectInteractive("))
        assertTrue("a screenshot is taken over our window", "isOwn(" in body("internal suspend fun captureScreen()", "private fun launchByQuery("))
        assertTrue("open_app can launch us", "isOwn(" in body("private fun launchByQuery(", "private fun toastDebug("))
        val exec = body("internal fun executeAppAction(", "internal fun snapshotInteractiveTree()")
        assertTrue("a found node is not checked for our package", "isOwn(node.packageName" in exec)
        assertTrue("legacy commands act in our window", "isOwn(" in body("private fun executeCommand(", "override fun onInterrupt()"))
    }
}
