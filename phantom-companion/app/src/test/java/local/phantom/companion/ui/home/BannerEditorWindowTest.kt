package local.phantom.companion.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplay

/**
 * «Зберегти» банера на телефоні, де вікно діалогу коротше за висоту з конфігурації:
 * так Android 15+ тримає плаваюче вікно між смугами (тут — 24 dp згори і 48 dp
 * кнопок навігації знизу), а Compose 1.7 міряє вміст на всю висоту.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "uk-w360dp-h640dp-xxhdpi")
class BannerEditorWindowTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L
    private var note: SemanticsNode? = null

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun settle() = repeat(30) {
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

    private fun shots(n: Int) = List(n) { i ->
        BannerShot(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), i.toByte()), Bitmap.createBitmap(390, 210, Bitmap.Config.ARGB_8888))
    }

    private fun tap(root: View, x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        root.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
        root.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
        settle()
    }

    private fun editorOnShortWindow(video: Boolean, onSave: () -> Unit): Pair<View, SemanticsNode> {
        RuntimeEnvironment.setFontScale(1.3f)
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val bars = ((24 + 48) * density).toInt()
        val display = ShadowDisplay.getDefaultDisplay()
        shadowOf(display).setHeight((640 * density).toInt() - bars)

        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        val clip = if (video) PickedVideo(File("clip.mp4"), shots(1)[0], 4_000L) else null
        c.get().setContent(parent = r) {
            PhantomTheme(spec = AmberNight) {
                BannerEditor(shots(6), clip, saving = false, failed = true, onDismiss = {}, onSave = { _, _ -> onSave() })
            }
        }
        settle()
        assertEquals("модель: конфігурація вища за вікно", 640, c.get().resources.configuration.screenHeightDp)
        val root = ShadowDialog.getLatestDialog()!!.window!!.decorView
        val save = roots(root).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "banner-save" }
        assertNotNull("«Зберегти» немає у вікні редактора", save)
        note = roots(root).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }
            .firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "banner-note" }
        return root to save!!
    }

    private fun keepShot(root: View, vis: Rect, name: String) {
        val full = Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        android.view.PixelCopy.request(ShadowDialog.getLatestDialog()!!.window!!, full, {}, android.os.Handler(Looper.getMainLooper()))
        shadowOf(Looper.getMainLooper()).idle()
        val seen = Bitmap.createBitmap(full, 0, 0, minOf(full.width, vis.width()), minOf(full.height, vis.height()))
        val dir = File("build/banner-fix-shots").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { seen.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertReachable(video: Boolean) {
        var saved = 0
        val (root, save) = editorOnShortWindow(video) { saved++ }
        val vis = Rect().also(root::getWindowVisibleDisplayFrame)
        val at = IntArray(2).also(root::getLocationOnScreen)
        keepShot(root, vis, if (video) "banner-video-360-font13" else "banner-photos-360-font13")
        val b = save.boundsInWindow
        assertTrue("«Зберегти» має висоту: $b", b.height > 0f)
        assertTrue("«Зберегти» нижче видимого вікна: ${b.bottom} > ${vis.bottom - at[1]}", b.bottom <= vis.bottom - at[1])
        assertTrue("«Зберегти» вище видимого вікна: ${b.top}", b.top >= vis.top - at[1])
        val n = note?.boundsInWindow
        assertTrue("помилка збереження не видна: $n", n != null && n.height > 0f && n.bottom <= b.top)
        tap(root, b.center.x, b.center.y)
        assertEquals("дотик по «Зберегти» не зберіг", 1, saved)
    }

    @Test fun `six photos at font 1_3 on a short window keep Save visible above the nav bar and a tap saves`() = assertReachable(video = false)

    @Test fun `video side on a short window keeps Save visible and a tap saves`() = assertReachable(video = true)
}
