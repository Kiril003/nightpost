package local.phantom.companion.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.service.PushWake.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PushWakeTest {

    private var configured = true
    private var meshUp = true
    private var pulls = 0
    private var starts = 0
    private var now = 10_000L

    private fun wake() = PushWake(
        configured = { configured },
        pull = { pulls++; meshUp },
        startMesh = { starts++ },
        nowMs = { now },
    )

    private val ping = mapOf(PushWake.KEY to PushWake.VALUE)

    @Test
    fun `без файла налаштувань пуш не робить нічого`() {
        configured = false
        assertEquals(Outcome.OFF, wake().onData(ping))
        assertEquals("забір пішов без налаштувань", 0, pulls)
        assertEquals("сітку підняли без налаштувань", 0, starts)
    }

    @Test
    fun `порожній пінг будить застосунок і забирає зі сховка`() {
        assertEquals(Outcome.PULLED, wake().onData(ping))
        assertEquals(1, pulls)
        assertEquals(0, starts)
    }

    @Test
    fun `сітка не працює - пінг піднімає її службу`() {
        meshUp = false
        assertEquals(Outcome.STARTED, wake().onData(ping))
        assertEquals(1, starts)
    }

    @Test
    fun `пуш із вмістом не будить і нічого не читає`() {
        val w = wake()
        assertEquals(Outcome.IGNORED, w.onData(mapOf("title" to "Марта", "body" to "привіт")))
        assertEquals(Outcome.IGNORED, w.onData(ping + ("body" to "привіт")))
        assertEquals(Outcome.IGNORED, w.onData(mapOf(PushWake.KEY to "0")))
        assertEquals(Outcome.IGNORED, w.onData(emptyMap()))
        assertEquals(0, pulls)
        assertEquals(0, starts)
    }

    @Test
    fun `черга пінгів зливається в один забір`() {
        val w = wake()
        assertEquals(Outcome.PULLED, w.onData(ping))
        now += PushWake.MIN_GAP_MS - 1
        assertEquals(Outcome.COALESCED, w.onData(ping))
        assertEquals(1, pulls)
        now += 1
        assertEquals(Outcome.PULLED, w.onData(ping))
        assertEquals(2, pulls)
    }

    @Test
    fun `ігнорований пуш не відсуває наступний забір`() {
        val w = wake()
        w.onData(mapOf("title" to "x"))
        assertEquals(Outcome.PULLED, w.onData(ping))
    }

    @Test
    fun `налаштування є, коли плагін поклав google_app_id`() {
        assertTrue(PushConfig.present { name -> if (name == "google_app_id") "1:123:android:abc" else null })
        assertFalse(PushConfig.present { null })
        assertFalse(PushConfig.present { "  " })
    }

    @Test
    fun `збірка без файла не має google_app_id`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        assertFalse("у дереві з'явились налаштування Firebase без рішення власника", PushConfig.present(app))
    }

    @Test
    fun `спільний обробник без налаштувань вимкнений`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        assertEquals(Outcome.OFF, PushWake.shared(app).onData(ping))
    }
}
