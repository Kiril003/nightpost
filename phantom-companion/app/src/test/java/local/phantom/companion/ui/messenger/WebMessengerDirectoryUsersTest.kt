package local.phantom.companion.ui.messenger

import android.app.Application
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import local.phantom.companion.core.net.crypto.Ed25519
import local.phantom.companion.core.net.peer.PeerIntro
import local.phantom.companion.core.net.peer.PeerInvite
import local.phantom.companion.data.PeerDirectoryStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebMessengerDirectoryUsersTest {

    @Test
    fun `addressless network row carries no key code as display name`() = runBlocking {
        val keys = Ed25519.generateKeypair()
        val pub = Base64.getEncoder().withoutPadding().encodeToString(keys.publicKey)
        val dh = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val code = PeerInvite.encode(PeerInvite.Invite(PeerIntro.fingerprint(pub), "", pub, dh, "", 0))
        val parsed = (PeerInvite.decode(code) as PeerInvite.Decoded.Ph2).invite
        val dir = WebMessengerDirectory(find = { h -> PeerDirectoryStore.Found.One(h, parsed, code) })
        val res = Json.parseToJsonElement(dir.route("GET", listOf("messenger", "directory", "users"), "query=olena_test1")).jsonObject
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        val row = res["data"]!!.jsonArray[0].jsonObject
        assertEquals("olena_test1", row["username"]!!.jsonPrimitive.content)
        val name = row["display_name"]!!.jsonPrimitive.content
        assertFalse("код ключа замість імені: $name", Regex("[0-9a-f]{4}·[0-9a-f]{4}").containsMatchIn(name))
    }
}
