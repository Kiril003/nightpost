package local.phantom.companion.ui.messenger

import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.core.net.peer.PeerRevise
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebMessengerReviseTest {

    private val mine = PeerMessage(id = "m1", fromId = "me", toId = "them", body = "о шостій", sentAtMs = 1_000L)
    private val theirs = mine.copy(id = "t1", fromId = "them", toId = "me")

    private val acts = mutableListOf<Pair<List<String>, PeerRevise.Act>>()
    private val kept = mutableListOf<PeerMessage>()
    private val forgotten = mutableListOf<PeerMessage>()
    private var log = listOf(mine, theirs)

    private fun revise(queued: Int?) = WebMessengerRevise(
        log = { log },
        me = { "me" },
        revise = { copies, act ->
            if (queued != null) acts += copies.map { it.id } to act(copies.first().id)
            queued
        },
        keepLocal = { kept += it },
        forget = { forgotten += it; 0 },
        nowMs = { now },
    )

    private var now = 9_000L

    @Test
    fun `підпис свого фото правиться, голосове — ні`() {
        val body = { kind: String ->
            """{"blob_id":"${"b".repeat(32)}","key_hex":"00ff","sha256":"ab","size":10,"name":"p.jpg","mime":"image/jpeg","kind":"$kind","caption":"старий"}"""
        }
        val photo = mine.copy(id = "p1", body = body("image"))
        val r = revise(queued = null).edit(listOf(photo), "новий") as WebMessengerRevise.Outcome.Edited
        assertEquals("новий", local.phantom.companion.core.net.peer.PeerFile.describe(kept.first { it.id == "p1" }.body)!!.caption)
        assertEquals("local", r.frame)
        val voice = mine.copy(id = "v1", body = body("voice"))
        assertEquals(WebMessengerRevise.Outcome.Refused(409, "not_words"), revise(1).edit(listOf(voice), "слова"))
    }

    @Test
    fun `після 48 год свій лист уже не правиться, і кадр не йде`() {
        now = mine.sentAtMs + PeerRevise.EDIT_WINDOW_MS + 1
        assertEquals(WebMessengerRevise.Outcome.Refused(409, "edit_window"), revise(1).edit(listOf(mine), "запізно"))
        assertTrue(acts.isEmpty())
        now = mine.sentAtMs + PeerRevise.EDIT_WINDOW_MS
        assertTrue(revise(1).edit(listOf(mine), "ще можна") is WebMessengerRevise.Outcome.Edited)
    }

    @Test
    fun `правка, що лягла в чергу, відповідає sent`() {
        val r = revise(queued = 1).edit(listOf(mine), "о сьомій") as WebMessengerRevise.Outcome.Edited
        assertEquals("sent", r.frame)
        assertEquals(listOf(listOf("m1") to PeerRevise.Edit("m1", "о сьомій")), acts)
    }

    @Test
    fun `правка без жодного кадру чесно лишається local`() {
        val r = revise(queued = 0).edit(listOf(mine), "о сьомій") as WebMessengerRevise.Outcome.Edited
        assertEquals("local", r.frame)
        val noKey = revise(queued = null).edit(listOf(mine), "о сьомій") as WebMessengerRevise.Outcome.Edited
        assertEquals("local", noKey.frame)
        val m1 = kept.first { it.id == "m1" }
        assertEquals("о сьомій", m1.body)
        assertEquals(9_000L, m1.editedAtMs)
        log = kept.distinctBy { it.id }
        assertEquals(listOf("о сьомій", "о шостій"), revise(queued = 0).history(m1).map { it.text })
    }

    @Test
    fun `чужий рядок і тіло пристрою не переписуються`() {
        assertEquals(WebMessengerRevise.Outcome.Refused(403, "not_mine"), revise(1).edit(listOf(theirs), "так"))
        assertEquals(
            422,
            (revise(1).edit(listOf(mine), "PH-LOC:50.1,30.5") as WebMessengerRevise.Outcome.Refused).status,
        )
        assertTrue(acts.isEmpty())
    }

    @Test
    fun `у групі правка йде кожній копії`() {
        val group = PeerGroup.Group("a1b2c3d4e5f6", "ланка", listOf("me", "a", "b"))
        val body = PeerGroup.encode(group, "о шостій")
        val copies = listOf("a", "b").map { mine.copy(id = "g-$it", toId = it, body = body) }
        val r = revise(queued = 2).edit(copies, "о сьомій") as WebMessengerRevise.Outcome.Edited
        assertEquals("sent", r.frame)
        assertEquals(listOf("g-a", "g-b"), acts.single().first)
    }

    @Test
    fun `стирання для всіх каже sent лише коли кадр пішов`() {
        val sent = revise(queued = 1).delete(listOf(mine), forEveryone = true) as WebMessengerRevise.Outcome.Deleted
        assertTrue(sent.forEveryone)
        assertEquals("sent", sent.frame)
        assertEquals(PeerRevise.Unsend("m1"), acts.single().second)
        assertTrue(forgotten.isEmpty())

        val nobody = revise(queued = 0).delete(listOf(mine), forEveryone = true) as WebMessengerRevise.Outcome.Deleted
        assertFalse(nobody.forEveryone)
        assertEquals("local", nobody.frame)
    }

    @Test
    fun `стирання в себе і чужого рядка — лише тут`() {
        val here = revise(queued = 1).delete(listOf(mine), forEveryone = false) as WebMessengerRevise.Outcome.Deleted
        assertFalse(here.forEveryone)
        val other = revise(queued = 1).delete(listOf(theirs), forEveryone = true) as WebMessengerRevise.Outcome.Deleted
        assertFalse(other.forEveryone)
        assertEquals(listOf(mine, theirs), forgotten)
        assertTrue(acts.isEmpty())
    }

    @Test
    fun `прапорець для всіх читається з запиту веба`() {
        assertTrue(WebMessengerRevise.forEveryone("for_everyone=true"))
        assertTrue(WebMessengerRevise.forEveryone("x=1&for_everyone=true"))
        assertFalse(WebMessengerRevise.forEveryone("for_everyone=false"))
        assertFalse(WebMessengerRevise.forEveryone(""))
    }

    @Test
    fun `міст веде правку й стирання обох розмов сюди`() {
        var dir: java.io.File? = java.io.File("").absoluteFile
        while (dir != null && !java.io.File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        val bridge = java.io.File(dir, "app/src/main/java/local/phantom/companion/ui/messenger/WebMessengerBridge.kt").readText()
        assertEquals(2, Regex("""revisions\.delete\(""").findAll(bridge).count())
        assertEquals(2, Regex("""revisions\.edit\(""").findAll(bridge).count())
        assertEquals(2, Regex("""ok\(versionsOf\(revisions\.history\(line\)\)\)""").findAll(bridge).count())
    }
}
