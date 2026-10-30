package local.phantom.companion.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Looper
import android.view.Window
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
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.phantom.companion.R
import local.phantom.companion.core.design.motion.LocalMotionScale
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import local.phantom.companion.data.ProfilesMirror
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * Кадри збірки поруч із рендерами profile-r1 (390 dp, укр., день і ніч):
 * лягають у build/profile-r1-shots/ під тими ж іменами, що й рендери.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "uk-w390dp-h844dp-xxhdpi")
class ProfileBannerShotsTest {

    @Test fun mePhotosDay() = shot("me-photos-390-day", SunriseWarm) { page(banner = 6) }
    @Test fun mePhotosNight() = shot("me-photos-390-night", AmberNight) { page(banner = 6) }
    @Test fun meVideoDay() = shot("me-video-390-day", SunriseWarm) { page(banner = 1, video = true) }
    @Test fun meVideoNight() = shot("me-video-390-night", AmberNight) { page(banner = 1, video = true) }
    @Test fun meEmptyDay() = shot("me-empty-390-day", SunriseWarm) { page(banner = 0) }
    @Test fun meEmptyNight() = shot("me-empty-390-night", AmberNight) { page(banner = 0) }
    @Config(qualifiers = "uk-w390dp-h1300dp-xxhdpi")
    @Test fun meFullDay() = shot("me-full-390-day", SunriseWarm) { page(banner = 6) }
    @Config(qualifiers = "uk-w390dp-h1300dp-xxhdpi")
    @Test fun meFullNight() = shot("me-full-390-night", AmberNight) { page(banner = 6, accent = "sea") }
    @Test fun bannerEditDay() = shot("banner-edit-390-day", SunriseWarm) { editor() }
    @Test fun bannerEditNight() = shot("banner-edit-390-night", AmberNight) { editor() }

    private val skies = listOf(
        0xFFDCE3D3.toInt() to 0xFF5E8F55.toInt(),
        0xFFE9DCC6.toInt() to 0xFFC8693E.toInt(),
        0xFFB9CCE0.toInt() to 0xFFF4F1EA.toInt(),
        0xFF2F6F6A.toInt() to 0xFFB9D6CF.toInt(),
        0xFFD7D2C8.toInt() to 0xFF6B4F3A.toInt(),
        0xFF8FA37C.toInt() to 0xFFE8E2CF.toInt(),
    )

    private fun picture(w: Int, h: Int, ground: Int, ink: Int, seed: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(ground)
        val p = Paint().apply { color = ink; isAntiAlias = true; style = Paint.Style.STROKE; strokeWidth = w / 90f }
        val path = Path()
        for (i in 0 until 7) {
            val y = h * (0.2f + i * 0.1f)
            path.reset()
            path.moveTo(0f, y)
            path.cubicTo(w * 0.3f, y - h * 0.08f * ((seed + i) % 3), w * 0.6f, y + h * 0.07f, w.toFloat(), y - h * 0.03f)
            c.drawPath(path, p)
        }
        return b
    }

    private fun shots(n: Int) = List(n) { i ->
        val (g, ink) = skies[i % skies.size]
        val bmp = picture(780, 420, g, ink, i)
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it); it.toByteArray() }
        BannerShot(bytes, bmp)
    }

    private fun face(): Bitmap = picture(208, 208, 0xFFC8693E.toInt(), 0xFFE9DCC6.toInt(), 2)

    @Composable
    private fun page(banner: Int, video: Boolean = false, accent: String? = null) = ProfileFaceBody(
        me = Me(
            name = "Марта Коваль",
            photo = if (banner == 0) null else face(),
            banner = shots(banner),
            video = if (video) File("/nonexistent/banner-video") else null,
            videoMs = if (video) 6_000L else 0L,
            statusText = if (banner == 0) "" else "Майстерня до 18:00",
        ),
        handle = "marta.k",
        activeProfile = if (accent == null) "Особистий" else "Студія",
        activeAccent = accent,
        circles = listOf(
            ProfileFace.Circle("close", R.string.circle_close, 3),
            ProfileFace.Circle("friends", R.string.circle_friends, 7),
        ),
        highlights = emptyList(),
        failed = false,
        naming = false,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = {},
        openTab = {}, composeMoment = {},
        profiles = listOf(
            ProfilesMirror.Profile(id = "me", name = "Особистий"),
            ProfilesMirror.Profile(id = "studio", name = "Студія", accent = "sea"),
            ProfilesMirror.Profile(id = "home", name = "Сімʼя", accent = "rose"),
        ),
        activeId = if (accent == null) "me" else "studio",
    )

    @Composable
    private fun editor() = BannerEditorBody(shots(4).mapIndexed { i, s -> if (i == 0) s.at(128, 77) else s }, null, saving = false, failed = false, onDismiss = {}, onSave = { _, _ -> })

    private fun copy(window: Window): Bitmap {
        val decor = window.decorView
        val full = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        return full
    }

    private fun shot(name: String, spec: PhantomThemeSpec, sheet: Boolean = false, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(LocalMotionScale provides 0f) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
            }
        }
        repeat(30) {
            Snapshot.sendApplyNotifications()
            clock.sendFrame(System.nanoTime())
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
        }
        val root = activity.findViewById<android.view.View>(android.R.id.content)
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val page = Bitmap.createBitmap(copy(activity.window), at[0], at[1], root.width, root.height)
        val bitmap = if (!sheet) page else {
            val dialog = ShadowDialog.getLatestDialog()
            assertTrue("$name: аркуша немає", dialog != null)
            val raw = copy(dialog!!.window!!)
            val out = page.copy(Bitmap.Config.ARGB_8888, true)
            val c = Canvas(out)
            c.drawColor(0x99000000.toInt())
            val v = dialog.window!!.decorView
            val loc = IntArray(2).also { v.getLocationOnScreen(it) }
            val top = (root.height - v.height).coerceAtLeast(0).takeIf { loc[1] == 0 } ?: (loc[1] - at[1])
            c.drawBitmap(raw, 0f, top.toFloat(), null)
            out
        }
        val dir = File("build/profile-r1-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: нічого не намалювалось", inked > 50)
        recomposer.cancel()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
