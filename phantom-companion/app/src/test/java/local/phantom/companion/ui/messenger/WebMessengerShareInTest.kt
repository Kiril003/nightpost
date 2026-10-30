package local.phantom.companion.ui.messenger

import android.content.Intent
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayInputStream
import java.io.File
import local.phantom.companion.share.ShareInbox
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class WebMessengerShareInTest {

    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        ShareInbox.clear(app)
        WebNav.pending.value = null
    }

    @Test
    fun `empty pending returns null id and empty items`() {
        val route = WebMessengerShareIn(app)
        assertTrue(route.claims(listOf("messenger", "share", "pending")))
        val res = JSONObject(route.handle("GET", listOf("messenger", "share", "pending"), null))
        assertTrue(res.getBoolean("ok"))
        val data = res.getJSONObject("data")
        assertTrue(data.isNull("id"))
        assertEquals(0, data.getJSONArray("items").length())
        assertEquals(0, data.getInt("skipped"))
    }

    @Test
    fun `pending JSON shape, item base64 round-trip, and DELETE clears`() {
        val uri = Uri.parse("content://test.share/pic.jpg")
        val rawBytes = byteArrayOf(10, 20, 30, 40, 50, 60)
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(rawBytes))

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, "caption text")
        }
        assertTrue(ShareInbox.accept(app, intent))

        val route = WebMessengerShareIn(app)
        val pendingRes = JSONObject(route.handle("GET", listOf("messenger", "share", "pending"), null))
        assertTrue(pendingRes.getBoolean("ok"))
        val data = pendingRes.getJSONObject("data")
        assertTrue(data.getString("id").isNotEmpty())
        val items = data.getJSONArray("items")
        assertEquals(2, items.length())

        val imgItem = (0 until items.length()).map { items.getJSONObject(it) }.first { it.getString("kind") == "image" }
        val imgIndex = imgItem.getInt("index")

        val itemRes = JSONObject(route.handle("GET", listOf("messenger", "share", "item", imgIndex.toString()), null))
        assertTrue(itemRes.getBoolean("ok"))
        val itemData = itemRes.getJSONObject("data")
        val decoded = Base64.decode(itemData.getString("base64"), Base64.NO_WRAP)
        assertArrayEquals(rawBytes, decoded)

        val missingRes = JSONObject(route.handle("GET", listOf("messenger", "share", "item", "99"), null))
        assertEquals(false, missingRes.getBoolean("ok"))
        assertEquals(404, missingRes.getInt("status"))
        assertEquals("no_item", missingRes.getString("code"))

        val delRes = JSONObject(route.handle("DELETE", listOf("messenger", "share", "pending"), null))
        assertTrue(delRes.getBoolean("ok"))
        assertNull(ShareInbox.pending.value)
    }

    @Test
    fun `a map place waits in the chat share sheet with its point and name`() {
        assertTrue(ShareInbox.offerPlace(app, 49.84, 24.03, "  Ринок  "))
        assertEquals(WebNav.Door.ShareIn, WebNav.pending.value?.door)
        val data = JSONObject(WebMessengerShareIn(app).handle("GET", listOf("messenger", "share", "pending"), null))
            .getJSONObject("data")
        val item = data.getJSONArray("items").getJSONObject(0)
        assertEquals("place", item.getString("kind"))
        assertEquals(49.84, item.getDouble("lat"), 0.0)
        assertEquals(24.03, item.getDouble("lon"), 0.0)
        assertEquals("Ринок", item.getString("text"))
        assertEquals(false, ShareInbox.offerPlace(app, Double.NaN, 24.0, "x"))
        assertEquals(false, ShareInbox.offerPlace(app, 91.0, 24.0, "x"))
    }

    @Test
    fun `the map port hands the place to the share sheet`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val link = File(root, "app/src/main/java/local/phantom/companion/data/MapPeopleLink.kt").readText()
        assertTrue(link, "ShareInbox.offerPlace(container.applicationContext, lat, lon, name)" in link)
    }

    @Test
    fun `the outbound share route stays with the bridge`() {
        val route = WebMessengerShareIn(app)
        assertEquals(false, route.claims(listOf("messenger", "share")))
        assertTrue(route.claims(listOf("messenger", "share", "pending")))
        assertTrue(route.claims(listOf("messenger", "share", "item", "0")))
    }

    @Test
    fun `mount lines present in MainActivity and WebMessengerBridge`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "app/src/main/java").isDirectory }
        val mainSrc = File(root, "app/src/main/java/local/phantom/companion/MainActivity.kt").readText()
        assertTrue(
            "MainActivity must route share intents into ShareInbox",
            "if (local.phantom.companion.share.ShareInbox.accept(this, intent)) return" in mainSrc,
        )
        val bridgeSrc = File(root, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertTrue("WebMessengerBridge must instantiate WebMessengerShareIn", "WebMessengerShareIn(" in bridgeSrc)
        assertTrue(
            "WebMessengerBridge must delegate shareInRoute",
            "if (shareInRoute.claims(seg)) return shareInRoute.handle(method, seg, body)" in bridgeSrc,
        )
    }
}
