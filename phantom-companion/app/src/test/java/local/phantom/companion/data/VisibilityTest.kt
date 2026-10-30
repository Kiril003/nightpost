package local.phantom.companion.data

import java.io.File
import java.nio.file.Files
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerProfile
import local.phantom.companion.data.VisibilityStore.Choice
import local.phantom.companion.data.VisibilityStore.Level
import local.phantom.companion.ui.messenger.WebMessengerPrivacy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisibilityTest {

    private val dir: File = Files.createTempDirectory("vis").toFile()
    private val roster = listOf(person("ana"), person("bob"))
    private val circle = setOf("ana")
    private var choice = Choice()
    private val wire = mutableListOf<Pair<String, String>>()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(300) { it.toByte() }
    private val thumb = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)

    private fun person(id: String) = PeerIdentity(
        id = id,
        name = id,
        kind = PeerIdentity.Kind.PERSON,
        pubKey = "pk-$id",
        dhPubKey = "dh-$id",
        introducedAtMs = 1L,
    )

    private fun herald() = ProfileHerald(
        dir = dir,
        legacyRev = { 0L },
        people = { roster },
        blocked = { emptySet() },
        name = { "Кирило" },
        handle = { PeerProfile.Field.Cleared },
        send = { id, frame -> wire += id to frame; true },
        hides = { id -> choice.hides(id in circle) },
    )

    private fun lastTo(id: String) = PeerProfile.decode(wire.last { it.first == id && PeerProfile.isProfile(it.second) }.second)!!

    private fun withPhoto() = PeerProfile.Body(rev = 0, avatar = PeerProfile.Field.Set(thumb), photo = PeerProfile.Field.Set(jpeg))

    @Test
    fun `«Мої люди» — фото бачить лише людина з кола, решта отримує літери`() {
        choice = Choice(photo = Level.PEOPLE)
        herald().publish(0, withPhoto())
        assertTrue((lastTo("ana").avatar as PeerProfile.Field.Set).value.contentEquals(thumb))
        assertEquals(PeerProfile.Field.Cleared, lastTo("bob").avatar)
        assertEquals(PeerProfile.Field.Cleared, lastTo("bob").photo)
    }

    @Test
    fun `зміна вибору досилає кадр новішою версією — і забирає, і повертає`() {
        val h = herald()
        h.publish(0, withPhoto())
        val first = lastTo("bob").rev
        wire.clear()
        choice = Choice(photo = Level.NOBODY)
        h.sweep()
        val hidden = lastTo("bob")
        assertTrue(hidden.rev > first)
        assertEquals(PeerProfile.Field.Cleared, hidden.avatar)
        assertEquals(PeerProfile.Field.Cleared, lastTo("ana").avatar)
        wire.clear()
        h.sweep()
        assertTrue(wire.isEmpty())
        choice = Choice()
        h.sweep()
        assertTrue(lastTo("bob").rev > hidden.rev)
        assertTrue((lastTo("bob").avatar as PeerProfile.Field.Set).value.contentEquals(thumb))
    }

    @Test
    fun `«Ніхто» для часу в мережі — кадр каже людям не показувати його`() {
        choice = Choice(lastSeen = Level.NOBODY)
        herald().publish(0, withPhoto())
        assertTrue(lastTo("ana").hidesSeen)
        assertTrue(lastTo("bob").hidesSeen)
        assertTrue((lastTo("bob").avatar as PeerProfile.Field.Set).value.contentEquals(thumb))
    }

    @Test
    fun `час у мережі не показується, коли людина ховає свій або ми ховаємо наш`() {
        val ana = person("ana")
        assertTrue(VisibilityStore.seenShown(ana, Choice().hides(true)))
        assertFalse(VisibilityStore.seenShown(ana.copy(hidesSeen = true), 0))
        assertFalse(VisibilityStore.seenShown(ana, Choice(lastSeen = Level.NOBODY).hides(true)))
        assertFalse(VisibilityStore.seenShown(ana, Choice(lastSeen = Level.PEOPLE).hides(false)))
        assertTrue(VisibilityStore.seenShown(ana, Choice(lastSeen = Level.PEOPLE).hides(true)))
    }

    @Test
    fun `міст віддає вибір і приймає лише відомі рівні`() {
        var receipts = true
        var saved = Choice()
        val route = WebMessengerPrivacy({ receipts }, { receipts = it }, { saved }, { saved = it })
        val get = JSONObject(route.handle("GET", null)).getJSONObject("data")
        assertEquals("all", get.getString("last_seen"))
        assertEquals("all", get.getString("photo"))

        val set = JSONObject(route.handle("PATCH", """{"last_seen":"nobody"}""")).getJSONObject("data")
        assertEquals("nobody", set.getString("last_seen"))
        assertEquals(Level.NOBODY, saved.lastSeen)

        val bad = JSONObject(route.handle("PATCH", """{"photo":"friends"}"""))
        assertFalse(bad.getBoolean("ok"))
        assertEquals(Level.ALL, saved.photo)

        JSONObject(route.handle("PATCH", """{"read_receipts":false}"""))
        assertFalse(receipts)
        assertEquals(Level.NOBODY, saved.lastSeen)
    }
}
