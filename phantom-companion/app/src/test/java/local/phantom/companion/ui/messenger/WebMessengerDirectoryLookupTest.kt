package local.phantom.companion.ui.messenger

import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
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

class WebMessengerDirectoryLookupTest {

    private val lookupSeg = listOf("messenger", "directory", "lookup")

    private fun card(): PeerInvite.Invite {
        val keys = Ed25519.generateKeypair()
        val pub = Base64.getEncoder().withoutPadding().encodeToString(keys.publicKey)
        val dh = Base64.getEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        return PeerInvite.Invite(
            peerId = PeerIntro.fingerprint(pub),
            name = "",
            pubKey = pub,
            dhPubKey = dh,
            host = "",
            port = 0,
        )
    }

    private fun parse(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    @Test
    fun `taken handle returns available false and taken true`() = runBlocking {
        val invite = card()
        val asked = mutableListOf<String>()
        val dir = WebMessengerDirectory(
            find = { h ->
                asked += h
                PeerDirectoryStore.Found.One(h, invite, PeerInvite.encode(invite))
            },
            myPeerId = { "different_peer_id" },
        )
        val res = parse(dir.route("GET", lookupSeg, "handle=%40Kyrylo_M1"))
        assertEquals(listOf("kyrylo_m1"), asked)
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, res["status"]!!.jsonPrimitive.int)
        val data = res["data"]!!.jsonObject
        assertEquals("kyrylo_m1", data["handle"]!!.jsonPrimitive.content)
        assertFalse(data["available"]!!.jsonPrimitive.boolean)
        assertTrue(data["taken"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `free handle returns available true and taken false`() = runBlocking {
        val asked = mutableListOf<String>()
        val dir = WebMessengerDirectory(
            find = { h ->
                asked += h
                PeerDirectoryStore.Found.None
            },
        )
        val res = parse(dir.route("GET", lookupSeg, "handle=kyrylo_free"))
        assertEquals(listOf("kyrylo_free"), asked)
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, res["status"]!!.jsonPrimitive.int)
        val data = res["data"]!!.jsonObject
        assertEquals("kyrylo_free", data["handle"]!!.jsonPrimitive.content)
        assertTrue(data["available"]!!.jsonPrimitive.boolean)
        assertFalse(data["taken"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `own handle returns available true and mine true`() = runBlocking {
        val myInvite = card()
        val asked = mutableListOf<String>()
        val dir = WebMessengerDirectory(
            find = { h ->
                asked += h
                PeerDirectoryStore.Found.One(h, myInvite, PeerInvite.encode(myInvite))
            },
            myPeerId = { myInvite.peerId },
        )
        val res = parse(dir.route("GET", lookupSeg, "handle=%40Kyrylo_M1"))
        assertEquals(listOf("kyrylo_m1"), asked)
        assertTrue(res["ok"]!!.jsonPrimitive.boolean)
        assertEquals(200, res["status"]!!.jsonPrimitive.int)
        val data = res["data"]!!.jsonObject
        assertEquals("kyrylo_m1", data["handle"]!!.jsonPrimitive.content)
        assertTrue(data["available"]!!.jsonPrimitive.boolean)
        assertFalse(data["taken"]!!.jsonPrimitive.boolean)
        assertTrue(data["mine"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `bad format returns 400 bad_handle without querying directory`() = runBlocking {
        val asked = mutableListOf<String>()
        val dir = WebMessengerDirectory(
            find = { h ->
                asked += h
                PeerDirectoryStore.Found.None
            },
        )
        for (q in listOf("handle=short", "handle=%D0%BA%D0%B8%D1%80%D0%B8%D0%BB%D0%BE_123", "handle=bad-name!", "handle=", "")) {
            val res = parse(dir.route("GET", lookupSeg, q))
            assertFalse("expected ok=false for $q", res["ok"]!!.jsonPrimitive.boolean)
            assertEquals("expected 400 for $q", 400, res["status"]!!.jsonPrimitive.int)
            assertEquals("bad_handle", res["code"]!!.jsonPrimitive.content)
        }
        assertTrue("directory must not be queried on bad format: $asked", asked.isEmpty())
    }
}
