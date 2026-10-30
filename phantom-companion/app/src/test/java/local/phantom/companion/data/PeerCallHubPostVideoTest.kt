package local.phantom.companion.data

import android.app.Application
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `post` складає кадр через org.json, якого в чистій JVM немає, тому тут Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerCallHubPostVideoTest {

    private val me = "aaaa1111bbbb2222"
    private val marta = "cccc3333dddd4444"
    private val callId = "call_9f31ac2b"

    @Test
    fun `набір відеодзвінка з порожньої лінії не будить службу окремо — плашка Dialing візьме відео сама`() {
        val seen = mutableListOf<Boolean>()
        val hub = PeerCallHub(nowMs = { 1_788_517_702_898L }, send = { _, _ -> true }, selfId = { me }, onVideo = { seen += it })
        runBlocking { hub.post(marta, "offer", callId, JSONObject("""{"sdp":"v=0","media":"video"}"""), me) }
        assertTrue(hub.video.value)
        assertTrue("лінія була порожня, службі нічого було міняти: $seen", seen.isEmpty())
    }

    @Test
    fun `своє відео посеред розмови теж доходить до служби`() {
        val seen = mutableListOf<Boolean>()
        val hub = PeerCallHub(nowMs = { 1_788_517_702_898L }, send = { _, _ -> true }, selfId = { me }, onVideo = { seen += it })
        runBlocking {
            hub.post(marta, "offer", callId, JSONObject("""{"sdp":"v=0","media":"audio"}"""), me)
            assertTrue("набір мусить відкрити лінію", hub.lineOpen.value)
            hub.post(marta, "offer", callId, JSONObject("""{"sdp":"v=0 re","media":"video"}"""), me)
        }
        assertEquals(listOf(true), seen)
    }
}
