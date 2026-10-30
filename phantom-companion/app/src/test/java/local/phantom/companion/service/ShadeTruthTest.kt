package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.io.File
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerRosterStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Шторка — правда: прочитане зникає, той самий лист не дзвонить удруге, плашка доростає на місці. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ShadeTruthTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val nm get() = app.getSystemService(NotificationManager::class.java)

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        PeerNotifications.rosterOf = { PeerRosterStore(MemSlot()) }
        PeerNotifications.lockHides = { false }
        ShadeOnce.forget()
    }

    @After
    fun tearDown() {
        PeerNotifications.resetRoster()
    }

    private fun arrive(id: String, words: String, at: Long, from: String = "marta", name: String = "Марта") {
        val m = PeerMessage(id = id, fromId = from, toId = "me", body = words, sentAtMs = at)
        ShadeDoor.arrived(app, "me", from, id) {
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(m, null, null)!!, name, words, at)
        }
    }

    private fun textsOf(peer: String): List<String>? = shadowOf(nm).getNotification(PeerNotifications.idOf(peer))
        ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)?.messages?.map { m -> m.text.toString() } }

    private fun summary() = shadowOf(nm).allNotifications.firstOrNull {
        it.group == PeerNotifications.GROUP && (it.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0
    }

    @Test
    fun `відкрив розмову, сховок приніс той самий лист і новий — у шторці лише новий`() {
        arrive("A", "перший", 1_000L)
        assertEquals(listOf("перший"), textsOf("marta"))

        ShadeDoor.seen(app, "marta")
        assertNull("відкрита розмова лишилась у шторці", textsOf("marta"))

        arrive("A", "перший", 1_000L)
        arrive("B", "другий", 2_000L)
        assertEquals("прочитане повернулось у шторку", listOf("другий"), textsOf("marta"))
    }

    @Test
    fun `ключ — профіль, розмова й id, переживає смерть процесу, стеля тримається`() {
        val once = ShadeOnce(app)
        assertTrue(once.first("me", "marta", "m-1"))
        assertFalse("повтор дзвонить удруге", once.first("me", "marta", "m-1"))
        assertTrue("той самий id від іншої людини заглушено", once.first("me", "oleh", "m-1"))
        assertTrue("той самий id в іншому профілі заглушено", once.first("work", "marta", "m-1"))
        assertFalse("після смерті процесу забув", ShadeOnce(app).first("me", "marta", "m-1"))

        val small = ShadeOnce(app, cap = 3)
        listOf("a", "b", "c", "d").forEach { assertTrue(small.first("me", "x", it)) }
        assertFalse(ShadeOnce(app, cap = 3).first("me", "x", "d"))
        assertTrue("найстаріший не випав за стелею", ShadeOnce(app, cap = 3).first("me", "x", "a"))
    }

    @Test
    fun `остання дитина пішла — підсумкової плашки Nightpost теж немає`() {
        arrive("m-1", "привіт", 1_000L)
        arrive("o-1", "добрий", 1_500L, from = "oleh", name = "Олег")
        assertTrue("дві розмови мають підсумок", summary() != null)

        ShadeDoor.seen(app, "marta")
        assertNull("підсумок лишився над однією розмовою", summary())
        ShadeDoor.seen(app, "oleh")
        assertTrue("у шторці лишились плашки групи", shadowOf(nm).allNotifications.none { it.group == PeerNotifications.GROUP })
    }

    @Test
    fun `підсумок не рахує щойно прибрану плашку, навіть коли система ще її показує`() {
        val marta = PeerNotifications.idOf("marta")
        val oleh = PeerNotifications.idOf("oleh")
        assertEquals(listOf(oleh), PeerNotifications.grouped(listOf(marta, oleh, PeerNotifications.SUMMARY_ID), gone = marta))
    }

    @Test
    fun `розмова відкрита на екрані — шторка мовчить`() {
        val m = PeerMessage(id = "v-1", fromId = "marta", toId = "me", body = "тут", sentAtMs = 1L)
        assertNull(PeerAnnounce.notice(m, visibleChat = "marta", visibleGroup = null))
    }

    private fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "feature-stream").isDirectory) dir = dir.parentFile
        return dir ?: error("не знайшов корінь дерева (каталог feature-stream)")
    }

    private fun code(rel: String): String = File(repoRoot(), "app/src/main/java/local/phantom/companion/$rel").readText()
        .lineSequence()
        .filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `кожен вхідний лист іде через двері шторки, а свіжий питає видиму розмову`() {
        val container = code("data/AppContainer.kt")
        val announce = container.substringAfter("private fun announceMessage(").substringBefore("private fun announceFresh(")
        assertTrue(
            "вхідний лист оминає ShadeDoor",
            announce.contains("local.phantom.companion.service.ShadeDoor.arrived(applicationContext, mesh.selfIdFlow.value, message.fromId, message.id)"),
        )
        assertTrue(announce.contains("announceFresh(message)"))
        val fresh = container.substringAfter("private fun announceFresh(").substringBefore("private fun announceReaction(")
        assertTrue(
            "свіжий лист не питає видиму розмову",
            fresh.contains("visibleChat = events.visiblePeerChat.value.takeIf { chatsOnScreen() }"),
        )
    }

    @Test
    fun `відкрита розмова й межа прочитаного прибирають плашку`() {
        val bridge = code("ui/messenger/WebMessengerBridge.kt")
        val seen = "local.phantom.companion.service.ShadeDoor.seen(container.applicationContext, "
        val visible = bridge.substringAfter("private fun markVisible(").substringBefore("private fun markChrome(")
        assertTrue("відкрита розмова лишає плашку", visible.contains(seen + "id)"))
        val dm = bridge.substringAfter("""seg[1] == "conversations" && seg.size == 4 && seg[3] == "read" ->""").substringBefore("ok(")
        assertTrue("прочитана розмова лишає плашку", dm.contains(seen + "seg[2])"))
        val group = bridge.substringAfter("container.groupReads.markRead(group.id, newest)").substringBefore("ok(")
        assertTrue("прочитана група лишає плашку", group.contains(seen + "group.id)"))
    }
}
