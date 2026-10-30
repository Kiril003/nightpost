package local.phantom.companion.ui.home

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import local.phantom.companion.core.design.theme.AmberNight
import local.phantom.companion.core.design.theme.PhantomTheme
import local.phantom.companion.core.net.peer.PeerBanner
import local.phantom.companion.core.net.peer.PeerProfile
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
import org.robolectric.shadows.ShadowDialog

/** Банер «Я»: стиснення до дроту, фокус кадрування, редактор до шести фото і аркуші сторінки. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class, qualifiers = "en-w390dp-h844dp-xhdpi")
class ProfileBannerTest {

    private var controller: ActivityController<ComponentActivity>? = null
    private var recomposer: Recomposer? = null
    private val clock = BroadcastFrameClock()
    private var frameNanos = 0L

    @After fun close() {
        recomposer?.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        controller?.pause()?.stop()?.destroy()
    }

    private fun open(content: @Composable () -> Unit): ComponentActivity {
        val c = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        controller = c
        val ctx = Dispatchers.Main + clock
        val r = Recomposer(ctx)
        recomposer = r
        CoroutineScope(ctx + Job()).launch { r.runRecomposeAndApplyChanges() }
        c.get().setContent(parent = r) { PhantomTheme(spec = AmberNight) { content() } }
        settle()
        return c.get()
    }

    private fun settle() = repeat(20) {
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

    private fun screen(a: ComponentActivity): List<SemanticsNode> {
        val views = listOf(a.window.decorView) + listOfNotNull(ShadowDialog.getLatestDialog()?.window?.decorView)
        return views.flatMap(::roots).flatMap { all(it.semanticsOwner.unmergedRootSemanticsNode) }
    }

    private fun SemanticsNode.texts() = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun tag(a: ComponentActivity, tag: String) = screen(a).firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun click(n: SemanticsNode?) {
        assertNotNull(n)
        n!!.config[SemanticsActions.OnClick].action!!.invoke()
        settle()
    }

    private fun noisy(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val rnd = java.util.Random(7)
        val p = Paint()
        repeat(300) {
            p.color = rnd.nextInt() or 0xFF000000.toInt()
            c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, 4f + rnd.nextFloat() * 40f, p)
        }
        return b
    }

    private fun shot(i: Int) = BannerShot(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), i.toByte()), Bitmap.createBitmap(390, 210, Bitmap.Config.ARGB_8888))

    @Test fun `a big noisy photo is squeezed into one PH-PBN frame`() {
        val bytes = BannerCompress.encode(noisy(4000, 3000))
        assertNotNull("фото не влізло в кадр", bytes)
        assertTrue("понад стелю: ${bytes!!.size}", bytes.size <= PeerBanner.MAX_BYTES)
        assertEquals(PeerProfile.ImageKind.WEBP, PeerProfile.imageKind(bytes))
        assertNotNull("кадр дроту не будується", PeerBanner.encode(bytes))
        val back = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertTrue("довша сторона ≤ 800", maxOf(back.width, back.height) <= BannerCompress.SIDE)
        assertEquals(800 to 600, BannerCompress.fit(4000, 3000, 800))
        assertEquals(300 to 200, BannerCompress.fit(300, 200, 800))
    }

    @Test fun `the crop frame follows the focus and a touch maps back to the same focus`() {
        val img = BannerFocus.fitted(390f, 240f, 1200, 900)
        assertEquals(320f, img.width, 0.5f)
        assertEquals(240f, img.height, 0.5f)
        val left = BannerFocus.frame(img, 0, 0)
        val right = BannerFocus.frame(img, 255, 255)
        assertEquals("рамка на ширину фото", img.width, left.width, 0.5f)
        assertEquals("рамка в пропорції банера", img.width * 210f / 390f, left.height, 0.5f)
        assertEquals(img.top, left.top, 0.5f)
        assertEquals(img.top + img.height - right.height, right.top, 0.5f)
        for (fy in listOf(0, 77, 128, 255)) {
            val f = BannerFocus.frame(img, 128, fy)
            val (_, back) = BannerFocus.fromTouch(img, Offset(f.left + f.width / 2, f.top + f.height / 2))
            assertTrue("фокус $fy → $back", kotlin.math.abs(back - fy) <= 1)
        }
        assertEquals(-1f, BannerFocus.bias(0), 0.001f)
        assertEquals(1f, BannerFocus.bias(255), 0.001f)
        val (sx, sy) = BannerFocus.cropScale(390f, 210f, 1080, 1920)
        assertTrue("відео заповнює банер без полів: $sx×$sy", sx >= 1f - 1e-3f && sy >= 1f - 1e-3f && kotlin.math.abs(minOf(sx, sy) - 1f) < 1e-3f)
    }

    @Test fun `six photos fill the editor, the seventh slot says Max 6, removing one frees it, Save keeps the order`() {
        var saved: List<BannerShot>? = null
        val six = List(6, ::shot)
        val a = open { BannerEditorBody(six, null, saving = false, failed = false, onDismiss = {}, onSave = { s, _ -> saved = s }) }
        val slot = tag(a, "banner-slot-full")
        assertNotNull("сьомий слот не показано", slot)
        val dp = a.resources.displayMetrics.density
        assertTrue("сьомий слот вужчий за дотик: ${slot!!.size.width / dp}", slot.size.width / dp >= 47.5f)
        assertTrue("сьомий слот за краєм 390", slot.boundsInWindow.right / dp <= 390f - 15.5f)
        assertTrue(screen(a).any { "Max 6" in it.texts() })
        assertTrue(screen(a).any { "6/6" in it.texts() })
        val fromPhone = tag(a, "banner-from-phone")!!
        assertTrue("«From phone» при 6/6 не тисне", fromPhone.config.getOrNull(SemanticsProperties.Disabled) != null)
        click(tag(a, "banner-thumb-2"))
        click(tag(a, "banner-remove"))
        assertNull("слот 7 лишився при 5/6", tag(a, "banner-slot-full"))
        assertTrue(screen(a).any { "5/6" in it.texts() })
        click(tag(a, "banner-save"))
        assertEquals(listOf(0, 1, 3, 4, 5).map { six[it].hash }, saved!!.map { it.hash })
    }

    @Test fun `an empty editor offers the gallery in the frame itself and the frame takes the free height`() {
        val a = open { BannerEditorBody(emptyList(), null, saving = false, failed = false, onDismiss = {}, onSave = { _, _ -> }) }
        val dp = a.resources.displayMetrics.density
        val crop = tag(a, "banner-crop")!!
        assertTrue("кадр низький, під ним порожньо: ${crop.size.height / dp}", crop.size.height / dp >= 400f)
        val empty = tag(a, "banner-empty-pick")
        assertNotNull("порожній кадр без дії (T51)", empty)
        assertTrue(screen(a).filter { it.config.getOrNull(SemanticsProperties.TestTag) == "banner-empty-pick" }.flatMap { all(it) }.any { "From phone" in it.texts() })
        click(empty)
        val started = shadowOf(a).nextStartedActivityForResult
        assertNotNull("дотик по порожньому кадру не відкрив галерею", started)
    }

    @Test fun `the Video side shows its picker and Save sends no photos without a clip`() {
        var saved: Pair<List<BannerShot>, PickedVideo?>? = null
        val a = open { BannerEditorBody(listOf(shot(1)), null, saving = false, failed = true, onDismiss = {}, onSave = { s, v -> saved = s to v }) }
        assertTrue("збій збереження не показано", screen(a).any { "Not saved" in it.texts() })
        click(tag(a, "banner-mode-video"))
        assertNull("мініатюри фото на боці відео", tag(a, "banner-thumb-0"))
        assertNotNull(tag(a, "banner-from-phone"))
        click(tag(a, "banner-save"))
        assertEquals("без відео лишаються фото", 1, saved!!.first.size)
        assertNull(saved!!.second)
    }

    @Test fun `the edit sheet lists what can change and hides clears that have nothing to clear`() {
        val picked = mutableListOf<EditRow>()
        val a = open { ProfileEditSheet(hasPhoto = true, hasBanner = false, onPick = { picked += it }, onClose = {}) }
        assertNotNull(tag(a, "edit-photo"))
        assertNotNull(tag(a, "edit-banner"))
        assertNotNull(tag(a, "edit-clear_photo"))
        assertNull("нема банера — нема чого прибирати", tag(a, "edit-clear_banner"))
        click(tag(a, "edit-banner"))
        assertEquals(listOf(EditRow.BANNER), picked)
    }

    @Test fun `the profile glyph from the bridge picks its icon and an unknown one falls back to home`() {
        assertEquals(local.phantom.companion.R.drawable.ph_glyph_work, glyphIcon("work"))
        assertEquals(local.phantom.companion.R.drawable.ph_glyph_home, glyphIcon("rocket"))
        assertEquals(local.phantom.companion.R.drawable.ph_glyph_home, glyphIcon(null))
    }
}
