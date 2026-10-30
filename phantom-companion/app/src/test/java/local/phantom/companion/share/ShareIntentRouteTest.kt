package local.phantom.companion.share

import android.content.Intent
import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.invitePayload
import local.phantom.companion.ui.messenger.WebNav
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class ShareIntentRouteTest {

    private val app get() = RuntimeEnvironment.getApplication()

    private val invite = PeerInvite.Invite(
        peerId = "a1b2c3d4e5f6a7b8",
        name = "Марта",
        pubKey = "o3NKOiXtZDvHEzBuaevdkgoKEPmHCAfUGKfhlxmjevg",
        dhPubKey = "JHxQvxJ0EJ0kOxCkYq2mR5vP1kQ0oZ8mVhq7bT3sXWA",
        host = "",
        port = 0,
    )

    @After
    fun tearDown() {
        ShareInbox.clear(app)
        WebNav.pending.value = null
    }

    @Test
    fun `SEND image-jpeg with one stream stores one file and opens ShareIn`() {
        val uri = Uri.parse("content://test.share/photo.jpg")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
        }
        assertTrue(ShareInbox.accept(app, intent))
        val batch = ShareInbox.pending.value
        assertNotNull(batch)
        assertEquals(1, batch!!.items.size)
        assertEquals(0, batch.skipped)
        val item = batch.items.single()
        assertEquals("image", item.kind)
        assertEquals(5L, item.size)
        assertNotNull(item.path)
        assertTrue(File(item.path!!).isFile)
        assertEquals(WebNav.Door.ShareIn, WebNav.pending.value?.door)
    }

    @Test
    fun `SEND_MULTIPLE with 12 streams keeps 10 and counts 2 skipped`() {
        val uris = ArrayList<Uri>()
        repeat(12) { idx ->
            val u = Uri.parse("content://test.share/img_$idx.png")
            shadowOf(app.contentResolver).registerInputStream(u, ByteArrayInputStream(byteArrayOf(idx.toByte(), 9)))
            uris.add(u)
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        assertTrue(ShareInbox.accept(app, intent))
        val batch = ShareInbox.pending.value!!
        assertEquals(10, batch.items.size)
        assertEquals(2, batch.skipped)
        batch.items.forEach { assertTrue(File(it.path!!).isFile) }
    }

    @Test
    fun `SEND text-plain stores trimmed text item`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "  hello  ")
        }
        assertTrue(ShareInbox.accept(app, intent))
        val batch = ShareInbox.pending.value!!
        assertEquals(1, batch.items.size)
        assertEquals("text", batch.items.single().kind)
        assertEquals("hello", batch.items.single().text)
    }

    @Test
    fun `SEND text holding a PeerInvite stays on the invite path`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, PeerInvite.webLink(invite))
        }
        val raw = invitePayload(intent)
        assertNotNull(raw)
        val decoded = PeerInvite.decode(PeerInvite.findInText(raw) ?: raw)
        assertEquals(PeerInvite.Decoded.Ph2(invite), decoded)

        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val mainSrc = File(root, "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        val inviteIdx = mainSrc.indexOf("if (acceptInviteIntent(intent)) return")
        val shareIdx = mainSrc.indexOf("ShareInbox.accept(this, intent)")
        assertTrue("invite check must precede ShareInbox", inviteIdx in 0 until shareIdx)
    }

    private fun sendImage(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/jpeg"
        putExtra(Intent.EXTRA_STREAM, uri)
    }

    @Test
    fun `a file URI is not read and counts as skipped`() {
        val secret = File.createTempFile("secret", ".jpg").apply {
            writeBytes(byteArrayOf(7, 7, 7))
            deleteOnExit()
        }
        assertTrue(ShareInbox.accept(app, sendImage(Uri.fromFile(secret))))
        val batch = ShareInbox.pending.value!!
        assertEquals(0, batch.items.count { it.kind == "image" })
        assertEquals(1, batch.skipped)
        val cached = File(app.cacheDir, "share-in").walkTopDown().filter { it.isFile }.toList()
        assertTrue(cached.toString(), cached.isEmpty())
    }

    @Test
    fun `a URI of our own FileProvider is not read and counts as skipped`() {
        val own = Uri.parse("content://${app.packageName}.fileprovider/picked/letter.jpg")
        shadowOf(app.contentResolver).registerInputStream(own, ByteArrayInputStream(byteArrayOf(1, 2, 3)))
        assertTrue(ShareInbox.accept(app, sendImage(own)))
        val batch = ShareInbox.pending.value!!
        assertEquals(0, batch.items.size)
        assertEquals(1, batch.skipped)
    }

    @Test
    fun `an image over ten megabytes is skipped and leaves no file`() {
        val big = Uri.parse("content://test.share/big.jpg")
        shadowOf(app.contentResolver).registerInputStream(big, ByteArrayInputStream(ByteArray(10 * 1024 * 1024 + 1)))
        assertTrue(ShareInbox.accept(app, sendImage(big)))
        val batch = ShareInbox.pending.value!!
        assertEquals(0, batch.items.size)
        assertEquals(1, batch.skipped)
        assertTrue(File(app.cacheDir, "share-in").walkTopDown().none { it.isFile })
    }

    @Test
    fun `an intent already taken is not taken twice`() {
        val uri = Uri.parse("content://test.share/once.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2)))
        val intent = sendImage(uri)
        assertTrue(ShareInbox.accept(app, intent))
        ShareInbox.clear(app)
        assertFalse(ShareInbox.accept(app, intent))
        assertNull(ShareInbox.pending.value)
    }

    @Test
    fun `an empty share keeps the batch already waiting`() {
        val uri = Uri.parse("content://test.share/first.jpg")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(byteArrayOf(1, 2)))
        assertTrue(ShareInbox.accept(app, sendImage(uri)))
        val first = ShareInbox.pending.value!!
        val blank = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "   ")
        }
        assertFalse(ShareInbox.accept(app, blank))
        assertEquals(first, ShareInbox.pending.value)
        assertTrue(File(first.items.single().path!!).isFile)
    }

    @Test
    fun `a new share replaces the previous batch files`() {
        val a = Uri.parse("content://test.share/a.jpg")
        val b = Uri.parse("content://test.share/b.jpg")
        shadowOf(app.contentResolver).registerInputStream(a, ByteArrayInputStream(byteArrayOf(1)))
        shadowOf(app.contentResolver).registerInputStream(b, ByteArrayInputStream(byteArrayOf(2)))
        assertTrue(ShareInbox.accept(app, sendImage(a)))
        val old = File(ShareInbox.pending.value!!.items.single().path!!)
        assertTrue(ShareInbox.accept(app, sendImage(b)))
        assertFalse(old.exists())
        assertTrue(File(ShareInbox.pending.value!!.items.single().path!!).isFile)
    }

    @Test
    fun `AndroidManifest declares SEND and SEND_MULTIPLE for image`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/AndroidManifest.xml").isFile }
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File(root, "app/src/main/AndroidManifest.xml")).documentElement
        val ns = "http://schemas.android.com/apk/res/android"
        val activities = doc.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as Element }
            .first { it.getAttributeNS(ns, "name") == ".MainActivity" }
        val filters = main.getElementsByTagName("intent-filter")
        val triples = (0 until filters.length).map { filters.item(it) as Element }.map { f ->
            val actions = f.getElementsByTagName("action").let { l ->
                (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "name") }
            }
            val mimes = f.getElementsByTagName("data").let { l ->
                (0 until l.length).map { (l.item(it) as Element).getAttributeNS(ns, "mimeType") }
            }
            actions to mimes
        }
        assertTrue(triples.any { (a, m) -> "android.intent.action.SEND" in a && "image/*" in m })
        assertTrue(triples.any { (a, m) -> "android.intent.action.SEND_MULTIPLE" in a && "image/*" in m })
        assertFalse(triples.any { (_, m) -> "*/*" in m })
    }
}
