package local.phantom.companion.service

import android.Manifest
import android.app.ActivityManager
import android.app.Application
import android.app.Notification
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import local.phantom.companion.core.net.peer.PeerCallLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PeerCallServiceTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val callId = "call_9f31ac2b"
    private val marta = "cccc3333dddd4444"

    @Before
    fun grant() {
        PeerCallService.running = false
        PeerCallService.window.startRefused()
        PeerCallNotifications.current = null
        RefusesCamera.attempts.clear()
        RefusesEverything.attempts.clear()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO)
    }

    private fun startedCallService(): Intent? {
        while (true) {
            val next = shadowOf(app).nextStartedService ?: return null
            if (next.component?.className == PeerCallService::class.java.name) return next
        }
    }

    private fun startedCallIntents(): List<Intent> = buildList {
        while (true) {
            val next = shadowOf(app).nextStartedService ?: break
            if (next.component?.className == PeerCallService::class.java.name) add(next)
        }
    }

    private fun run(intent: Intent): PeerCallService =
        Robolectric.buildService(PeerCallService::class.java, intent).create().startCommand(0, 1).get()

    @Test
    fun `розмова тримає службу з мікрофоном`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        val intent = startedCallService()
        assertNotNull("розмова йде, а служби, що тримає процес, немає", intent)
        val service = run(intent!!)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        assertEquals(PeerCallNotifications.ID, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    fun `голосова розмова забирає звук із гучномовця, який поставив WebView`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val ear = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audio).setAvailableCommunicationDevices(listOf(ear, speaker))
        audio.setCommunicationDevice(speaker)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        run(startedCallService()!!)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
    }

    @Test
    fun `набір теж тримає службу`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Dialing(callId, marta, 1L), "Марта")
        assertNotNull(startedCallService())
    }

    @Test
    fun `відеодзвінок додає камеру, якщо камеру дали`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта", video = true)
        val service = run(startedCallService()!!)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            service.foregroundServiceType,
        )
    }

    @Test
    fun `відео без дозволу камери лишається на мікрофоні`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта", video = true)
        val service = run(startedCallService()!!)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
    }

    @Test
    fun `без мікрофона служба не вдає розмову, але спершу виходить на передній план — інакше система вб'є процес`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val service = run(PeerCallService.intent(app, video = false))
        assertEquals(
            "stopSelf до startForeground: ForegroundServiceDidNotStartInTimeException",
            PeerCallNotifications.ID,
            shadowOf(service).lastForegroundNotificationId,
        )
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse("розмови немає, а служба каже, що вона йде", PeerCallService.running)
    }

    @Test
    fun `кінець лінії зупиняє службу, що вже вийшла на передній план`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        run(startedCallService()!!)
        PeerCallNotifications.show(app, PeerCallLine.Line.Idle, "")
        val stopped = shadowOf(app).nextStoppedService
        assertEquals(PeerCallService::class.java.name, stopped?.component?.className)
    }

    @Test
    fun `поклали до виходу служби на передній план — зупинка їде службі наміром, а не stopService`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Dialing(callId, marta, 1L), "Марта")
        PeerCallNotifications.show(app, PeerCallLine.Line.Over(callId, marta, "busy"), "Марта")
        assertNull(
            "stopService у вікні підйому: Bringing down service while still waiting for start foreground",
            shadowOf(app).nextStoppedService,
        )
        val intents = startedCallIntents()
        assertEquals("набір і зупинка", 2, intents.size)
        assertEquals(PeerCallService.ACTION_STOP, intents[1].action)

        val controller = Robolectric.buildService(PeerCallService::class.java, intents[0]).create().startCommand(0, 1)
        val service = controller.get()
        assertEquals(PeerCallNotifications.ID, shadowOf(service).lastForegroundNotificationId)
        assertFalse(shadowOf(service).isStoppedBySelf)
        controller.withIntent(intents[1]).startCommand(0, 2)
        assertTrue("доставлена зупинка мусить прибрати службу", shadowOf(service).isStoppedBySelf)
        assertFalse(PeerCallService.running)
    }

    @Test
    fun `вибір маршруту без служби не переживає кінець розмови`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val ear = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audio).setAvailableCommunicationDevices(listOf(ear, speaker))
        val route = local.phantom.companion.ui.messenger.CallAudioRoute.shared(app)
        route.choose("speaker")

        PeerCallNotifications.show(app, PeerCallLine.Line.Over(callId, marta, "hangup"), "Марта")

        route.settle(video = false)
        assertEquals(
            "вибір минулої розмови керує наступною, бо служба, яка його скидає, не заводилась",
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
            audio.communicationDevice?.type,
        )
    }

    @Test
    fun `слухавкова розмова без служби не лишає замка близькості`() {
        val power = app.getSystemService(android.os.PowerManager::class.java)
        shadowOf(power).setIsWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        val audio = app.getSystemService(AudioManager::class.java)
        val ear = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        shadowOf(audio).setAvailableCommunicationDevices(listOf(ear))
        local.phantom.companion.ui.messenger.CallAudioRoute.shared(app).choose("earpiece")
        val lock = org.robolectric.shadows.ShadowPowerManager.getLatestWakeLock()
        assertNotNull(lock)
        assertTrue(lock.isHeld)

        PeerCallNotifications.show(app, PeerCallLine.Line.Idle, "")

        assertFalse("екран гасне біля вуха ще години по розмові", lock.isHeld)
    }

    @Test
    fun `друга зупинка поспіль іде вже звичайним stopService`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Dialing(callId, marta, 1L), "Марта")
        PeerCallNotifications.show(app, PeerCallLine.Line.Over(callId, marta, "busy"), "Марта")
        PeerCallNotifications.show(app, PeerCallLine.Line.Idle, "")
        assertNotNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun `камеру не дали служби — вона лишається на мікрофоні, а не падає`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        val service = Robolectric.buildService(
            RefusesCamera::class.java,
            PeerCallService.intent(app, video = true),
        ).create().startCommand(0, 1).get()
        assertEquals(
            listOf(
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            ),
            RefusesCamera.attempts,
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
        assertTrue("розмова йде, хоч і без камери", PeerCallService.running)
        assertFalse(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `система не пустила на передній план зовсім — служба випробовує всі типи й тільки тоді йде геть`() {
        val service = Robolectric.buildService(
            RefusesEverything::class.java,
            PeerCallService.intent(app, video = false),
        ).create().startCommand(0, 1).get()
        assertEquals(
            listOf(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST),
            RefusesEverything.attempts,
        )
        assertTrue("служба, що не вийшла на передній план, мусить піти, а не висіти", shadowOf(service).isStoppedBySelf)
        assertFalse(PeerCallService.running)
    }

    @Test
    fun `відео ввімкнули посеред голосової розмови — служба переходить на мікрофон і камеру`() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта", video = false)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            run(startedCallService()!!).foregroundServiceType,
        )
        PeerCallNotifications.videoChanged(app, true)
        val upgraded = startedCallService()
        assertNotNull("відео ввімкнули, а службу не перезапущено з камерою", upgraded)
        val service = run(upgraded!!)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            service.foregroundServiceType,
        )
        assertEquals("плашка тримає ім'я й текст розмови", PeerCallNotifications.ID, shadowOf(service).lastForegroundNotificationId)
    }

    @Test
    fun `відео змінилось, коли розмови немає, — служба не заводиться`() {
        PeerCallNotifications.videoChanged(app, true)
        assertNull(startedCallService())
    }

    private fun background(level: Int = ActivityManager.RunningAppProcessInfo.IMPORTANCE_CACHED) {
        val me = ActivityManager.RunningAppProcessInfo().apply {
            processName = app.packageName
            pid = android.os.Process.myPid()
            uid = android.os.Process.myUid()
            importance = level
        }
        shadowOf(app.getSystemService(ActivityManager::class.java)).setProcesses(listOf(me))
    }

    @Test
    fun `з фону першою служба не заводиться — інакше Android вбив би процес`() {
        background()
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        assertNull(startedCallService())
    }

    @Test
    fun `сітка тримає процес службою (125) — але це не передній план, і з неї служба розмови не стартує`() {
        // PeerMeshService (dataSync) живе всю розмову, тож замкнений екран — це 125, а не «кешований».
        background(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        assertNull("мікрофонна служба з фону: ForegroundServiceStartNotAllowedException", startedCallService())
    }

    @Test
    fun `взяли з плашки раніше, ніж екран став перед людиною — службу заводять, щойно він став`() {
        background(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        assertNull(startedCallService())

        background(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        PeerCallNotifications.holdCall(app)

        val intent = startedCallService()
        assertNotNull("екран перед людиною, розмова йде, а служби, що тримає мікрофон, немає", intent)
        assertEquals(PeerCallNotifications.ID, shadowOf(run(intent!!)).lastForegroundNotificationId)
    }

    @Test
    fun `служба вже тримає розмову — holdCall її не смикає`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        run(startedCallService()!!)
        PeerCallNotifications.holdCall(app)
        assertNull(startedCallService())
    }

    @Test
    fun `розмови немає — holdCall нічого не заводить`() {
        PeerCallNotifications.holdCall(app)
        assertNull(startedCallService())
    }

    @Test
    fun `екран на передньому плані — служба стартує`() {
        background(ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        assertNotNull(startedCallService())
    }

    @Test
    fun `взяли за замкненим екраном — служба вже тримає розмову, а голос вертається в слухавку без нового старту`() {
        val audio = app.getSystemService(AudioManager::class.java)
        val ear = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build()
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        shadowOf(audio).setAvailableCommunicationDevices(listOf(ear, speaker))
        audio.setCommunicationDevice(speaker)
        PeerCallService.running = true
        background()
        PeerCallNotifications.show(app, PeerCallLine.Line.Talking(callId, marta, 1L), "Марта")
        assertNull("startForegroundService з фону: ForegroundServiceStartNotAllowedException", startedCallService())
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, audio.communicationDevice?.type)
    }

    @Test
    fun `дзвінок, що лише дзвонить, служби не заводить`() {
        PeerCallNotifications.show(app, PeerCallLine.Line.Ringing(callId, marta, 1L), "Марта")
        assertNull(startedCallService())
    }
}

/** Камеру система не пускає (дозвіл відкликали, тип не оголошено): мікрофон мусить вціліти. */
class RefusesCamera : PeerCallService() {
    override fun foreground(notification: Notification, type: Int) {
        attempts += type
        if (type and ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA != 0) throw SecurityException("camera")
        super.foreground(notification, type)
    }

    companion object {
        val attempts = mutableListOf<Int>()
    }
}

/** Фон забороняє будь-який тип. */
class RefusesEverything : PeerCallService() {
    override fun foreground(notification: Notification, type: Int) {
        attempts += type
        throw IllegalStateException("not allowed")
    }

    companion object {
        val attempts = mutableListOf<Int>()
    }
}
