package local.phantom.companion.ui.home

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
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
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.design.theme.PhantomThemeSpec
import local.phantom.companion.core.design.theme.SunriseWarm
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Кадри обличчя профілю вдень і вночі, англійською; лягають у build/profile-face-look/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = android.app.Application::class, qualifiers = "en-w411dp-h891dp-xhdpi")
class ProfileFaceShotsTest {

    @Test fun filledNight() = shot("profile-night-en", AmberNight) { filled() }
    @Test fun filledDay() = shot("profile-day-en", SunriseWarm) { filled() }
    @Test fun emptyNight() = shot("profile-empty-night-en", AmberNight) { empty() }
    @Test fun emptyDay() = shot("profile-empty-day-en", SunriseWarm) { empty() }
    @Test fun namingNight() = shot("profile-name-edit-night-en", AmberNight) { filled(naming = true) }

    private fun picture(w: Int, h: Int, sky: Int, ground: Int, sun: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(sky)
        c.drawCircle(w * 0.72f, h * 0.42f, h * 0.16f, Paint().apply { color = sun; isAntiAlias = true })
        c.drawRect(0f, h * 0.62f, w.toFloat(), h.toFloat(), Paint().apply { color = ground })
        return b
    }

    private fun thumb(sky: Int, ground: Int): ByteArray =
        ByteArrayOutputStream().use { picture(96, 96, sky, ground, 0xFFF2E3C6.toInt()).compress(Bitmap.CompressFormat.JPEG, 80, it); it.toByteArray() }

    private fun banner(vararg colors: Pair<Int, Int>): List<BannerShot> = colors.mapIndexed { i, (sky, ground) ->
        val bmp = picture(780, 420, sky, ground, 0xFFF0D9B5.toInt())
        val bytes = ByteArrayOutputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it); it.toByteArray() }
        BannerShot(bytes, bmp, 128, if (i == 0) 77 else 128)
    }

    @Composable
    private fun filled(naming: Boolean = false) = ProfileFaceBody(
        me = Me(
            name = "Ira Koval",
            photo = picture(208, 208, 0xFF9DB4C0.toInt(), 0xFF5C6B73.toInt(), 0xFFE0C9A6.toInt()),
            banner = banner(
                0xFF8FA9B8.toInt() to 0xFF3E4C55.toInt(),
                0xFFB7C9A8.toInt() to 0xFF56654A.toInt(),
                0xFFD8B4A0.toInt() to 0xFF7A5643.toInt(),
            ),
            statusText = "Studio until 18:00",
        ),
        handle = "ira",
        activeProfile = "Personal",
        circles = listOf(
            ProfileFace.Circle("close", R.string.circle_close, 3),
            ProfileFace.Circle("friends", R.string.circle_friends, 7),
            ProfileFace.Circle("work", R.string.circle_work, 4),
        ),
        highlights = listOf(
            ProfileFace.Highlight("a", thumb(0xFFB7C9A8.toInt(), 0xFF56654A.toInt())),
            ProfileFace.Highlight("b", thumb(0xFFD8B4A0.toInt(), 0xFF7A5643.toInt())),
            ProfileFace.Highlight("c", thumb(0xFFA8B8D8.toInt(), 0xFF46557A.toInt())),
        ),
        failed = false,
        naming = naming,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = {},
        openTab = {}, composeMoment = {},
    )

    @Composable
    private fun empty() = ProfileFaceBody(
        me = Me(name = "", photo = null),
        handle = null,
        circles = emptyList(),
        highlights = emptyList(),
        failed = false,
        naming = false,
        onPhoto = {}, onName = {}, onNameDone = {}, onNameCancel = {},
        onAction = {},
        openTab = {}, composeMoment = {},
    )

    private fun shot(name: String, spec: PhantomThemeSpec, content: @Composable () -> Unit) {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = controller.get()
        val clock = BroadcastFrameClock()
        val context = Dispatchers.Main + clock
        val recomposer = Recomposer(context)
        val scope = CoroutineScope(context + Job())
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        activity.setContent(parent = recomposer) {
            PhantomTheme(spec = spec) {
                CompositionLocalProvider(local.phantom.companion.core.design.motion.LocalMotionScale provides 0f) {
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
        val decor = activity.window.decorView
        val full = Bitmap.createBitmap(decor.width.coerceAtLeast(1), decor.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(activity.window, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val at = IntArray(2).also { root.getLocationInWindow(it) }
        val bitmap = Bitmap.createBitmap(full, at[0], at[1], root.width, root.height)
        val out = File("build/profile-face-look").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var inked = 0
        for (y in 0 until bitmap.height step 16) for (x in 0 until bitmap.width step 16) {
            if (bitmap.getPixel(x, y) != bitmap.getPixel(0, 0)) inked++
        }
        assertTrue("$name: обличчя не намалювалось", inked > 50)
        recomposer.cancel()
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller.pause().stop().destroy()
    }
}
