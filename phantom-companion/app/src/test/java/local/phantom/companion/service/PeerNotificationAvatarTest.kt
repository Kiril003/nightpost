package local.phantom.companion.service

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import local.phantom.companion.core.net.peer.PeerAnnounce
import local.phantom.companion.core.net.peer.PeerIdentity
import local.phantom.companion.core.net.peer.PeerMessage
import local.phantom.companion.data.PeerLogSlot
import local.phantom.companion.data.PeerRosterStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** U13: у шторці — фото людини, а не лише літера; без фото лишається літера. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], application = Application::class)
class PeerNotificationAvatarTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var roster: PeerRosterStore

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
        roster = PeerRosterStore(MemSlot())
        PeerNotifications.rosterOf = { roster }
        PeerNotifications.lockHides = { false }
    }

    @After
    fun tearDown() = PeerNotifications.resetRoster()

    @Test
    fun `U13 - the sender's photo is the face in the notification`() {
        roster.upsert(person("marta", photoB64(PHOTO)))
        assertEquals(PHOTO, centreOfSender("marta"))
    }

    @Test
    fun `U13 - without a photo the initial stays`() {
        roster.upsert(person("oleh", null))
        assertNotEquals(PHOTO, centreOfSender("oleh"))
    }

    private fun centreOfSender(id: String): Int {
        val notice = PeerAnnounce.notice(PeerMessage(id = "m-$id", fromId = id, toId = "me", body = "привіт", sentAtMs = 1_000L), null, null)!!
        PeerNotifications.notifyMessage(app, notice, "Ім'я", "привіт", 1_000L)
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).getNotification(PeerNotifications.idOf(id))
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(posted)!!
        val icon = style.messages.last().person?.icon
        assertNotNull("сповіщення без обличчя відправника", icon)
        val drawable = icon!!.toIcon(app).loadDrawable(app)!!
        val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, 64, 64)
        drawable.draw(Canvas(bmp))
        return bmp.getPixel(32, 20)
    }

    private fun person(id: String, photo: String?) =
        PeerIdentity(id = id, name = id, kind = PeerIdentity.Kind.PERSON, pubKey = "k-$id", avatarThumbB64 = photo)

    private fun photoB64(color: Int): String {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return java.util.Base64.getEncoder().encodeToString(out.toByteArray())
    }

    private companion object {
        val PHOTO = Color.rgb(32, 96, 192)
    }
}
