package local.phantom.companion.ui.messenger

import android.app.Application
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.PowerManager
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.ui.messenger.CallAudioRoute.Kind
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
import org.robolectric.shadows.AudioDeviceInfoBuilder
import org.robolectric.shadows.ShadowPowerManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CallAudioRouteTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val audio = app.getSystemService(AudioManager::class.java)
    private val power = app.getSystemService(PowerManager::class.java)

    private fun device(type: Int): AudioDeviceInfo = AudioDeviceInfoBuilder.newBuilder().setType(type).build()

    private fun offer(vararg types: Int) =
        shadowOf(audio).setAvailableCommunicationDevices(types.map(::device))

    @Before
    fun proximity() {
        ShadowPowerManager.clearWakeLocks()
        shadowOf(power).setIsWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
    }

    @Test
    fun `тип пристрою стає маршрутом`() {
        assertEquals(Kind.EARPIECE, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
        assertEquals(Kind.SPEAKER, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals(Kind.BLUETOOTH, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(Kind.BLUETOOTH, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(Kind.WIRED, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(Kind.WIRED, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals(Kind.WIRED, CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertNull(CallAudioRoute.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
    }

    @Test
    fun `перелік — лише те, що справді є, без повторів`() {
        offer(
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_TELEPHONY,
        )
        assertEquals(listOf(Kind.EARPIECE, Kind.SPEAKER, Kind.BLUETOOTH), CallAudioRoute(app).available())
    }

    @Test
    fun `гучномовець справді перемикає звук`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        assertTrue(route.select(Kind.SPEAKER))
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, audio.communicationDevice?.type)
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    fun `маршрут, якого немає, не вибирається`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        assertFalse(route.select(Kind.BLUETOOTH))
        assertNull(audio.communicationDevice)
    }

    @Test
    fun `слухавка гасить екран біля вуха, гучномовець і кінець — ні`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.select(Kind.EARPIECE)
        val lock = ShadowPowerManager.getLatestWakeLock()
        assertTrue("біля вуха щока натискає кнопки: немає замка близькості", lock?.isHeld == true)
        route.select(Kind.SPEAKER)
        assertFalse(lock!!.isHeld)
        route.select(Kind.EARPIECE)
        route.release()
        assertFalse(ShadowPowerManager.getLatestWakeLock()!!.isHeld)
        assertNull(audio.communicationDevice)
    }

    @Test
    fun `форма для мосту`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        val chose = route.choose("speaker")
        assertTrue(chose.getBoolean("ok"))
        assertEquals("speaker", chose.getString("current"))
        val seen = route.describe()
        val routes = seen.getJSONArray("routes")
        assertEquals(listOf("earpiece", "speaker"), (0 until routes.length()).map { routes.getString(it) })
        assertEquals("speaker", seen.getString("current"))
        assertFalse(route.choose("radio").getBoolean("ok"))
    }

    @Test
    fun `голос — у слухавку, відео — у гучномовець, а не навпаки`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        audio.setCommunicationDevice(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        val route = CallAudioRoute(app)
        route.settle(video = false)
        assertEquals(Kind.EARPIECE, route.current())
        route.release()
        route.settle(video = true)
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    fun `гарнітура важить більше за слухавку`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
        val route = CallAudioRoute(app)
        route.settle(video = true)
        assertEquals(Kind.BLUETOOTH, route.current())
    }

    @Test
    fun `вибір людини розмова не перебиває`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.choose("speaker")
        route.settle(video = false)
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    @Config(sdk = [29])
    fun `до Android 12 гучномовець вмикається старим перемикачем`() {
        shadowOf(audio).setOutputDevices(listOf(device(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE), device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        val route = CallAudioRoute(app)
        assertEquals(listOf(Kind.EARPIECE, Kind.SPEAKER), route.available())
        assertTrue(route.select(Kind.SPEAKER))
        assertTrue(audio.isSpeakerphoneOn)
        assertEquals(Kind.SPEAKER, route.current())
        route.release()
        assertFalse(audio.isSpeakerphoneOn)
    }
    // ── WebView ставить гучномовець вдруге, коли починає грати голос співрозмовника ──

    @Test
    fun `WebView знову поставив гучномовець — маршрут вертає слухавку`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.settle(video = false)
        assertEquals(Kind.EARPIECE, route.current())

        audio.setCommunicationDevice(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        route.onCommunicationDeviceChanged()

        assertEquals("голос знову в гучномовці, а людина слухає біля вуха", Kind.EARPIECE, route.current())
    }

    @Test
    fun `людина сама обрала гучномовець — маршрут з нею не сперечається`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.settle(video = false)
        route.choose("speaker")
        route.onCommunicationDeviceChanged()
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    fun `без розмови маршрут нічого не вертає`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        audio.setCommunicationDevice(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        route.onCommunicationDeviceChanged()
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    fun `після кінця розмови маршрут більше не вертає нічого`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.settle(video = false)
        route.release()
        audio.setCommunicationDevice(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        route.onCommunicationDeviceChanged()
        assertEquals(Kind.SPEAKER, route.current())
    }

    @Test
    fun `рушій, що ставить гучномовець без кінця, не заганяє маршрут у війну`() {
        offer(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        val route = CallAudioRoute(app)
        route.settle(video = false)
        var fights = 0
        repeat(20) {
            audio.setCommunicationDevice(device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
            route.onCommunicationDeviceChanged()
            if (route.current() == Kind.EARPIECE) fights++
        }
        assertTrue("маршрут не втрутився жодного разу", fights >= 1)
        assertTrue("маршрут б'ється з рушієм $fights разів з 20", fights <= 5)
    }
}
