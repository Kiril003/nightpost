package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.R
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerBeacon
import local.phantom.companion.core.net.peer.PeerGroup
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.ChatHushStore
import local.phantom.companion.data.HushRule
import local.phantom.companion.data.LockScreenReveal
import local.phantom.companion.data.LockScreenRevealStore
import local.phantom.companion.data.PeerCircle
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerRosterStore
import local.phantom.companion.data.ProfilesMirror
import local.phantom.companion.data.Quiet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * Одне сповіщення на людину, стабільне між запусками.
 *
 * Тут ламається тихо: збіг id — і повідомлення від одного друга затирає
 * повідомлення від іншого; нестабільний id — і після перезапуску процесу
 * розмова роздвоюється на дві плашки. Ні того, ні того на екрані не видно як
 * помилку, тому це пінується числом.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    private class MemSlot : PeerLogSlot {
        val cells = HashMap<String, String>()
        override fun read(key: String): String? = cells[key]
        override fun write(key: String, value: String?) {
            if (value == null) cells.remove(key) else cells[key] = value
        }
    }

    private lateinit var roster: PeerRosterStore

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        roster = PeerRosterStore(MemSlot())
        PeerNotifications.rosterOf = { roster }
        PeerNotifications.lockHides = { false }
    }

    @After
    fun tearDown() {
        PeerNotifications.resetRoster()
    }

    /**
     * Типове значення тут — це те, що побачить кожен, хто не заходив у
     * налаштування, тобто майже кожен. Воно мусить бути безпечним, і зіпсований
     * запис у преференсах теж мусить падати в безпечний бік, а не в FULL.
     */
    @Test
    fun `lock screen defaults to safe and never widens on bad data`() {
        assertNotEquals(LockScreenReveal.FULL, LockScreenRevealStore.SAFE_DEFAULT)
        assertTrue(
            "прихованіший рівень має давати менше число видимості",
            LockScreenReveal.HIDDEN.visibility() < LockScreenReveal.REDACTED.visibility() &&
                LockScreenReveal.REDACTED.visibility() < LockScreenReveal.FULL.visibility(),
        )
        val settings = LockScreenRevealStore.Settings(
            default = LockScreenRevealStore.SAFE_DEFAULT,
            perConversation = mapOf("marta" to LockScreenReveal.FULL),
        )
        assertEquals(LockScreenReveal.FULL, settings.forConversation("marta"))
        assertEquals(LockScreenRevealStore.SAFE_DEFAULT, settings.forConversation("bohdan"))
    }

    @Test
    fun `T6 - a person who never chose follows the phone, a stored choice stays`() {
        assertEquals(LockScreenReveal.SYSTEM, LockScreenRevealStore.SAFE_DEFAULT)
        assertEquals(LockScreenReveal.REDACTED.visibility(), LockScreenReveal.SYSTEM.visibility())
        val prefs = app.getSharedPreferences("phantom_lockscreen_reveal_v1", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        assertEquals(LockScreenReveal.SYSTEM, LockScreenRevealStore(app).state.value.default)
        prefs.edit().putString("default", "REDACTED").commit()
        assertEquals(LockScreenReveal.REDACTED, LockScreenRevealStore(app).state.value.default)
        prefs.edit().clear().commit()
    }

    @Test
    fun `T6 - where the phone hides content the lock screen still names who wrote`() {
        val store = LockScreenRevealStore.of(app)
        try {
            store.setDefault(LockScreenReveal.SYSTEM)
            val notice = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
            PeerNotifications.notifyMessage(app, notice, "Марта", "завтра о сьомій", 1_000L)
            val nm = app.getSystemService(NotificationManager::class.java)
            val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))!!
            assertEquals(android.app.Notification.VISIBILITY_PRIVATE, posted.visibility)
            val pub = posted.publicVersion!!
            assertEquals("Марта", pub.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
            assertEquals(app.getString(R.string.peer_hidden_message), pub.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            val all = pub.extras.keySet().joinToString(" ") { k -> pub.extras.get(k)?.toString().orEmpty() }
            assertFalse("the words leak past the phone's own setting", all.contains("завтра о сьомій"))

            store.setDefault(LockScreenReveal.REDACTED)
            PeerNotifications.notifyMessage(app, notice, "Марта", "ще слово", 2_000L)
            val redacted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))!!.publicVersion!!
            val words = redacted.extras.keySet().joinToString(" ") { k -> redacted.extras.get(k)?.toString().orEmpty() }
            assertFalse("«only New message» names the person", words.contains("Марта"))
        } finally {
            store.setDefault(LockScreenRevealStore.SAFE_DEFAULT)
        }
    }

    @Test
    fun `distinct peers get distinct notifications`() {
        val ids = (0 until 500)
            .map { "%016x".format(it * 0x9E3779B9L) }
            .map { PeerNotifications.idOf(it) }
        assertEquals("id collision across peers", ids.size, ids.toSet().size)
    }

    @Test
    fun `the same peer keeps the same notification`() {
        val id = "a1b2c3d4e5f60718"
        assertEquals(PeerNotifications.idOf(id), PeerNotifications.idOf(id))
    }

    @Test
    fun `peer ids never reach the air-alert or mesh notifications`() {
        val taken = setOf(
            AirAlertNotifications.NOTIF_ALARM,
            StatusShade.NOTIF_ID,
        )
        val mine = (0 until 500).map { PeerNotifications.idOf("peer-$it") }
        assertTrue("peer notification overwrote an alert", mine.none { it in taken })
        assertTrue("peer id below its own base", mine.all { it >= 4400 })
    }

    @Test
    fun `the message channel is not the mesh channel`() {
        assertNotEquals(PeerNotifications.CHANNEL, StatusShade.CHANNEL)
        assertNotEquals(PeerNotifications.CHANNEL, PeerBeacon.SERVICE_TYPE)
    }

    /*
     * Тап по сповіщенню групи вів на `phantom://peer/<id групи>`: такого
     * контакту немає, екран відкривався й одразу закривався. Оператор
     * натискав — і не відбувалось нічого, крім зниклої плашки.
     */

    private val group = PeerGroup.Group("abc123abc123", "Північ", listOf("me", "marta", "oleh"))

    private fun line(from: String, body: String) = PeerMessage(
        id = "m1",
        fromId = from,
        toId = "me",
        body = body,
        sentAtMs = 1_000L,
    )

    @Test
    fun `a group line sends the tap to the group, not to a contact that does not exist`() {
        val notice = PeerAnnounce.notice(line("marta", PeerGroup.encode(group, "виходимо")), null, null)
        assertEquals("phantom://peergroup/abc123abc123", PeerNotifications.linkFor(notice!!))
    }

    @Test
    fun `a private line still sends the tap to the person who wrote`() {
        val notice = PeerAnnounce.notice(line("marta", "тут"), null, null)
        assertEquals("phantom://peer/marta", PeerNotifications.linkFor(notice!!))
    }

    @Test
    fun `the two never lead to the same place`() {
        val asGroup = PeerAnnounce.notice(line("marta", PeerGroup.encode(group, "виходимо")), null, null)
        val asPerson = PeerAnnounce.notice(line(group.id, "тут"), null, null)
        assertNotEquals(
            "a group and a person with the same id must not share a destination",
            PeerNotifications.linkFor(asGroup!!),
            PeerNotifications.linkFor(asPerson!!),
        )
    }

    @Test
    fun `both hosts are declared in the manifest, or the tap has nowhere to land`() {
        val manifest = generateSequence(File(".").absoluteFile) { it.parentFile }
            .take(6)
            .map { File(it, "app/src/main/AndroidManifest.xml") }
            .firstOrNull { it.isFile }
            ?: error("AndroidManifest.xml not found from ${File(".").absolutePath}")
        val browsable = manifest.readText()
            .split("<intent-filter>")
            .filter { it.contains("android.intent.category.BROWSABLE") }
            .joinToString("\n")
        for (host in listOf(PeerNotifications.HOST_PEER, PeerNotifications.HOST_GROUP)) {
            assertTrue(
                "phantom://$host/… is produced but no filter accepts it",
                browsable.contains("""android:host="$host""""),
            )
        }
    }

    @Test
    fun `P01 - a muted chat puts nothing in the shade, an expired mute rings again`() {
        val hush = ChatHushStore(app)
        val nm = app.getSystemService(NotificationManager::class.java)
        try {
            hush.set("marta", null)
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "тихо"), null, null)!!, "Марта", "тихо", 1_000L)
            assertNull("a muted chat still rang", shadowOf(nm).getNotification(PeerNotifications.idOf("marta")))

            val group = PeerAnnounce.Notice(key = "g-hush", fromId = "oleh", groupId = "g-hush", groupName = "Дім", text = "у групі")
            hush.set("g-hush", System.currentTimeMillis() + 3_600_000L)
            PeerNotifications.notifyMessage(app, group, "Олег", "у групі", 2_000L)
            assertNull("a muted group still rang", shadowOf(nm).getNotification(PeerNotifications.idOf("g-hush")))

            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("bohdan", "не тихо"), null, null)!!, "Богдан", "не тихо", 3_000L)
            assertNotNull("muting one chat silenced another", shadowOf(nm).getNotification(PeerNotifications.idOf("bohdan")))

            hush.set("marta", System.currentTimeMillis() - 1L)
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "знову"), null, null)!!, "Марта", "знову", 4_000L)
            assertNotNull("an expired mute stayed silent", shadowOf(nm).getNotification(PeerNotifications.idOf("marta")))
        } finally {
            hush.clear("marta")
            hush.clear("g-hush")
        }
    }

    @Test
    fun `Q1 - web hush rules silence the shade, a missing rule set rings as before, a mention still breaks through`() {
        val hush = ChatHushStore(app)
        PeerNotifications.hushOf = { hush }
        val nm = app.getSystemService(NotificationManager::class.java)
        try {
            hush.setRules(emptyMap())
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "привіт"), null, null)!!, "Марта", "привіт", 1_000L)
            assertNotNull("no rules from the web must ring as today", shadowOf(nm).getNotification(PeerNotifications.idOf("marta")))
            nm.cancelAll()

            hush.setRules(mapOf("marta" to HushRule(), group.id to HushRule()))
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "тихо"), null, null)!!, "Марта", "тихо", 2_000L)
            assertNull("a chat muted by rule still rang", shadowOf(nm).getNotification(PeerNotifications.idOf("marta")))

            val plain = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "просто так")), null, null, selfId = "me")!!
            PeerNotifications.notifyMessage(app, plain, "Олег", "просто так", 3_000L)
            assertNull("a group muted by its circle rule still rang", shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)))

            val mine = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "@Кирило глянь")).copy(mentions = listOf("me")), null, null, selfId = "me")!!
            PeerNotifications.notifyMessage(app, mine, "Олег", "@Кирило глянь", 4_000L)
            assertNotNull("a mention of me stopped breaking through", shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)))

            hush.setRules(mapOf("marta" to HushRule(ask = true)))
            PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "ти де?"), null, null)!!, "Марта", "ти де?", 5_000L)
            assertNotNull("a direct question did not pass the ask exception", shadowOf(nm).getNotification(PeerNotifications.idOf("marta")))
        } finally {
            hush.setRules(emptyMap())
        }
    }

    @Test
    fun `P10 - a mention of me rings through a muted group, a plain line does not, and the switch turns it off`() {
        val hush = ChatHushStore(app)
        PeerNotifications.hushOf = { hush }
        val nm = app.getSystemService(NotificationManager::class.java)
        try {
            hush.set(group.id, null)
            val plain = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "@Марта глянь")).copy(mentions = listOf("marta")), null, null, selfId = "me")!!
            PeerNotifications.notifyMessage(app, plain, "Олег", "@Марта глянь", 1_000L)
            assertNull("a muted group rang for someone else's mention", shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)))

            hush.setMentionsThrough(false)
            val mine = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "@Кирило глянь")).copy(mentions = listOf("me")), null, null, selfId = "me")!!
            PeerNotifications.notifyMessage(app, mine, "Олег", "@Кирило глянь", 2_000L)
            assertNull("mentions turned off still rang", shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)))

            hush.setMentionsThrough(true)
            PeerNotifications.notifyMessage(app, mine, "Олег", "@Кирило глянь", 3_000L)
            assertNotNull("a mention of me stayed out of the shade", shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)))
        } finally {
            hush.clear(group.id)
            hush.setMentionsThrough(true)
        }
    }

    @Test
    fun `P10 - a mention of me rings in profile quiet hours, a plain group line stays silent`() {
        val mirror = ProfilesMirror(Files.createTempDirectory("notif-mention").toFile())
        mirror.apply(
            """{"v":1,"activeId":"p","profiles":[{"id":"p","name":"Фокус","kind":"work","passGroups":["close"],"quiet":{"from":"22:00","to":"07:00"}}]}""",
        )
        PeerNotifications.mirrorOf = { mirror }
        PeerNotifications.minuteOfDayNow = { 23 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)

        val plain = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "разом")), null, null, selfId = "me")!!
        PeerNotifications.notifyMessage(app, plain, "Олег", "разом", 1_000L)
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)).groupAlertBehavior)

        nm.cancelAll()
        val mine = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "@Кирило")).copy(mentions = listOf("me")), null, null, selfId = "me")!!
        PeerNotifications.notifyMessage(app, mine, "Олег", "@Кирило", 2_000L)
        assertNotEquals(android.app.Notification.GROUP_ALERT_SUMMARY, shadowOf(nm).getNotification(PeerNotifications.idOf(group.id)).groupAlertBehavior)
    }

    @Test
    fun `close peer posts to the close channel`() {
        roster.addToCircle("marta", "close")
        val notice = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "привіт", 1_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        assertNotNull(posted)
        assertEquals("phantom_peer_message_close_v1", posted.channelId)
        val channel = nm.getNotificationChannel("phantom_peer_message_close_v1")
        assertNotNull(channel)
        assertEquals(app.getString(R.string.circle_close), channel.name.toString())
    }

    @Test
    fun `peer in two rings uses the closer one`() {
        roster.addToCircle("oleh", "work")
        roster.addToCircle("oleh", "close")
        val notice = PeerAnnounce.notice(line("oleh", "тут"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Олег", "тут", 2_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("oleh"))
        assertNotNull(posted)
        assertEquals("phantom_peer_message_close_v1", posted.channelId)
    }

    @Test
    fun `peer in no ring uses the default channel`() {
        val notice = PeerAnnounce.notice(line("taras", "алло"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Тарас", "алло", 3_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("taras"))
        assertNotNull(posted)
        assertEquals(PeerNotifications.CHANNEL, posted.channelId)
    }

    @Test
    fun `vibration patterns of the ring channels differ`() {
        val nm = app.getSystemService(NotificationManager::class.java)
        val patterns = PeerCircle.ALL.map { ring ->
            roster.setCircle("peer-$ring", ring)
            val notice = PeerAnnounce.notice(line("peer-$ring", "слово"), null, null)!!
            PeerNotifications.notifyMessage(app, notice, ring, "слово", 4_000L)
            val channel = nm.getNotificationChannel("phantom_peer_message_${ring}_v1")
            assertNotNull("missing channel for $ring", channel)
            val pattern = channel.vibrationPattern
            assertNotNull("missing vibration pattern for $ring", pattern)
            assertTrue("empty vibration pattern for $ring", pattern.isNotEmpty())
            pattern.toList()
        }
        assertEquals(PeerCircle.ALL.size, patterns.toSet().size)
    }

    @Test
    fun `hidden notification does not name the ring through its channel`() {
        PeerNotifications.lockHides = { true }
        roster.addToCircle("marta", "close")
        val notice = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "привіт", 5_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        assertNotNull(posted)
        assertEquals(PeerNotifications.CHANNEL, posted.channelId)
        val extras = posted.extras.keySet().joinToString(" ") { k -> posted.extras.get(k)?.toString().orEmpty() }
        assertFalse("hidden notification leaks the name", extras.contains("Марта"))
        assertFalse("hidden notification leaks the text", extras.contains("привіт"))
    }

    @Test
    fun `a reply after the lock turns on does not reveal the conversation`() {
        roster.addToCircle("marta", "close")
        val notice = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "привіт", 7_000L)
        PeerNotifications.lockHides = { true }
        PeerNotifications.noteOwnReply(app, notice.key, "буду о сьомій")
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf(notice.key))
        assertNotNull(posted)
        assertEquals(PeerNotifications.CHANNEL, posted.channelId)
        val extras = posted.extras.keySet().joinToString(" ") { k -> posted.extras.get(k)?.toString().orEmpty() }
        assertFalse("locked reply leaks the name", extras.contains("Марта"))
        assertFalse("locked reply leaks the thread", extras.contains("привіт"))
        assertFalse("locked reply leaks the reply", extras.contains("буду о сьомій"))
    }

    @Test
    fun `D1 - a lock read failure names the sender but never shows the text`() {
        assertNull(PeerNotifications.hidesContent { error("keystore") })
        PeerNotifications.lockHides = { null }
        val notice = PeerAnnounce.notice(line("marta", "таємниця"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "таємниця", 1_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        assertEquals("Марта", posted.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString())
        assertEquals(app.getString(R.string.peer_hidden_message), posted.extras.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
        assertFalse("таємниця" in posted.extras.toString())
        assertNull(posted.publicVersion.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.takeIf { it == "Марта" })
        assertEquals(PeerNotifications.linkFor(notice), shadowOf(posted.contentIntent).savedIntent.data?.toString())
    }

    @Test
    fun `D1 - a hidden notification still opens that chat`() {
        PeerNotifications.lockHides = { true }
        val notice = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "привіт", 1_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        val opened = shadowOf(posted.contentIntent).savedIntent
        assertEquals(PeerNotifications.linkFor(notice), opened.data?.toString())
    }

    @Test
    fun `group line from a close sender stays on the default channel`() {
        roster.addToCircle("marta", "close")
        val notice = PeerAnnounce.notice(line("marta", PeerGroup.encode(group, "виходимо")), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Марта", "виходимо", 6_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf(notice.key))
        assertNotNull(posted)
        assertEquals(PeerNotifications.CHANNEL, posted.channelId)
    }

    @Test
    fun `passes is true for empty passGroups, true on intersection, false otherwise`() {
        val openProfile = ProfilesMirror.Profile(id = "p1", passGroups = emptyList())
        assertTrue(openProfile.passes(emptySet()))
        assertTrue(openProfile.passes(setOf("work")))

        val workClose = ProfilesMirror.Profile(id = "p2", passGroups = listOf("close", "work"))
        assertTrue(workClose.passes(setOf("close")))
        assertTrue(workClose.passes(setOf("friends", "work")))
        assertFalse(workClose.passes(setOf("friends", "social")))
        assertFalse(workClose.passes(emptySet()))
    }

    @Test
    fun `quietAt handles overnight and daytime ranges`() {
        val q = Quiet(from = "22:00", to = "07:00")
        assertTrue(PeerNotifications.quietAt(q, 23 * 60 + 30))
        assertTrue(PeerNotifications.quietAt(q, 6 * 60 + 59))
        assertFalse(PeerNotifications.quietAt(q, 7 * 60))
        assertFalse(PeerNotifications.quietAt(q, 21 * 60 + 59))
        assertTrue(PeerNotifications.quietAt(q, 22 * 60))
    }

    @Test
    fun `v1 - sender outside passGroups is posted silently, never dropped, and quiet hours silence everyone`() {
        val mirror = ProfilesMirror(Files.createTempDirectory("notif-mirror").toFile())
        mirror.apply(
            """
            {
              "v": 1,
              "activeId": "p-focus",
              "profiles": [
                {
                  "id": "p-focus",
                  "name": "Фокус",
                  "kind": "work",
                  "passGroups": ["close"],
                  "quiet": { "from": "22:00", "to": "07:00" }
                }
              ]
            }
            """.trimIndent(),
        )
        PeerNotifications.mirrorOf = { mirror }
        PeerNotifications.minuteOfDayNow = { 23 * 60 + 30 }

        roster.addToCircle("oleh", "work")
        roster.addToCircle("marta", "close")

        val nm = app.getSystemService(NotificationManager::class.java)
        val fromOleh = PeerAnnounce.notice(line("oleh", "звіт"), null, null)!!
        PeerNotifications.notifyMessage(app, fromOleh, "Олег", "звіт", 8_000L)
        val oleh = shadowOf(nm).getNotification(PeerNotifications.idOf("oleh"))
        assertNotNull(oleh)
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, oleh.groupAlertBehavior)

        val fromMarta = PeerAnnounce.notice(line("marta", "привіт"), null, null)!!
        PeerNotifications.notifyMessage(app, fromMarta, "Марта", "привіт", 9_000L)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        assertNotNull(posted)
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, posted.groupAlertBehavior)

        val groupNotice = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "разом")), null, null)!!
        PeerNotifications.notifyMessage(app, groupNotice, "Олег", "разом", 10_000L)
        assertNotNull(shadowOf(nm).getNotification(PeerNotifications.idOf(groupNotice.key)))
    }

    private fun switchPayload(active: String) =
        """
        {"v":1,"activeId":"$active","profiles":[
          {"id":"p","name":"Особисте","kind":"personal","passGroups":[],"quiet":null,"expiresAt":null,"faces":{},
           "kit":{"tabs":["chats"],"quick":[],"notify":"all","assistant":"all","wallet":"personal"}},
          {"id":"s","name":"Школа","kind":"study","passGroups":["work"],"quiet":{"from":"16:00","to":"08:00"},
           "expiresAt":null,"faces":{"work":{"name":"Марта Ігорівна"}},
           "kit":{"tabs":["chats"],"quick":[],"notify":"hours","assistant":"none","wallet":null}}
        ]}
        """.trimIndent()

    @Test
    fun `перемикання профілю з вебу глушить плашку в години тиші нового профілю`() {
        val mirror = ProfilesMirror(Files.createTempDirectory("switch-quiet").toFile())
        PeerNotifications.mirrorOf = { mirror }
        PeerNotifications.minuteOfDayNow = { 18 * 60 }
        roster.addToCircle("marta", "work")
        val nm = app.getSystemService(NotificationManager::class.java)
        val notice = PeerAnnounce.notice(line("marta", "завтра контрольна"), null, null)!!

        assertTrue(mirror.apply(switchPayload("p")))
        PeerNotifications.notifyMessage(app, notice, "Марта", "завтра контрольна", 1_000L)
        val loud = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))!!
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, loud.groupAlertBehavior)

        assertTrue(mirror.apply(switchPayload("s")))
        PeerNotifications.notifyMessage(app, notice, "Марта", "завтра контрольна", 2_000L)
        val hushed = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))!!
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, hushed.groupAlertBehavior)
    }

    @Test
    fun `перемикання профілю з вебу міняє обличчя для кола і будить оголошення профілю`() {
        var changes = 0
        val mirror = ProfilesMirror(Files.createTempDirectory("switch-face").toFile(), onChanged = { changes++ })
        assertTrue(mirror.apply(switchPayload("p")))
        assertNull(mirror.faceFor(setOf("work")))

        assertTrue(mirror.apply(switchPayload("s")))
        assertEquals(2, changes)
        assertEquals("Марта Ігорівна", mirror.faceFor(setOf("work"))?.name)
        assertNull(mirror.faceFor(setOf("friends")))
    }

    private fun owned(active: String, peers: String = "", chats: String = "") =
        """{"v":2,"activeId":"$active","profiles":[
          {"id":"p","name":"Особисте","kind":"personal"},
          {"id":"w","name":"Робота","kind":"work","passGroups":["work"],"quiet":{"from":"22:00","to":"07:00"}},
          {"id":"b","name":"Бізнес","kind":"business","passGroups":["work"]}],
          "peerProfile":{$peers},"chatProfile":{$chats}}"""

    private fun ownedMirror(json: String): ProfilesMirror =
        ProfilesMirror(Files.createTempDirectory("notif-owned").toFile()).also { m ->
            assertTrue(m.apply(json))
            PeerNotifications.mirrorOf = { m }
        }

    private fun titleOf(n: android.app.Notification): String? =
        androidx.core.app.NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.conversationTitle?.toString()

    @Test
    fun `незнайомець у розмові «Бізнесу» дзвонить, поки активне «Особисте», і заголовок каже профіль`() {
        ownedMirror(owned("p", peers = "\"ivan\":\"b\"", chats = "\"ivan\":\"b\""))
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("ivan", "є столик?"), null, null)!!, "Іван", "є столик?", 1_000L)
        val n = shadowOf(nm).getNotification(PeerNotifications.idOf("ivan"))
        assertNotNull(n)
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, n.groupAlertBehavior)
        assertEquals(app.getString(R.string.peer_in_profile, "Іван", "Бізнес"), titleOf(n))
    }

    @Test
    fun `розмова активного профілю — без імені профілю в заголовку`() {
        ownedMirror(owned("b", peers = "\"ivan\":\"b\""))
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("ivan", "є столик?"), null, null)!!, "Іван", "є столик?", 1_000L)
        assertNull(titleOf(shadowOf(nm).getNotification(PeerNotifications.idOf("ivan"))!!))
    }

    @Test
    fun `близька людина в «Роботі» в години тиші — плашка є, але тиха`() {
        ownedMirror(owned("p", peers = "\"marta\":\"w\""))
        roster.addToCircle("marta", "close")
        PeerNotifications.minuteOfDayNow = { 23 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "ти тут?"), null, null)!!, "Марта", "ти тут?", 1_000L)
        val n = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))
        assertNotNull(n)
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertEquals(app.getString(R.string.peer_in_profile, "Марта", "Робота"), titleOf(n))
    }

    @Test
    fun `група йде за профілем своєї розмови, а не за профілем автора`() {
        ownedMirror(owned("p", peers = "\"oleh\":\"p\"", chats = "\"${group.id}\":\"w\""))
        PeerNotifications.minuteOfDayNow = { 23 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        val notice = PeerAnnounce.notice(line("oleh", PeerGroup.encode(group, "звіт завтра")), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Олег", "звіт завтра", 1_000L)
        val n = shadowOf(nm).getNotification(PeerNotifications.idOf(notice.key))
        assertNotNull(n)
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
        assertEquals(app.getString(R.string.peer_in_profile, group.name, "Робота"), titleOf(n))
    }

    @Test
    fun `v2 - людина поза колами профілю не викидається і не глушиться`() {
        ownedMirror(owned("b", peers = "\"anna\":\"b\""))
        roster.addToCircle("anna", "friends")
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("anna", "привіт"), null, null)!!, "Анна", "привіт", 1_000L)
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, shadowOf(nm).getNotification(PeerNotifications.idOf("anna"))!!.groupAlertBehavior)
    }

    private fun alerting(alert: String) =
        """{"v":2,"activeId":"p","profiles":[
          {"id":"p","name":"Особисте","kind":"personal"},
          {"id":"w","name":"Робота","kind":"work","alert":"$alert"}],
          "peerProfile":{"oleh":"w"},"chatProfile":{}}"""

    @Test
    fun `«Робота» на вібрації — плашка йде в канал без звуку, з вібрацією кола`() {
        ownedMirror(alerting("vibrate"))
        roster.addToCircle("oleh", "work")
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("oleh", "звіт"), null, null)!!, "Олег", "звіт", 1_000L)
        val n = shadowOf(nm).getNotification(PeerNotifications.idOf("oleh"))!!
        assertEquals("phantom_peer_message_work_buzz_v1", n.channelId)
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, n.groupAlertBehavior)
        val channel = nm.getNotificationChannel("phantom_peer_message_work_buzz_v1")!!
        assertNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        assertEquals(PeerNotifications.vibrationForRing("work").toList(), channel.vibrationPattern.toList())
    }

    @Test
    fun `«Робота» без сигналу — плашка є, але тиха, а «Особисте» дзвонить як було`() {
        ownedMirror(alerting("silent"))
        roster.addToCircle("oleh", "work")
        roster.addToCircle("marta", "close")
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("oleh", "звіт"), null, null)!!, "Олег", "звіт", 1_000L)
        val oleh = shadowOf(nm).getNotification(PeerNotifications.idOf("oleh"))!!
        assertEquals(android.app.Notification.GROUP_ALERT_SUMMARY, oleh.groupAlertBehavior)
        assertEquals("phantom_peer_message_work_v1", oleh.channelId)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("marta", "привіт"), null, null)!!, "Марта", "привіт", 2_000L)
        val marta = shadowOf(nm).getNotification(PeerNotifications.idOf("marta"))!!
        assertEquals(android.app.Notification.GROUP_ALERT_ALL, marta.groupAlertBehavior)
        assertEquals("phantom_peer_message_close_v1", marta.channelId)
    }

    @Test
    fun `вібрація при замкненому екрані теж без звуку`() {
        ownedMirror(alerting("vibrate"))
        PeerNotifications.lockHides = { true }
        PeerNotifications.minuteOfDayNow = { 12 * 60 }
        val nm = app.getSystemService(NotificationManager::class.java)
        PeerNotifications.notifyMessage(app, PeerAnnounce.notice(line("oleh", "звіт"), null, null)!!, "Олег", "звіт", 1_000L)
        assertEquals("phantom_peer_message_buzz_v1", shadowOf(nm).getNotification(PeerNotifications.idOf("oleh"))!!.channelId)
        assertNull(nm.getNotificationChannel("phantom_peer_message_buzz_v1")!!.sound)
    }

    @Test
    fun `веб бачить рядок «Сигнал» лише коли телефон його виконує`() {
        val m = Class.forName("local.phantom.companion.ui.messenger.WebMessengerBridge").getMethod("profileAlerts")
        assertNotNull(m.getAnnotation(android.webkit.JavascriptInterface::class.java))
    }
}
