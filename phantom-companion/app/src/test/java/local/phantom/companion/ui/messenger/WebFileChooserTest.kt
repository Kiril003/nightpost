package local.phantom.companion.ui.messenger

import android.net.Uri
import android.webkit.ValueCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WebFileChooserTest {

    @Test
    fun `capture — це камера, лише відео — відеокамера`() {
        assertEquals(ChooserPlan.Camera(video = false), WebFileChooser.plan(arrayOf("image/*"), capture = true, multiple = false))
        assertEquals(ChooserPlan.Camera(video = true), WebFileChooser.plan(arrayOf("video/*"), capture = true, multiple = false))
        assertEquals(ChooserPlan.Camera(video = false), WebFileChooser.plan(null, capture = true, multiple = false))
    }

    @Test
    fun `фото й відео — Photo Picker, файл — вибір документів`() {
        assertEquals(
            ChooserPlan.Media(images = true, video = true, multiple = true),
            WebFileChooser.plan(arrayOf("image/*,video/*"), capture = false, multiple = true),
        )
        assertEquals(
            ChooserPlan.Media(images = true, video = false, multiple = false),
            WebFileChooser.plan(arrayOf(" IMAGE/png "), capture = false, multiple = false),
        )
        assertEquals(ChooserPlan.Documents(listOf("*/*"), true), WebFileChooser.plan(arrayOf(""), capture = false, multiple = true))
        assertEquals(ChooserPlan.Documents(listOf("*/*"), false), WebFileChooser.plan(null, capture = false, multiple = false))
        assertEquals(
            ChooserPlan.Documents(listOf("image/*", "application/pdf"), false),
            WebFileChooser.plan(arrayOf("image/*", ".pdf", "application/pdf"), capture = false, multiple = false),
        )
    }

    @Test
    fun `рід у Photo Picker відповідає тому, що просили`() {
        fun type(p: ChooserPlan.Media) = WebFileChooser.mediaRequest(p).mediaType
        assertEquals(PickVisualMedia.ImageOnly, type(ChooserPlan.Media(images = true, video = false, multiple = false)))
        assertEquals(PickVisualMedia.VideoOnly, type(ChooserPlan.Media(images = false, video = true, multiple = false)))
        assertEquals(PickVisualMedia.ImageAndVideo, type(ChooserPlan.Media(images = true, video = true, multiple = true)))
    }

    private class Got : ValueCallback<Array<Uri>> {
        val answers = mutableListOf<Array<Uri>?>()
        override fun onReceiveValue(value: Array<Uri>?) {
            answers += value
        }
    }

    private class Launches {
        var cameraOk = true
        val photos = mutableListOf<Uri>()
        val videos = mutableListOf<Uri>()
        val media = mutableListOf<Pair<PickVisualMediaRequest, Boolean>>()
        val docs = mutableListOf<Pair<List<String>, Boolean>>()
        val target: Uri = Uri.parse("content://test.fileprovider/picked/x/IMG.jpg")
        fun session() = WebFileChooserSession(
            askCamera = { it(cameraOk) },
            captureTarget = { target },
            takePhoto = { photos += it },
            takeVideo = { videos += it },
            pickMedia = { r, m -> media += r to m },
            openDocuments = { mimes, m -> docs += mimes.toList() to m },
        )
    }

    @Test
    fun `камеру дозволено — знімок повертається у вебу`() {
        val l = Launches()
        val s = l.session()
        val got = Got()
        assertTrue(s.show(got, ChooserPlan.Camera(video = false)))
        assertEquals(listOf(l.target), l.photos)
        s.onCaptured(true)
        assertEquals(1, got.answers.size)
        assertArrayEquals(arrayOf(l.target), got.answers.single())
    }

    @Test
    fun `камеру не дозволено — камера не відкривається, а веб отримує чесне «нічого»`() {
        val l = Launches().apply { cameraOk = false }
        val got = Got()
        l.session().show(got, ChooserPlan.Camera(video = false))
        assertTrue(l.photos.isEmpty())
        assertEquals(1, got.answers.size)
        assertNull(got.answers.single())
    }

    @Test
    fun `знімок скасовано — веб отримує null і може відкрити вибір знову`() {
        val l = Launches()
        val s = l.session()
        val got = Got()
        s.show(got, ChooserPlan.Camera(video = true))
        assertEquals(listOf(l.target), l.videos)
        s.onCaptured(false)
        assertNull(got.answers.single())
    }

    @Test
    fun `новий вибір закриває попередній відповіддю null`() {
        val l = Launches()
        val s = l.session()
        val first = Got()
        val second = Got()
        s.show(first, ChooserPlan.Media(images = true, video = true, multiple = true))
        s.show(second, ChooserPlan.Documents(listOf("*/*"), false))
        assertNull(first.answers.single())
        assertEquals(listOf(listOf("*/*") to false), l.docs)
        assertEquals(PickVisualMedia.ImageAndVideo, l.media.single().first.mediaType)
        assertTrue(l.media.single().second)
    }

    @Test
    fun `порожній вибір відповідає одразу, непорожній віддає колбек для копій`() {
        val s = Launches().session()
        val got = Got()
        s.show(got, ChooserPlan.Documents(listOf("*/*"), true))
        assertNull(s.takePicked(emptyArray()))
        assertNull(got.answers.single())
        val again = Got()
        s.show(again, ChooserPlan.Documents(listOf("*/*"), true))
        assertEquals(again, s.takePicked(arrayOf(Uri.parse("content://x/1"))))
        assertTrue(again.answers.isEmpty())
    }

    @Test
    fun `знімок лягає в теку копій під нашим FileProvider`() {
        val app = RuntimeEnvironment.getApplication()
        val uri = WebFileChooser.captureTarget(app, video = false, nowMs = 0L)
        assertEquals("${app.packageName}.fileprovider", uri.authority)
        assertTrue(uri.path!!.startsWith("/picked/"))
        assertTrue(uri.lastPathSegment!!.matches(Regex("IMG_\\d{8}_\\d{6}\\.jpg")))
        assertTrue(File(app.cacheDir, WebPickedFiles.DIR).isDirectory)
    }

    @Test
    fun `досяжність — WebView відкриває вибір через план, а не один спільний вибір`() {
        val host = File("src/main/java/local/phantom/companion/ui/messenger/WebMessengerHost.kt").readText()
        assertTrue(host.contains("WebFileChooser.plan(fileChooserParams)"))
        assertTrue(host.contains("ActivityResultContracts.TakePicture()"))
        assertTrue(host.contains("ActivityResultContracts.PickMultipleVisualMedia()"))
        assertTrue(host.contains("cameraGate.request(answer)"))
        assertTrue(!host.contains("fileChooserParams?.createIntent()"))
    }
}
