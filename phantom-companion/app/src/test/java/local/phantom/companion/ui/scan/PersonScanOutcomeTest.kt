package local.phantom.companion.ui.scan

import java.io.File
import kotlinx.coroutines.runBlocking
import local.phantom.companion.core.net.peer.PeerIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonScanOutcomeTest {

    private val olena = PeerIdentity(id = "olena-fixture", name = "Олена", kind = PeerIdentity.Kind.PERSON, pubKey = "k")

    @Test
    fun `camera scan marks verified and opens the chat`() {
        assertEquals(PersonScanOutcome.Outcome(markVerified = true, openChat = "olena-fixture"),
            PersonScanOutcome.after(PeerIdentity.KeySource.CAMERA, olena))
    }

    @Test
    fun `shared link opens the chat without verifying`() {
        assertEquals(PersonScanOutcome.Outcome(markVerified = false, openChat = "olena-fixture"),
            PersonScanOutcome.after(PeerIdentity.KeySource.SHARED, olena))
    }

    @Test
    fun `landing opens the chat, never the card`() = runBlocking {
        val verified = mutableListOf<String>()
        var chat: String? = null
        PersonScanOutcome.land(PeerIdentity.KeySource.CAMERA, { olena }, { verified += it }, { chat = it }, { error("card") })
        assertEquals(listOf("olena-fixture"), verified)
        assertEquals("olena-fixture", chat)

        verified.clear()
        assertNull(PersonScanOutcome.land(PeerIdentity.KeySource.CAMERA, { error("no key") }, { verified += it }, {}, {}))
        assertTrue(verified.isEmpty())
    }

    @Test
    fun `a link with a changed key opens the card that says so, not the chat`() = runBlocking {
        val changed = olena.copy(verifiedAtMs = 5L, keyChangedAtMs = 10L)
        var chat: String? = null
        var card: String? = null
        PersonScanOutcome.land(PeerIdentity.KeySource.SHARED, { changed }, { error("verify") }, { chat = it }, { card = it })
        assertNull(chat)
        assertEquals("olena-fixture", card)

        val verified = mutableListOf<String>()
        card = null
        PersonScanOutcome.land(PeerIdentity.KeySource.CAMERA, { changed }, { verified += it }, { chat = it }, { card = it })
        assertEquals(listOf("olena-fixture"), verified)
        assertEquals("olena-fixture", chat)
        assertNull(card)
    }

    @Test
    fun `a failed verify mark still opens the chat`() = runBlocking {
        var chat: String? = null
        PersonScanOutcome.land(PeerIdentity.KeySource.CAMERA, { olena }, { error("disk") }, { chat = it }, {})
        assertEquals("olena-fixture", chat)
    }

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "app/src/main/java").isDirectory }

    private fun src(path: String): String =
        File(root, "app/src/main/java/local/phantom/companion/$path").readText()

    @Test
    fun `scanner and shared link land in the chat, not on the compare card`() {
        val person = src("navigation/ScanDestination.kt")
            .substringAfter("is CodeRoute.Person ->").substringBefore("is CodeRoute.SafetyNumber ->")
        assertTrue(person.contains("PersonScanOutcome.land("))
        assertTrue(person.contains("PeerIdentity.KeySource.CAMERA"))
        assertTrue(person.contains("peers.setVerified(id, true"))
        assertTrue(person.contains("peerChatRequest.value = id"))
        assertTrue(person.contains("openCard = { id -> container.peerCardRequest.value = id }"))
        assertEquals("картка лише для зміненого ключа", 1, Regex("peerCardRequest").findAll(person).count())
        assertTrue("свій код додає себе в контакти", person.contains("myInvite.value?.peerId") && person.contains("scan_card_self"))

        val shared = src("MainActivity.kt")
            .substringAfter("private fun acceptInviteIntent(").substringBefore("\n    private fun ")
        assertTrue(shared.contains("PersonScanOutcome.land("))
        assertTrue(shared.contains("KeySource.SHARED"))
        assertTrue(shared.contains("peerChatRequest.value = id"))
        assertTrue(shared.contains("openCard = { id -> container.peerCardRequest.value = id }"))
        assertEquals("картка лише для зміненого ключа", 1, Regex("peerCardRequest").findAll(shared).count())
    }
}
