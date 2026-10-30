package local.phantom.companion.service

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ДРУГА ПОЛОВИНА ДЕДЛАЙНУ.
 *
 * [ForegroundStartOrderTest] доводить, що служба виходить на передній план
 * ПЕРШИМ рядком. Цього мало: вбити процес можна й з іншого боку — прибрати
 * службу `stopService` у ту саму мить, поки система ще чекає на
 * `startForeground`. Логи emulator-5554 12.09:
 *
 *   W ActivityManager: Bringing down service while still waiting for start
 *                      foreground: ServiceRecord{… PeerMeshService}
 *   E AndroidRuntime: FATAL EXCEPTION: main
 *   ForegroundServiceDidNotStartInTimeException
 *
 * [PeerMeshGate] смикає службу щоразу, коли змінюється реєстр або видимість
 * екрана «Люди» — а системні діалоги дозволів роблять це кілька разів за
 * секунду.
 */
class ForegroundStopWindowTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "feature-stream").isDirectory }
        ?: error("не знайшла кореня сховища: жоден предок не має теки feature-stream")

    @Test
    fun `у відкритому вікні зупинку доставляють, а не рвуть ззовні`() {
        val window = ForegroundStartWindow()
        window.startRequested()
        assertTrue(window.open)
        assertEquals(ForegroundStartWindow.StopKind.DELIVER_STOP, window.stopKind())
    }

    @Test
    fun `після виходу на передній план звичайний stopService безпечний`() {
        val window = ForegroundStartWindow()
        window.startRequested()
        window.foregrounded()
        assertFalse(window.open)
        assertEquals(ForegroundStartWindow.StopKind.STOP_SERVICE, window.stopKind())
    }

    @Test
    fun `службу, якої не піднімали, прибирають напряму`() {
        assertEquals(ForegroundStartWindow.StopKind.STOP_SERVICE, ForegroundStartWindow().stopKind())
    }

    @Test
    fun `відмова системи на старті закриває вікно`() {
        val window = ForegroundStartWindow()
        window.startRequested()
        window.startRefused()
        assertEquals(ForegroundStartWindow.StopKind.STOP_SERVICE, window.stopKind())
    }

    @Test
    fun `дві зупинки поспіль не доставляють намір двічі`() {
        val window = ForegroundStartWindow()
        window.startRequested()
        assertEquals(ForegroundStartWindow.StopKind.DELIVER_STOP, window.stopKind())
        assertEquals(ForegroundStartWindow.StopKind.STOP_SERVICE, window.stopKind())
    }

    @Test
    fun `сітка не кличе stopService повз вікно`() {
        val src = File(
            root,
            "app/src/main/java/local/phantom/companion/service/PeerMeshService.kt",
        ).readText()
        assertTrue("файл служби не знайдено або порожній", src.isNotBlank())
        assertTrue(
            "PeerMeshService.stop мусить питати ForegroundStartWindow",
            src.contains("window.stopKind()"),
        )
        assertTrue(
            "підйом мусить закривати вікно",
            src.contains("window.foregrounded()"),
        )
        assertTrue(
            "доставлену зупинку мусить хтось приймати",
            src.contains("ACTION_STOP"),
        )
    }
}
