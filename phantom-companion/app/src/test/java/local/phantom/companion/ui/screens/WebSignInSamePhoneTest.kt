package local.phantom.companion.ui.screens

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.net.auth.WebAuthQr
import local.phantom.companion.core.net.auth.WebAuthQrClient
import local.phantom.companion.invitePayload
import local.phantom.companion.isTappedInvite
import local.phantom.companion.webSignInPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebSignInSamePhoneTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    @After fun close() {
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

    private fun pump() = repeat(40) {
        Snapshot.sendApplyNotifications()
        frameNanos += 50_000_000L
        clock.sendFrame(frameNanos)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
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

    /** Усі вікна процесу: шторка підтвердження живе у своєму вікні, не в decorView. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(a: ComponentActivity): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(instance) as List<View>).toList()
    }.getOrDefault(emptyList()).ifEmpty { listOf(a.window.decorView) }

    private fun screen(a: ComponentActivity) =
        windows(a).flatMap(::roots).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }

    private fun words(a: ComponentActivity) = screen(a).flatMap { it.words() }

    private fun tap(a: ComponentActivity, text: String) {
        val hit = screen(a).filter { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && all(n).any { m -> text in m.words() } }
            .minByOrNull { all(it).size } ?: error("немає кнопки «$text»: ${words(a)}")
        hit.config[SemanticsActions.OnClick].action!!.invoke()
        pump()
    }

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `webSignInPayload accepts https phantom-os dev s with p and rejects other forms`() {
        val qrPayload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val validIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://phantom-os.dev/s?p=NP-AUTH-v1%7Cchal_9f8e7d%7Cnonce_1a2b3c%7C1900000000"),
        )
        assertEquals(qrPayload, webSignInPayload(validIntent))

        val wrongHost = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/s?p=$qrPayload"))
        assertNull(webSignInPayload(wrongHost))

        val subdomainHost = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.phantom-os.dev/s?p=$qrPayload"))
        assertNull(webSignInPayload(subdomainHost))

        val httpScheme = Intent(Intent.ACTION_VIEW, Uri.parse("http://phantom-os.dev/s?p=$qrPayload"))
        assertNull(webSignInPayload(httpScheme))

        val subPath = Intent(Intent.ACTION_VIEW, Uri.parse("https://phantom-os.dev/s/x?p=$qrPayload"))
        assertNull(webSignInPayload(subPath))

        val invitePath = Intent(Intent.ACTION_VIEW, Uri.parse("https://phantom-os.dev/i?p=$qrPayload"))
        assertNull(webSignInPayload(invitePath))

        val missingP = Intent(Intent.ACTION_VIEW, Uri.parse("https://phantom-os.dev/s"))
        assertNull(webSignInPayload(missingP))

        val blankP = Intent(Intent.ACTION_VIEW, Uri.parse("https://phantom-os.dev/s?p=%20"))
        assertNull(webSignInPayload(blankP))

        val sendIntent = Intent(
            Intent.ACTION_SEND,
            Uri.parse("https://phantom-os.dev/s?p=$qrPayload"),
        ).putExtra(Intent.EXTRA_TEXT, "https://phantom-os.dev/s?p=$qrPayload")
        assertNull(webSignInPayload(sendIntent))

        assertNull(webSignInPayload(null))
    }

    @Test
    fun `invite routing ignores s links`() {
        val signInLink = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("https://phantom-os.dev/s?p=NP-AUTH-v1%7Cchal_9f8e7d%7Cnonce_1a2b3c%7C1900000000"),
        )
        assertFalse(isTappedInvite(signInLink))
        assertNull(invitePayload(signInLink))
    }

    @Test
    fun `link-started flow with same_ip false never calls onApprove`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val mismatchInfo = WebAuthQr.PeekInfo(
            browser = "Firefox on Android",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = true,
            sameIp = false,
        )
        assertFalse(linkApproveAllowed(mismatchInfo))
        assertFalse(linkApproveAllowed(WebAuthQrClient.PeekOutcome.Found(mismatchInfo)))

        var approveCount = 0
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = {},
                    linkPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(mismatchInfo) },
                    onApprove = {
                        approveCount += 1
                        WebAuthQrClient.ApproveOutcome.Approved
                    },
                )
            }
        }
        val w = words(a)
        assertTrue("інша мережа названа: $w", context.getString(R.string.web_signin_other_network) in w)
        assertTrue("відмова названа: $w", context.getString(R.string.web_signin_refused) in w)
        assertFalse("кнопки підтвердження немає: $w", context.getString(R.string.web_signin_confirm) in w)
        assertEquals(0, approveCount)
    }

    @Test
    fun `link-started flow with same_ip true shows confirm sheet and approves once`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val sameIpInfo = WebAuthQr.PeekInfo(
            browser = "Chrome on Android",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = false,
            sameIp = true,
        )
        assertTrue(linkApproveAllowed(sameIpInfo))
        assertTrue(linkApproveAllowed(WebAuthQrClient.PeekOutcome.Found(sameIpInfo)))

        var approveCount = 0
        var backCount = 0
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = { backCount += 1 },
                    linkPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(sameIpInfo) },
                    onApprove = {
                        approveCount += 1
                        WebAuthQrClient.ApproveOutcome.Approved
                    },
                )
            }
        }
        assertTrue("браузер названо: ${words(a)}", words(a).any { "Chrome on Android" in it })
        tap(a, context.getString(R.string.web_signin_confirm))

        assertEquals(1, approveCount)
        val w = words(a)
        assertTrue("вхід підтверджено: $w", context.getString(R.string.web_signin_done) in w)
        tap(a, context.getString(R.string.web_signin_back_to_browser))
        assertEquals(1, backCount)
    }

    @Test
    fun `confirm sheet names an erase`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val eraseInfo = WebAuthQr.PeekInfo(
            browser = "Firefox on Linux",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = false,
            sameIp = true,
            purpose = "erase",
        )
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = {},
                    linkPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(eraseInfo) },
                    onApprove = { WebAuthQrClient.ApproveOutcome.Approved },
                )
            }
        }
        val w = words(a)
        assertTrue("рядок облікового запису: $w", context.getString(R.string.web_signin_account) in w)
        assertTrue("мета видалення названа: $w", context.getString(R.string.web_signin_purpose_erase) in w)
        assertTrue("кнопка підтвердження є: $w", context.getString(R.string.web_signin_confirm) in w)
    }

    @Test
    fun `confirm sheet names a sign-in`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val signInInfo = WebAuthQr.PeekInfo(
            browser = "Firefox on Linux",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = false,
            sameIp = true,
            purpose = "",
        )
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = {},
                    linkPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(signInInfo) },
                    onApprove = { WebAuthQrClient.ApproveOutcome.Approved },
                )
            }
        }
        val w = words(a)
        assertTrue("рядок облікового запису: $w", context.getString(R.string.web_signin_account) in w)
        assertTrue("мета входу названа: $w", context.getString(R.string.web_signin_purpose_signin) in w)
        assertFalse("мета видалення не показана: $w", context.getString(R.string.web_signin_purpose_erase) in w)
    }

    @Test
    fun `confirm sheet names a phone link to the owner's pages`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val linkInfo = WebAuthQr.PeekInfo(
            browser = "Firefox on Linux",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = false,
            sameIp = true,
            purpose = "link",
        )
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = {},
                    linkPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(linkInfo) },
                    onApprove = { WebAuthQrClient.ApproveOutcome.Approved },
                )
            }
        }
        val w = words(a)
        assertTrue("мета під'єднання названа: $w", context.getString(R.string.web_signin_purpose_link) in w)
        assertFalse("це не вхід: $w", context.getString(R.string.web_signin_confirm) in w)
        assertTrue("кнопка під'єднання є: $w", context.getString(R.string.web_signin_link_confirm) in w)
    }

    @Test
    fun `unknown purpose is refused`() {
        val payload = "NP-AUTH-v1|chal_9f8e7d|nonce_1a2b3c|1900000000"
        val unknownInfo = WebAuthQr.PeekInfo(
            browser = "Firefox on Linux",
            country = "UA",
            createdAt = "2026-04-17T12:00:00Z",
            ipMismatch = false,
            sameIp = true,
            purpose = "transfer",
        )
        assertFalse(linkApproveAllowed(unknownInfo))
        assertFalse(linkApproveAllowed(WebAuthQrClient.PeekOutcome.Found(unknownInfo)))

        var approveCount = 0
        val a = show {
            PhantomTheme {
                WebSignInScreen(
                    onBack = {},
                    scannedPayload = payload,
                    onPeek = { WebAuthQrClient.PeekOutcome.Found(unknownInfo) },
                    onApprove = {
                        approveCount += 1
                        WebAuthQrClient.ApproveOutcome.Approved
                    },
                )
            }
        }
        val w = words(a)
        assertTrue("відмова названа: $w", context.getString(R.string.web_signin_refused) in w)
        assertFalse("аркуша підтвердження немає: $w", context.getString(R.string.web_signin_account) in w)
        assertEquals(0, approveCount)
    }

    @Test
    fun `the screen does not read the profile name`() {
        val rel = "src/main/java/local/phantom/companion/ui/screens/WebSignInScreen.kt"
        val file = generateSequence(java.io.File(".").canonicalFile) { it.parentFile }
            .flatMap { dir ->
                sequenceOf(
                    dir.resolve(rel),
                    dir.resolve("app/$rel"),
                    dir.resolve("phantom-companion/app/$rel"),
                )
            }
            .firstOrNull { it.isFile }
            ?: error("WebSignInScreen.kt not found")
        assertFalse(
            "WebSignInScreen.kt must not reference displayName",
            "displayName" in file.readText(),
        )
    }
}
